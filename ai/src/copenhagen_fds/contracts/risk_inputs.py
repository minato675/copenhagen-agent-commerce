"""Bind already evaluated behavior and intent to a single point-in-time request."""

from typing import Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.analysis_results import IntentComparisonResult
from copenhagen_fds.contracts.inputs import FraudEvaluationRequest
from copenhagen_fds.contracts.personal_amount import AMOUNT_SCORE_VERSION
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION
from copenhagen_fds.contracts.personal_readiness import READINESS_VERSION
from copenhagen_fds.contracts.personal_time import TIME_PERIODS, TIME_SCORE_VERSION
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.results import EvaluationTarget, Timestamp
from copenhagen_fds.contracts.risk_policy import RiskPolicyConfig
from copenhagen_fds.contracts.values import Identifier, InputModel, Sha256


class RiskPolicyInput(InputModel):
    schema_version: Literal["risk-policy-input-v1"] = "risk-policy-input-v1"
    request: FraudEvaluationRequest
    scoring_input: PersonalizedScoringInput
    behavior: PersonalizedEvaluationResult | None
    intent: IntentComparisonResult
    policy: RiskPolicyConfig
    evaluated_at: Timestamp
    # Omission preserves the original point-in-time wire format and input hash.
    policy_evaluated_at: Timestamp | None = Field(default=None, exclude_if=lambda value: value is None)

    @model_validator(mode="after")
    def validate_binding(self) -> Self:
        supplied = self.scoring_input
        evaluation = supplied.cold.request.evaluation
        if self.request != evaluation.request:
            raise ValueError("policy and scoring input must preserve the exact purchase and order content")
        if self.evaluated_at != evaluation.evaluated_at:
            raise ValueError("behavior evidence is point-in-time and cannot be reused at another evaluation time")
        if evaluation.feature_schema_version != self.policy.feature_schema_version:
            raise ValueError("policy must support the supplied feature schema")
        config = supplied.config
        if config.score_scale_version != self.policy.score_scale_version or config.transition_policy_version != READINESS_VERSION:
            raise ValueError("policy requires supported personal score scale and transition versions")
        versions = {"amount_minor": AMOUNT_SCORE_VERSION,
                    **dict.fromkeys(FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION),
                    **dict.fromkeys(TIME_PERIODS, TIME_SCORE_VERSION)}
        if any(f.score_version != versions[f.feature_id] for f in config.features):
            raise ValueError("policy requires supported personal feature score versions")
        if self.behavior is not None:
            # Check exact provenance without rerunning scorers: transient failures are evidence.
            self.behavior.result.validate_for_input(supplied)
        self.intent.validate_for_request(self.request)
        metadata = self.intent.metadata
        if metadata.trace_id != supplied.cold.trace_id:
            raise ValueError("intent and behavior must share the evaluation trace")
        checked_at = self.policy_evaluated_at or self.evaluated_at
        if checked_at < self.evaluated_at:
            raise ValueError("policy time cannot precede the fixed behavior evaluation time")
        if metadata.evaluated_at is not None:
            age = (checked_at - metadata.evaluated_at).total_seconds()
            if age < 0 or age >= self.policy.intent_max_age_seconds:
                raise ValueError("intent evaluation must be prior or current and within the exclusive age limit")
        if metadata.valid_until is not None and metadata.valid_until <= checked_at:
            raise ValueError("expired intent evidence cannot be used for a current decision")
        return self


class RiskInputReference(InputModel):
    """Content hashes bind profiles, parameters and versions, not their authenticity."""

    schema_version: Literal["risk-policy-input-v1"] = "risk-policy-input-v1"
    target: EvaluationTarget
    trace_id: Identifier
    evaluated_at: Timestamp
    input_sha256: Sha256
    scoring_input_sha256: Sha256
    behavior_sha256: Sha256 | None
    intent_sha256: Sha256
    policy_evaluated_at: Timestamp | None = Field(default=None, exclude_if=lambda value: value is None)

    @model_validator(mode="after")
    def validate_time(self) -> Self:
        if self.policy_evaluated_at is not None and self.policy_evaluated_at < self.evaluated_at:
            raise ValueError("policy reference time cannot precede its fixed behavior cutoff")
        return self

    @classmethod
    def from_input(cls, supplied: RiskPolicyInput) -> Self:
        return cls(target=supplied.scoring_input.cold.request.evaluation.target,
                   trace_id=supplied.scoring_input.cold.trace_id, evaluated_at=supplied.evaluated_at,
                   input_sha256=personalization_hash(supplied),
                   scoring_input_sha256=personalization_hash(supplied.scoring_input),
                   behavior_sha256=None if supplied.behavior is None else personalization_hash(supplied.behavior),
                   intent_sha256=personalization_hash(supplied.intent), policy_evaluated_at=supplied.policy_evaluated_at)
