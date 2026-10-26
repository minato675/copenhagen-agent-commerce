"""Locally attached execution metadata and lossless analysis result envelopes."""

from enum import StrEnum
from typing import Annotated, Literal, Self

from pydantic import BeforeValidator, Field, model_validator

from copenhagen_fds.contracts.analysis_inputs import IntentAnalysisRequest, evaluation_target
from copenhagen_fds.contracts.analysis_diagnostics import ValidationDiagnostic
from copenhagen_fds.contracts.analysis_payloads import IntentComparisonOutput, IntentExtractionOutput
from copenhagen_fds.contracts.inputs import FraudEvaluationRequest, IntentSource, PurchaseIntent
from copenhagen_fds.contracts.results import ComponentResult, EvaluationReason, IntentAnalysisResult
from copenhagen_fds.contracts.statuses import DataSource, EvaluationStatus, ReasonCode
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Text, enum_parser


class AnalysisFailureKind(StrEnum):
    API_ERROR = "API_ERROR"
    TIMEOUT = "TIMEOUT"
    REFUSAL = "REFUSAL"
    INCOMPLETE_RESPONSE = "INCOMPLETE_RESPONSE"
    SCHEMA_ERROR = "SCHEMA_ERROR"
    INSUFFICIENT_INPUT = "INSUFFICIENT_INPUT"
    INVALID_INPUT = "INVALID_INPUT"


class AnalysisFailure(InputModel):
    kind: Annotated[AnalysisFailureKind, BeforeValidator(enum_parser(AnalysisFailureKind))]
    http_status: Annotated[int, Field(ge=100, le=599)] | None = None
    retryable: bool | None = None
    api_error: Literal[
        "AUTHENTICATION", "PERMISSION", "RATE_LIMIT", "SERVER", "REQUEST", "CONNECTION", "UNKNOWN",
    ] | None = None
    diagnostics: Annotated[Array[ValidationDiagnostic], Field(max_length=8)] = ()

    @model_validator(mode="after")
    def validate_api_error(self) -> Self:
        if self.api_error is not None and self.kind != AnalysisFailureKind.API_ERROR:
            raise ValueError("API error category requires API_ERROR failure")
        if self.diagnostics and self.kind != AnalysisFailureKind.SCHEMA_ERROR:
            raise ValueError("validation diagnostics require SCHEMA_ERROR failure")
        return self

    @property
    def reason_code(self) -> ReasonCode:
        if self.kind == AnalysisFailureKind.TIMEOUT:
            return ReasonCode.TIMEOUT
        if self.kind in (
            AnalysisFailureKind.API_ERROR, AnalysisFailureKind.INSUFFICIENT_INPUT, AnalysisFailureKind.INVALID_INPUT,
        ):
            return ReasonCode.EVALUATION_FAILED
        return ReasonCode.INVALID_RESPONSE


TokenCount = Annotated[int, Field(ge=0)]


class TokenUsage(InputModel):
    prompt_tokens: TokenCount | None = None
    completion_tokens: TokenCount | None = None
    total_tokens: TokenCount | None = None

    @model_validator(mode="after")
    def validate_counts(self) -> Self:
        parts = (self.prompt_tokens, self.completion_tokens)
        if self.total_tokens is not None:
            known = sum(value for value in parts if value is not None)
            if known > self.total_tokens or (all(v is not None for v in parts) and known != self.total_tokens):
                raise ValueError("reported token counts are inconsistent")
        return self


class AnalysisTelemetry(InputModel):
    gateway: Literal["GMS", "MOCK"]
    requested_model: Text | None = None
    response_id: Identifier | None = None
    request_id: Identifier | None = None
    duration_ms: Annotated[float, Field(ge=0, allow_inf_nan=False)] | None = None
    usage: TokenUsage | None = None

    @property
    def source(self) -> DataSource:
        return DataSource.MOCK if self.gateway == "MOCK" else DataSource.OPENAI


class AnalysisResult(ComponentResult):
    telemetry: AnalysisTelemetry
    failure: AnalysisFailure | None = None

    @model_validator(mode="after")
    def validate_execution_details(self) -> Self:
        if self.telemetry.source not in self.metadata.sources:
            raise ValueError("analysis source must match its gateway")
        other = DataSource.OPENAI if self.telemetry.source == DataSource.MOCK else DataSource.MOCK
        if other in self.metadata.sources:
            raise ValueError("one analysis call cannot be both mocked and real")
        if any(reason.source == other for reason in self.reasons):
            raise ValueError("analysis reasons cannot claim a different execution source")
        if self.status == EvaluationStatus.FAILED:
            if self.failure is None or {r.code for r in self.reasons} != {self.failure.reason_code}:
                raise ValueError("failed analysis requires its matching failure classification")
        elif self.failure is not None:
            raise ValueError("failure classification is only valid for FAILED analysis")
        if self.status == EvaluationStatus.SUCCESS:
            if self.metadata.model_version is None or self.metadata.prompt_version is None:
                raise ValueError("successful analysis requires model and prompt versions")
        if self.status == EvaluationStatus.NOT_RUN:
            if any(value is not None for value in (
                self.telemetry.response_id, self.telemetry.request_id,
                self.telemetry.duration_ms, self.telemetry.usage,
            )):
                raise ValueError("NOT_RUN analysis cannot contain call measurements")
        return self


class IntentExtractionResult(AnalysisResult):
    payload: IntentExtractionOutput | None = None
    input_source: IntentSource | None = None
    clarifications: Array[Text] = Field(default=(), repr=False)

    @model_validator(mode="after")
    def validate_extraction(self) -> Self:
        self._validate_payload(self.payload)
        return self

    def validate_for_request(self, request: IntentAnalysisRequest) -> Self:
        if self.metadata.target != request.target:
            raise ValueError("extraction target must match the supplied analysis request")
        if self.input_source is not None and self.input_source != request.user_request.source:
            raise ValueError("extraction source must match the supplied request source")
        if self.status == EvaluationStatus.SUCCESS and request.user_request.text is None:
            raise ValueError("successful extraction requires supplied request text")
        return self

    def to_purchase_intent(self, request: IntentAnalysisRequest) -> PurchaseIntent:
        self.validate_for_request(request)
        if self.payload is None:
            raise ValueError("only successful extraction can produce a purchase intent")
        return request.user_request.with_extracted_intent(self.payload)

    def to_evaluation_request(self, request: IntentAnalysisRequest) -> FraudEvaluationRequest:
        self.validate_for_request(request)
        if self.payload is None:
            raise ValueError("only successful extraction can produce an evaluation request")
        return request.with_extracted_intent(self.payload)


class IntentComparisonResult(AnalysisResult):
    payload: IntentComparisonOutput | None = None

    @model_validator(mode="after")
    def validate_comparison(self) -> Self:
        self._validate_payload(self.payload)
        if self.payload is not None:
            expected = tuple(
                EvaluationReason(code=ReasonCode(finding.code), source=self.telemetry.source)
                for finding in self.payload.findings
            )
            if self.reasons != expected:
                raise ValueError("comparison reasons must preserve its structured findings")
        # Reuse the public result's execution/semantic constraints as well.
        IntentAnalysisResult(
            status=self.status, metadata=self.metadata, reasons=self.reasons,
            comparison=None if self.payload is None else self.payload.comparison,
        )
        return self

    def validate_for_request(self, request: FraudEvaluationRequest) -> Self:
        if self.metadata.target != evaluation_target(request):
            raise ValueError("comparison target must match the supplied evaluation request")
        if self.payload is not None:
            self.payload.validate_for_request(request)
        return self

    def to_common_result(self, request: FraudEvaluationRequest) -> IntentAnalysisResult:
        """The common projection omits details; retain this envelope for full fidelity."""
        self.validate_for_request(request)
        return IntentAnalysisResult(
            status=self.status, metadata=self.metadata, reasons=self.reasons,
            comparison=None if self.payload is None else self.payload.comparison,
        )
