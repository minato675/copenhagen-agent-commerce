"""Versioned personalization settings; thresholds are supplied policy, not learned probabilities."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.values import Array, Identifier, InputModel


PositiveCount = Annotated[int, Field(gt=0)]
FractionValue = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]
Seconds = Annotated[float, Field(ge=0, allow_inf_nan=False)]


class ReadinessThresholds(InputModel):
    """Feature readiness bounds, with no operational defaults."""

    minimum_samples: PositiveCount
    full_samples: PositiveCount
    minimum_observed_seconds: Seconds
    full_observed_seconds: Seconds
    fresh_for_seconds: Seconds
    stale_after_seconds: Annotated[float, Field(gt=0, allow_inf_nan=False)]
    minimum_available_fraction: FractionValue
    full_available_fraction: FractionValue

    @model_validator(mode="after")
    def validate_bounds(self) -> Self:
        pairs = (
            (self.minimum_samples, self.full_samples),
            (self.minimum_observed_seconds, self.full_observed_seconds),
            (self.fresh_for_seconds, self.stale_after_seconds),
            (self.minimum_available_fraction, self.full_available_fraction),
        )
        if any(lower >= upper for lower, upper in pairs):
            raise ValueError("readiness bounds must leave a nonempty transition interval")
        return self


class PersonalFeatureConfig(InputModel):
    feature_id: Identifier
    score_version: Identifier
    readiness: ReadinessThresholds

    @model_validator(mode="after")
    def validate_feature(self) -> Self:
        if self.feature_id not in FEATURE_SPECS:
            raise ValueError("personalization requires a supported raw feature")
        return self


class PersonalizationConfig(InputModel):
    schema_version: Literal["personalization-config-v1"] = "personalization-config-v1"
    version: Identifier
    score_scale_version: Identifier
    transition_policy_version: Identifier
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    features: Annotated[Array[PersonalFeatureConfig], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_features(self) -> Self:
        ids = [item.feature_id for item in self.features]
        if ids != [name for name in FEATURE_SPECS if name in ids]:
            raise ValueError("feature settings must be unique and in the shared schema order")
        return self
