"""Auditable per-feature support, separate from anomaly scores and mixtures."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.personalization_config import FractionValue, PersonalizationConfig, ReadinessThresholds, Seconds
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, ProfileAvailability, personalization_hash
from copenhagen_fds.contracts.personalization_results import FeatureWindowEvidence
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


READINESS_VERSION = "feature-readiness-min-v1"
Count = Annotated[int, Field(ge=0)]


class ReadinessMeasures(InputModel):
    valid_samples: Count | None
    considered_samples: Count | None
    observed_seconds: Seconds | None
    recency_seconds: Seconds | None
    available_fraction: FractionValue | None
    coverage_checked_samples: Count | None
    coverage_supported_samples: Count | None

    @model_validator(mode="after")
    def validate_counts(self) -> Self:
        if (self.valid_samples is None) != (self.considered_samples is None):
            raise ValueError("sample counts must share availability")
        if self.valid_samples is not None and self.valid_samples > self.considered_samples:
            raise ValueError("valid samples cannot exceed considered samples")
        if (self.coverage_checked_samples is None) != (self.coverage_supported_samples is None):
            raise ValueError("coverage counts must share availability")
        if self.coverage_checked_samples is not None and self.coverage_supported_samples > self.coverage_checked_samples:
            raise ValueError("supported coverage cannot exceed checked samples")
        return self


class ReadinessFactors(InputModel):
    samples: FractionValue | None
    observed_period: FractionValue | None
    freshness: FractionValue | None
    availability: FractionValue | None


class WindowReadiness(InputModel):
    window: Literal["RECENT", "LONG_TERM"]
    evidence: FeatureWindowEvidence | None
    measures: ReadinessMeasures
    factors: ReadinessFactors
    coverage_status: Literal["NOT_REQUIRED", "UNAVAILABLE", "COMPLETE", "INCOMPLETE"]
    personal_weight: FractionValue
    reasons: Annotated[Array[Identifier], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_evidence(self) -> Self:
        if len(set(self.reasons)) != len(self.reasons):
            raise ValueError("readiness reasons must be unique")
        factors = tuple(self.factors.model_dump().values())
        if self.personal_weight > 0 and (any(v is None for v in factors) or
                self.personal_weight != min(factors) or self.coverage_status not in {"NOT_REQUIRED", "COMPLETE"}):
            raise ValueError("positive personal support requires every axis and usable coverage")
        if self.evidence is None:
            if any(v is not None for v in self.measures.model_dump().values()) or any(v is not None for v in factors):
                raise ValueError("absent evidence must not fabricate zero measurements")
            if self.personal_weight != 0 or self.coverage_status != "UNAVAILABLE":
                raise ValueError("absent evidence cannot support personalization")
        else:
            dist = self.evidence.distribution
            if self.evidence.window != self.window or self.measures.valid_samples != dist.observation.valid_sample_count:
                raise ValueError("readiness must preserve its statistical window and sample count")
            if self.measures.considered_samples != self.measures.valid_samples + dist.unavailable_count:
                raise ValueError("availability denominator must include missing feature observations")
        return self


class FeatureReadiness(InputModel):
    feature_id: Identifier
    thresholds: ReadinessThresholds
    effective_minimum_samples: Annotated[int, Field(ge=2)]
    effective_full_samples: Annotated[int, Field(ge=3)]
    windows: Annotated[Array[WindowReadiness], Field(min_length=2, max_length=2)]

    @model_validator(mode="after")
    def validate_feature(self) -> Self:
        if self.feature_id not in FEATURE_SPECS or self.effective_minimum_samples >= self.effective_full_samples:
            raise ValueError("readiness requires a supported feature and ordered effective sample bounds")
        if [w.window for w in self.windows] != ["RECENT", "LONG_TERM"]:
            raise ValueError("readiness requires both windows in order")
        if any(w.evidence is not None and w.evidence.distribution.feature_id != self.feature_id for w in self.windows):
            raise ValueError("readiness evidence cannot switch feature")
        return self


class PersonalReadinessResult(InputModel):
    schema_version: Literal["personal-readiness-result-v1"] = "personal-readiness-result-v1"
    policy_version: Literal["feature-readiness-min-v1"] = READINESS_VERSION
    input_sha256: Sha256
    config: PersonalizationConfig
    config_sha256: Sha256
    profile: ProfileAvailability
    features: Annotated[Array[FeatureReadiness], Field(min_length=1, max_length=9)]

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if self.config.transition_policy_version != self.policy_version or self.config_sha256 != personalization_hash(self.config):
            raise ValueError("readiness configuration must match supported policy and hash")
        if self.profile.scope != "PERSONAL":
            raise ValueError("readiness requires personal profile availability")
        ids = [f.feature_id for f in self.features]
        if ids != [name for name in FEATURE_SPECS if name in ids]:
            raise ValueError("readiness features must be unique and in schema order")
        settings = {f.feature_id: f for f in self.config.features}
        for feature in self.features:
            if feature.feature_id not in settings or feature.thresholds != settings[feature.feature_id].readiness:
                raise ValueError("readiness must retain the actual feature thresholds")
            for window in feature.windows:
                if (window.evidence is None) != (self.profile.status == "NOT_SUPPLIED"):
                    raise ValueError("supplied profile must retain evidence")
                if self.profile.status != "AVAILABLE" and window.personal_weight != 0:
                    raise ValueError("unavailable profile cannot support positive personal weight")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        from copenhagen_fds.personal_readiness import evaluate_personal_readiness

        if self != evaluate_personal_readiness(supplied):
            raise ValueError("readiness must reproduce from the exact input and evaluation time")
        return self
