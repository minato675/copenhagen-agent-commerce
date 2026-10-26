"""Admission policy evidence and decisions, separate from raw learning eligibility."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.learning import LearningFeedback, LearningPurchaseReference, LearningStatus
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


Count = Annotated[int, Field(ge=0)]
Positive = Annotated[int, Field(gt=0)]
Finite = Annotated[float, Field(allow_inf_nan=False)]


class ProfilePolicyConfig(InputModel):
    version: Identifier = "admission-config-v1"
    max_admissions_per_user_day: Positive = 10
    min_reference_samples: Positive = 5
    deviation_ratio: Annotated[float, Field(gt=1, allow_inf_nan=False)] = 3.0
    amount_floor_minor: Positive = 1
    count_floor: Positive = 1
    hour_tolerance: Annotated[int, Field(ge=0, le=12)] = 2
    weekday_tolerance: Annotated[int, Field(ge=0, le=3)] = 0
    adaptation_window_days: Annotated[int, Field(gt=0, le=3650)] = 28
    adaptation_min_samples: Annotated[int, Field(ge=2)] = 5
    adaptation_min_days: Annotated[int, Field(ge=2)] = 3
    similarity_ratio: Annotated[float, Field(ge=1, allow_inf_nan=False)] = 1.5
    timezone: Literal["Asia/Seoul"] = "Asia/Seoul"

    @model_validator(mode="after")
    def validate_support(self) -> Self:
        if self.adaptation_min_days > min(self.adaptation_min_samples, self.adaptation_window_days):
            raise ValueError("adaptation support must fit the configured sample and day window")
        return self


class PolicyObservation(InputModel):
    sample: LearningSample
    feedback: LearningFeedback | None = None

    @model_validator(mode="after")
    def validate_known_evidence(self) -> Self:
        decision, feedback = self.sample.eligibility, self.feedback
        if feedback is None or feedback.available_at > decision.as_of:
            return self  # Future evidence is removed before policy evaluation and hashing.
        if feedback.target != decision.target or feedback.occurred_at < self.sample.purchase_occurred_at:
            raise ValueError("known review must match the complete purchase and its occurrence")
        if feedback.feedback_id != decision.feedback_id:
            raise ValueError("known review must be the review selected by the learning replay")
        expected = "SUSPECTED" if feedback.classification == "SUSPECTED" else (
            "UNCONFIRMED" if feedback.classification == "UNCONFIRMED" else
            feedback.classification if feedback.verification == "VERIFIED" else "UNVERIFIED_FEEDBACK"
        )
        if decision.reason != expected:
            raise ValueError("known review must agree with the raw learning decision")
        return self


class PolicyFeatureCheck(InputModel):
    feature_id: Identifier
    value: Finite
    recent_count: Count
    long_count: Count
    recent_center: Finite | None
    long_center: Finite | None
    recent_distance: Finite | None
    long_distance: Finite | None
    recent_match: bool | None
    long_match: bool | None
    abrupt: bool


PolicyReason = Literal[
    "RAW_UNCONFIRMED", "RAW_SUSPECTED", "RAW_UNVERIFIED_FEEDBACK", "RAW_NOT_OBSERVED",
    "RAW_FRAUD_CONFIRMED", "MISSING_REVIEW_EVIDENCE", "BOOTSTRAP_NORMAL", "REFERENCE_MATCH",
    "NORMAL_CHANGE_SUPPORTED", "ABRUPT_CHANGE", "DAILY_LIMIT",
]


class PolicyDecision(InputModel):
    target: LearningPurchaseReference
    sample_sha256: Sha256
    status: LearningStatus
    reason: PolicyReason
    decided_at: Timestamp
    admitted_at: Timestamp | None = None
    policy_version: Literal["confirmed-normal-admission-v1"] = "confirmed-normal-admission-v1"
    config_sha256: Sha256
    raw_status: LearningStatus
    raw_reason: Identifier
    feedback: LearningFeedback | None = None
    checks: Array[PolicyFeatureCheck] = ()
    reference_sha256: Sha256 | None = None
    recent_start: Timestamp | None = None
    long_start: Timestamp | None = None
    support_purchase_ids: Array[Identifier] = ()
    support_distinct_days: Count = 0
    day_admissions_before: Count = 0
    daily_limit_reached: bool = False

    @model_validator(mode="after")
    def validate_decision(self) -> Self:
        included_reasons = {"BOOTSTRAP_NORMAL", "REFERENCE_MATCH", "NORMAL_CHANGE_SUPPORTED"}
        expected = "INCLUDE" if self.reason in included_reasons else "EXCLUDE" if self.reason == "RAW_FRAUD_CONFIRMED" else "DEFER"
        if self.status != expected:
            raise ValueError("policy status must match its admission reason")
        if (self.status == "INCLUDE") != (self.admitted_at is not None):
            raise ValueError("only admitted purchases have an admission timestamp")
        if self.admitted_at is not None and self.admitted_at != self.decided_at:
            raise ValueError("admission time must be the successful decision time")
        if self.feedback is not None and (self.feedback.available_at > self.decided_at or self.feedback.target != self.target):
            raise ValueError("policy evidence must match the purchase and be available at the decision")
        if self.status == "INCLUDE" and (self.raw_status != "INCLUDE" or self.feedback is None or
                self.feedback.classification != "NORMAL_CONFIRMED" or self.feedback.verification != "VERIFIED"):
            raise ValueError("admission requires an explicitly verified normal review")
        if self.status == "EXCLUDE" and (self.feedback is None or self.feedback.classification != "FRAUD_CONFIRMED"
                                        or self.feedback.verification != "VERIFIED"):
            raise ValueError("exclusion requires an available verified fraud review")
        if list(self.support_purchase_ids) != sorted(set(self.support_purchase_ids)):
            raise ValueError("support purchases must be unique and ordered")
        return self


class PolicyEvaluation(InputModel):
    schema_version: Literal["profile-admission-evaluation-v1"] = "profile-admission-evaluation-v1"
    policy_version: Literal["confirmed-normal-admission-v1"] = "confirmed-normal-admission-v1"
    config: ProfilePolicyConfig
    config_sha256: Sha256
    input_sha256: Sha256
    as_of: Timestamp
    replay_from: Timestamp
    decisions: Array[PolicyDecision]

    @model_validator(mode="after")
    def validate_evaluation(self) -> Self:
        ids = [d.target.purchase_request_id for d in self.decisions]
        if ids != sorted(set(ids)) or self.replay_from >= self.as_of:
            raise ValueError("policy decisions require unique ordered purchases and an ordered replay range")
        if any(d.decided_at > self.as_of or d.config_sha256 != self.config_sha256 for d in self.decisions):
            raise ValueError("decisions must preserve the cutoff and policy configuration")
        return self
