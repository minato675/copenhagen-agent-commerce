"""Component result contracts, without API calls, scoring or decision policy."""

from datetime import datetime
from typing import Annotated, Any, Literal, Self

from pydantic import AwareDatetime, BeforeValidator, Field, model_validator

from copenhagen_fds.contracts.inputs import SnapshotReference
from copenhagen_fds.contracts.statuses import (
    DataSource,
    EvaluationStatus,
    IntentMatch,
    ReasonCode,
    RiskLevel,
)
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, enum_parser


def _timestamp(value: Any) -> Any:
    # Parse explicit ISO text in dicts as well as JSON; never accept epoch numbers.
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value)
        except ValueError:
            raise ValueError("must be an ISO datetime with a timezone") from None
    return value


Timestamp = Annotated[AwareDatetime, BeforeValidator(_timestamp)]
StatusValue = Annotated[EvaluationStatus, BeforeValidator(enum_parser(EvaluationStatus))]
MatchValue = Annotated[IntentMatch, BeforeValidator(enum_parser(IntentMatch))]
RiskValue = Annotated[RiskLevel, BeforeValidator(enum_parser(RiskLevel))]
SourceValue = Annotated[DataSource, BeforeValidator(enum_parser(DataSource))]
ReasonValue = Annotated[ReasonCode, BeforeValidator(enum_parser(ReasonCode))]


class EvaluationTarget(InputModel):
    evaluation_id: Identifier
    user_id: Identifier
    purchase_request_id: Identifier
    order: SnapshotReference
    intent_version: Identifier | None


class EvaluationMetadata(InputModel):
    target: EvaluationTarget
    trace_id: Identifier
    sources: Annotated[Array[SourceValue], Field(min_length=1)]
    evaluated_at: Timestamp | None = None
    valid_until: Timestamp | None = None
    model_version: Identifier | None = None
    prompt_version: Identifier | None = None
    policy_version: Identifier | None = None

    @model_validator(mode="after")
    def validate_validity_window(self) -> Self:
        if self.valid_until is not None:
            if self.evaluated_at is None or self.valid_until <= self.evaluated_at:
                raise ValueError("valid_until must be later than evaluated_at")
        return self


class EvaluationReason(InputModel):
    code: ReasonValue
    source: SourceValue
    reference_id: Identifier | None = None


class ComponentResult(InputModel):
    status: StatusValue
    metadata: EvaluationMetadata
    reasons: Array[EvaluationReason]

    @model_validator(mode="after")
    def validate_execution(self) -> Self:
        not_run = self.status == EvaluationStatus.NOT_RUN
        if not_run != (self.metadata.evaluated_at is None):
            raise ValueError("evaluated_at is required exactly when evaluation has run")
        if self.status != EvaluationStatus.SUCCESS:
            if not self.reasons:
                raise ValueError("unsuccessful evaluation must include a reason")
            if self.metadata.valid_until is not None:
                raise ValueError("unsuccessful evaluation cannot have a validity window")
        codes = {reason.code for reason in self.reasons}
        failures = {ReasonCode.EVALUATION_FAILED, ReasonCode.TIMEOUT, ReasonCode.INVALID_RESPONSE}
        if self.status == EvaluationStatus.FAILED and not codes.intersection(failures):
            raise ValueError("FAILED evaluation requires a failure reason code")
        if self.status == EvaluationStatus.NOT_RUN and ReasonCode.NOT_EXECUTED not in codes:
            raise ValueError("NOT_RUN evaluation requires NOT_EXECUTED")
        if self.status == EvaluationStatus.SUCCESS and codes.intersection(failures | {ReasonCode.NOT_EXECUTED}):
            raise ValueError("SUCCESS evaluation cannot contain execution failure reasons")
        if self.status == EvaluationStatus.FAILED and ReasonCode.NOT_EXECUTED in codes:
            raise ValueError("FAILED evaluation cannot also be NOT_EXECUTED")
        if self.status == EvaluationStatus.NOT_RUN and codes.intersection(failures):
            raise ValueError("NOT_RUN evaluation cannot contain execution failure reasons")
        return self

    def _validate_payload(self, value: Any) -> None:
        if (self.status == EvaluationStatus.SUCCESS) != (value is not None):
            raise ValueError("result payload is required exactly for SUCCESS")


class IntentAnalysisResult(ComponentResult):
    comparison: MatchValue | None = None

    @model_validator(mode="after")
    def validate_comparison(self) -> Self:
        self._validate_payload(self.comparison)
        if self.status == EvaluationStatus.SUCCESS:
            if self.metadata.model_version is None or self.metadata.prompt_version is None:
                raise ValueError("intent success requires model and prompt versions")
            if self.comparison == IntentMatch.UNKNOWN and not self.reasons:
                raise ValueError("unknown comparison must include a reason")
        return self


class SiftScore(InputModel):
    """Raw payment_abuse API score, not a percentage or calibrated probability."""

    value: Annotated[float, Field(ge=0.0, le=1.0, allow_inf_nan=False)]
    unit: Literal["SIFT_PAYMENT_ABUSE_0_1"]


class SiftFraudResult(ComponentResult):
    score: SiftScore | None = None

    @model_validator(mode="after")
    def validate_score(self) -> Self:
        self._validate_payload(self.score)
        return self


class RuleResult(ComponentResult):
    rule_id: Identifier
    risk_level: RiskValue | None = None

    @model_validator(mode="after")
    def validate_risk(self) -> Self:
        self._validate_payload(self.risk_level)
        if self.status == EvaluationStatus.SUCCESS:
            if self.metadata.policy_version is None:
                raise ValueError("rule success requires a policy version")
            if self.risk_level == RiskLevel.UNKNOWN and not self.reasons:
                raise ValueError("unknown rule risk must include a reason")
        return self
