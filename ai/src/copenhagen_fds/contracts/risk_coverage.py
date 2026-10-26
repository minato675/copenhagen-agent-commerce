"""Policy-wide score availability, including features omitted from a request."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import CoverageValue, FeatureScoreResult
from copenhagen_fds.contracts.features import RawFeatureValue
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput
from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES, GroupId, RiskPolicyConfig
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


class RiskFeatureAvailability(InputModel):
    group_id: GroupId
    required: bool
    requested: bool
    source: Literal["RESULT", "NOT_REQUESTED", "BEHAVIOR_NOT_RUN"]
    raw: RawFeatureValue | None
    feature: FeatureScoreResult

    @model_validator(mode="after")
    def validate_source(self) -> Self:
        name = self.feature.feature_id
        if name not in GROUP_FEATURES[self.group_id]:
            raise ValueError("availability must preserve the configured feature group")
        if self.requested != (self.raw is not None) or self.requested != (self.source != "NOT_REQUESTED"):
            raise ValueError("requested features require raw evidence; unrequested features have none")
        if self.raw is not None and self.raw.feature_id != name:
            raise ValueError("raw evidence and final score must describe the same feature")
        if self.source != "RESULT" and (self.feature.status != "NOT_RUN" or self.feature.bases):
            raise ValueError("missing result cannot claim an executed feature or score basis")
        if self.source == "RESULT" and self.feature.status == "NOT_RUN":
            raise ValueError("evaluated personal features cannot claim NOT_RUN")
        return self


def coverage_fields(features: tuple[RiskFeatureAvailability, ...]) -> dict:
    """Deterministic availability only: zero is available, missing stays missing."""
    available = {row.feature.feature_id for row in features if row.feature.score is not None}
    required_missing = tuple(row.feature.feature_id for row in features
                             if row.required and row.feature.feature_id not in available)
    optional_missing = tuple(row.feature.feature_id for row in features
                             if not row.required and row.feature.feature_id not in available)
    complete = len(available) == len(features)
    return dict(
        evaluation_scope="FULL" if not required_missing else "PARTIAL" if available else "UNAVAILABLE",
        score_coverage="COMPLETE" if complete else "PARTIAL" if available else "UNAVAILABLE",
        required_unavailable=required_missing, optional_unavailable=optional_missing,
        unrequested_features=tuple(row.feature.feature_id for row in features if not row.requested),
        can_aggregate=complete,
    )


class RiskCoverageResult(InputModel):
    schema_version: Literal["risk-coverage-result-v1"] = "risk-coverage-result-v1"
    input_reference: RiskInputReference
    policy: RiskPolicyConfig
    policy_sha256: Sha256
    features: Annotated[Array[RiskFeatureAvailability], Field(min_length=9, max_length=9)]
    evaluation_scope: Literal["FULL", "PARTIAL", "UNAVAILABLE"]
    score_coverage: CoverageValue
    required_unavailable: Array[Identifier]
    optional_unavailable: Array[Identifier]
    unrequested_features: Array[Identifier]
    can_aggregate: bool
    # Aggregation belongs to the next step, even when all scores are available.
    behavior_score: None = None

    @model_validator(mode="after")
    def validate_coverage(self) -> Self:
        if self.policy_sha256 != personalization_hash(self.policy):
            raise ValueError("coverage must retain its exact policy configuration hash")
        configured = [(group.group_id, feature.feature_id, feature.required)
                      for group in self.policy.groups for feature in group.features]
        if [(row.group_id, row.feature.feature_id, row.required) for row in self.features] != configured:
            raise ValueError("coverage must retain every configured feature and requirement in order")
        for field, expected in coverage_fields(self.features).items():
            if getattr(self, field) != expected:
                raise ValueError("coverage state must preserve available, missing and unrequested features")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        from copenhagen_fds.risk_coverage import evaluate_risk_coverage

        if self != evaluate_risk_coverage(supplied):
            raise ValueError("coverage must reproduce from the exact request, result and policy")
        return self
