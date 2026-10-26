"""Extract intent through the shared adapter and bind caller-owned provenance."""

from datetime import datetime, timezone
from typing import Protocol

from pydantic import ValidationError

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.diagnostics import schema_failure
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION, build_extraction_prompt
from copenhagen_fds.analysis.transport_models import CompletionPrompt, StructuredCompletion
from copenhagen_fds.contracts import (
    AnalysisFailure, AnalysisTelemetry, DataSource, EvaluationMetadata, EvaluationReason,
    IntentAnalysisRequest, IntentExtractionOutput, IntentExtractionResult,
)
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.settings import EvaluationMode


class CompletionClient(Protocol):
    settings: OpenAISettings

    async def complete(self, prompt: CompletionPrompt, *, budget: AnalysisBudget) -> StructuredCompletion: ...


class IntentExtractor:
    """The caller owns the adapter and shares the budget with later comparison."""

    def __init__(self, adapter: CompletionClient):
        self._adapter = adapter

    async def extract(
        self, request: IntentAnalysisRequest, *, trace_id: str, budget: AnalysisBudget,
    ) -> IntentExtractionResult:
        settings = self._adapter.settings
        if budget.total_seconds > settings.evaluation_timeout_seconds:
            raise ValueError("analysis budget exceeds the configured total timeout")
        expected_telemetry = AnalysisTelemetry(
            gateway="MOCK" if settings.mode == EvaluationMode.MOCK else "GMS",
            requested_model=settings.openai_model,
        )
        # Validate caller metadata before spending any provider budget. Do not
        # attach a Pydantic error (which retains its inputs) to a public exception.
        metadata = None
        try:
            metadata = EvaluationMetadata(
                target=request.target, trace_id=trace_id,
                sources=(DataSource.BE, DataSource.FDS, expected_telemetry.source),
                prompt_version=EXTRACTION_PROMPT_VERSION,
            )
        except ValidationError:
            pass
        if metadata is None:
            raise ValueError("invalid extraction trace metadata")
        if request.user_request.text is None:
            return IntentExtractionResult(
                status="NOT_RUN", metadata=metadata, input_source=request.user_request.source,
                telemetry=expected_telemetry,
                reasons=(EvaluationReason(code="NOT_EXECUTED", source="FDS"),
                         EvaluationReason(code="INSUFFICIENT_DATA", source="FDS")),
                clarifications=("분석할 구매 요청 텍스트를 제공해야 합니다.",),
            ).validate_for_request(request)

        completion = None
        failure = None
        try:
            completion = await self._adapter.complete(build_extraction_prompt(request.user_request), budget=budget)
        except Exception:
            # Cancellation is a BaseException and propagates. Unexpected adapter
            # exceptions can retain raw input; return a fixed failure instead.
            failure = AnalysisFailure(kind="API_ERROR", api_error="UNKNOWN", retryable=False)

        telemetry = expected_telemetry
        response_model = None
        completed_at = datetime.now(timezone.utc)
        prompt_version = EXTRACTION_PROMPT_VERSION
        payload = None
        failure_source = DataSource.FDS
        uncertainty_source = DataSource.FDS
        clarifications = ()
        if isinstance(completion, StructuredCompletion):
            telemetry = completion.telemetry
            response_model = completion.response_model
            completed_at = completion.completed_at
            prompt_version = completion.prompt_version
            if (
                completion.stage != "extraction" or prompt_version != EXTRACTION_PROMPT_VERSION
                or telemetry.gateway != expected_telemetry.gateway
                or telemetry.requested_model != settings.openai_model
            ):
                failure = schema_failure("COMPLETION_CONTEXT", "INVALID_CONTEXT")
            elif completion.failure is not None:
                failure = completion.failure
                failure_source = telemetry.source
            elif budget.remaining_seconds <= 0:
                failure = AnalysisFailure(kind="TIMEOUT", retryable=False)
            elif isinstance(completion.payload, IntentExtractionOutput):
                payload = completion.payload
                clarifications = payload.ambiguities or ()
                if clarifications:
                    uncertainty_source = telemetry.source
                if not any((payload.items, payload.budget, payload.purpose,
                            payload.explicit_conditions, payload.preferences)):
                    # Valid JSON can still contain no usable purchase intent.
                    # Keep clarification needs without producing an empty intent.
                    payload = None
                    failure = AnalysisFailure(kind="INSUFFICIENT_INPUT", retryable=False)
                    clarifications = clarifications or ("구체적인 구매 요청을 확인해야 합니다.",)
                elif not payload.items and not clarifications:
                    clarifications = ("구매할 상품을 확인해야 합니다.",)
        elif failure is None:
            failure = schema_failure("COMPLETION_CONTEXT", "INVALID_CONTEXT")

        reasons = ()
        if failure is not None:
            reasons = (EvaluationReason(code=failure.reason_code, source=failure_source),)
        elif clarifications:
            reasons = (EvaluationReason(code="INTENT_UNCERTAIN", source=uncertainty_source),)
        result = IntentExtractionResult(
            status="FAILED" if failure else "SUCCESS", payload=payload, failure=failure,
            telemetry=telemetry, input_source=request.user_request.source, clarifications=clarifications,
            metadata=EvaluationMetadata(
                target=metadata.target, trace_id=metadata.trace_id,
                sources=(DataSource.BE, DataSource.FDS, telemetry.source),
                evaluated_at=completed_at, model_version=response_model, prompt_version=prompt_version,
            ),
            reasons=reasons,
        )
        return result.validate_for_request(request)
