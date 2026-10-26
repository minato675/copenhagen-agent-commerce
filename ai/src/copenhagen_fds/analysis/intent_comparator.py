"""Semantic comparison, with caller-bound results and validated item references."""

from datetime import datetime, timezone

from pydantic import ValidationError

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION, build_comparison_prompt
from copenhagen_fds.analysis.diagnostics import schema_failure
from copenhagen_fds.analysis.intent_extractor import CompletionClient
from copenhagen_fds.analysis.transport_models import StructuredCompletion
from copenhagen_fds.contracts import (
    AnalysisFailure, AnalysisTelemetry, DataSource, EvaluationMetadata, EvaluationReason,
    EvaluationTarget, FraudEvaluationRequest, IntentComparisonOutput, IntentComparisonResult,
)
from copenhagen_fds.contracts.analysis_inputs import evaluation_target
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.settings import EvaluationMode


def _comparison_context(
    settings: OpenAISettings, target: EvaluationTarget, trace_id: str,
) -> tuple[EvaluationMetadata, AnalysisTelemetry]:
    telemetry = AnalysisTelemetry(
        gateway="MOCK" if settings.mode == EvaluationMode.MOCK else "GMS",
        requested_model=settings.openai_model,
    )
    metadata = None
    try:
        metadata = EvaluationMetadata(
            target=target, trace_id=trace_id, prompt_version=COMPARISON_PROMPT_VERSION,
            sources=(DataSource.BE, DataSource.FDS, telemetry.source),
        )
    except ValidationError:
        pass
    if metadata is None:
        raise ValueError("invalid comparison trace metadata")
    return metadata, telemetry


def _comparison_without_call(
    settings: OpenAISettings, target: EvaluationTarget, trace_id: str,
    *, failure: AnalysisFailure | None = None,
) -> IntentComparisonResult:
    metadata, telemetry = _comparison_context(settings, target, trace_id)
    if failure is not None:
        metadata = EvaluationMetadata(
            **{**metadata.model_dump(), "evaluated_at": datetime.now(timezone.utc)},
        )
    return IntentComparisonResult(
        status="FAILED" if failure else "NOT_RUN", metadata=metadata, telemetry=telemetry,
        failure=failure,
        reasons=(EvaluationReason(code=failure.reason_code if failure else "NOT_EXECUTED", source="FDS"),),
    )


class IntentComparator:
    def __init__(self, adapter: CompletionClient):
        self._adapter = adapter

    async def compare(
        self, request: FraudEvaluationRequest, *, trace_id: str, budget: AnalysisBudget,
        clarifications: tuple[str, ...] = (),
    ) -> IntentComparisonResult:
        settings = self._adapter.settings
        if budget.total_seconds > settings.evaluation_timeout_seconds:
            raise ValueError("analysis budget exceeds the configured total timeout")
        metadata, expected = _comparison_context(settings, evaluation_target(request), trace_id)
        prompt = build_comparison_prompt(request, clarifications=clarifications)
        if budget.remaining_seconds <= 0:
            return _comparison_without_call(
                settings, metadata.target, trace_id, failure=AnalysisFailure(kind="TIMEOUT", retryable=False),
            ).validate_for_request(request)
        completion = None
        failure = None
        try:
            completion = await self._adapter.complete(prompt, budget=budget)
        except Exception:
            # CancelledError propagates; provider exceptions never leave this boundary.
            failure = AnalysisFailure(kind="API_ERROR", api_error="UNKNOWN", retryable=False)
        telemetry = expected
        completed_at = datetime.now(timezone.utc)
        response_model = None
        prompt_version = COMPARISON_PROMPT_VERSION
        failure_source = DataSource.FDS
        payload = None
        if isinstance(completion, StructuredCompletion):
            telemetry = completion.telemetry
            completed_at = completion.completed_at
            response_model = completion.response_model
            prompt_version = completion.prompt_version
            if (
                completion.stage != "comparison" or prompt_version != COMPARISON_PROMPT_VERSION
                or telemetry.gateway != expected.gateway or telemetry.requested_model != settings.openai_model
            ):
                failure = schema_failure("COMPLETION_CONTEXT", "INVALID_CONTEXT")
            elif completion.failure is not None:
                failure = completion.failure
                failure_source = telemetry.source
            elif budget.remaining_seconds <= 0:
                failure = AnalysisFailure(kind="TIMEOUT", retryable=False)
            elif isinstance(completion.payload, IntentComparisonOutput):
                candidate = completion.payload
                try:
                    candidate.validate_for_request(request)
                except ValueError:
                    failure = schema_failure("ITEM_REFERENCE", "UNKNOWN_REFERENCE")
                if failure is None:
                    if any(f.code == "INTENT_MISMATCH" and f.dimension in {"QUANTITY", "BUDGET"}
                           for f in candidate.findings):
                        failure = schema_failure("SEMANTIC_SCOPE", "OUT_OF_SCOPE")
                    elif candidate.comparison == "MATCH" and (
                        not request.intent.items or request.intent.ambiguities or clarifications
                    ):
                        failure = schema_failure("UNRESOLVED_INPUT", "UNRESOLVED_INPUT")
                    else:
                        payload = candidate
            else:
                failure = schema_failure("COMPLETION_CONTEXT", "INVALID_CONTEXT")
        elif failure is None:
            failure = schema_failure("COMPLETION_CONTEXT", "INVALID_CONTEXT")
        reasons = (
            (EvaluationReason(code=failure.reason_code, source=failure_source),) if failure else
            tuple(EvaluationReason(code=f.code, source=telemetry.source) for f in payload.findings)
        )
        return IntentComparisonResult(
            status="FAILED" if failure else "SUCCESS", payload=payload, failure=failure,
            telemetry=telemetry, reasons=reasons,
            metadata=EvaluationMetadata(
                target=metadata.target, trace_id=metadata.trace_id,
                sources=(DataSource.BE, DataSource.FDS, telemetry.source),
                evaluated_at=completed_at, model_version=response_model, prompt_version=prompt_version,
            ),
        ).validate_for_request(request)
