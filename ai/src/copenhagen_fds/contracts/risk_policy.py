"""Explicit, unvalidated decision settings; no weights are learned or applied here."""

import math
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.values import Array, Identifier, InputModel


GroupId = Literal["AMOUNT", "FREQUENCY", "TIME"]
GROUP_FEATURES = {
    "AMOUNT": ("amount_minor",),
    "FREQUENCY": tuple(name for name in FEATURE_SPECS if "count" in name),
    "TIME": ("kst_hour", "kst_day_of_week"),
}
ScoreValue = Annotated[float, Field(ge=0, le=100, allow_inf_nan=False)]
PositiveScore = Annotated[float, Field(gt=0, le=100, allow_inf_nan=False)]
Weight = Annotated[float, Field(gt=0, le=1, allow_inf_nan=False)]


class RiskFeatureSetting(InputModel):
    feature_id: Identifier
    required: bool


class RiskGroupSetting(InputModel):
    group_id: GroupId
    weight: Weight
    aggregation: Literal["MAX"] = "MAX"
    features: Annotated[Array[RiskFeatureSetting], Field(min_length=1, max_length=6)]

    @model_validator(mode="after")
    def validate_members(self) -> Self:
        if tuple(f.feature_id for f in self.features) != GROUP_FEATURES[self.group_id]:
            raise ValueError("group features must preserve their unique canonical mapping and order")
        return self


class RiskThresholds(InputModel):
    medium_from: PositiveScore
    high_from: PositiveScore

    @model_validator(mode="after")
    def validate_order(self) -> Self:
        if self.medium_from >= self.high_from:
            raise ValueError("risk thresholds require 0 < medium_from < high_from <= 100")
        return self


class GroupThreshold(InputModel):
    group_id: GroupId
    minimum_score: PositiveScore


class RiskFloorRule(InputModel):
    """One condition is a strong signal; multiple conditions mean all must hold."""

    rule_id: Identifier
    conditions: Annotated[Array[GroupThreshold], Field(min_length=1, max_length=3)]
    minimum_risk: Literal["MEDIUM", "HIGH"]

    @model_validator(mode="after")
    def validate_conditions(self) -> Self:
        ids = [condition.group_id for condition in self.conditions]
        if ids != [name for name in GROUP_FEATURES if name in ids]:
            raise ValueError("rule conditions must use distinct groups in canonical order")
        return self


class RiskPolicyConfig(InputModel):
    schema_version: Literal["risk-policy-config-v1"] = "risk-policy-config-v1"
    version: Identifier
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    feature_schema_version: Literal["cold-features-v1"] = "cold-features-v1"
    score_scale_version: Literal["behavior-deviation-blend-v1"] = "behavior-deviation-blend-v1"
    groups: Annotated[Array[RiskGroupSetting], Field(min_length=3, max_length=3)]
    thresholds: RiskThresholds
    intent_required: bool
    intent_max_age_seconds: Annotated[int, Field(gt=0, le=86400)]
    floor_rules: Array[RiskFloorRule] = ()
    missing_score_policy: Literal["NO_TOTAL_WITH_MISSING_SCORE"] = "NO_TOTAL_WITH_MISSING_SCORE"
    failure_policy: Literal["PRESERVE_FAILURE"] = "PRESERVE_FAILURE"
    stage_policy: Literal["USE_FINAL_FEATURE_SCORE"] = "USE_FINAL_FEATURE_SCORE"
    intent_policy: Literal["SEPARATE_SIGNAL_NO_RISK_DISCOUNT"] = "SEPARATE_SIGNAL_NO_RISK_DISCOUNT"

    @model_validator(mode="after")
    def validate_policy(self) -> Self:
        if [g.group_id for g in self.groups] != list(GROUP_FEATURES):
            raise ValueError("policy requires each canonical group exactly once in order")
        if not math.isclose(math.fsum(g.weight for g in self.groups), 1.0, rel_tol=0, abs_tol=1e-12):
            raise ValueError("group weights must sum to one; automatic normalization is not allowed")
        if not any(f.required for g in self.groups for f in g.features):
            raise ValueError("policy must declare at least one required behavior feature")
        if len({r.rule_id for r in self.floor_rules}) != len(self.floor_rules):
            raise ValueError("risk floor rule IDs must be unique")
        return self
