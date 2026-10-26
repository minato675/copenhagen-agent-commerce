"""Separate purchase/attempt count candidates, with exact count units and windows."""

from fractions import Fraction
from math import gcd
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.features import FEATURE_SPECS, RawFeatureValue
from copenhagen_fds.contracts.personalization_inputs import (
    PersonalizedScoringInput, ProfileAvailability, personalization_hash,
)
from copenhagen_fds.contracts.personalization_results import FeatureWindowEvidence
from copenhagen_fds.contracts.values import Array, InputModel, Sha256


FREQUENCY_FEATURES = tuple(name for name, (unit, _) in FEATURE_SPECS.items()
                           if unit in {"PURCHASE_COUNT", "ATTEMPT_COUNT"})
FREQUENCY_SCORE_VERSION = "personal-frequency-upper-linear-v1"


class PersonalFrequencyPolicy(InputModel):
    version: Literal["personal-frequency-upper-linear-v1"] = FREQUENCY_SCORE_VERSION
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    spread_multiplier: Annotated[float, Field(gt=0, allow_inf_nan=False)] = 3.0
    relative_scale_floor: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)] = 0.1
    minimum_scale_count: Annotated[int, Field(ge=1)] = 1


class ExactCount(InputModel):
    """An exact rational number of counts; no conversion to a per-time rate."""

    numerator: Annotated[int, Field(ge=0)]
    denominator: Annotated[int, Field(gt=0)]

    @model_validator(mode="after")
    def validate_reduced(self) -> Self:
        if gcd(self.numerator, self.denominator) != 1:
            raise ValueError("exact count must use a reduced fraction")
        return self

    @classmethod
    def from_fraction(cls, value: Fraction) -> Self:
        return cls(numerator=value.numerator, denominator=value.denominator)

    def as_fraction(self) -> Fraction:
        return Fraction(self.numerator, self.denominator)


class FrequencyCalculation(InputModel):
    median_count: ExactCount
    q95_count: ExactCount
    iqr_count: ExactCount
    mad_count: ExactCount
    saturation_distance_count: ExactCount
    zero_spread: bool

    @model_validator(mode="after")
    def validate_scale(self) -> Self:
        if self.saturation_distance_count.numerator == 0:
            raise ValueError("frequency saturation distance must be positive")
        if self.q95_count.as_fraction() < self.median_count.as_fraction():
            raise ValueError("upper quantile must not be below median")
        return self


FrequencyDiagnostic = Literal[
    "SCORED", "INPUT_NOT_COLLECTED", "INPUT_INSUFFICIENT_HISTORY", "INPUT_NOT_APPLICABLE",
    "PROFILE_NOT_SUPPLIED", "PROFILE_EXPIRED", "EMPTY", "INSUFFICIENT_SAMPLES", "CALCULATION_FAILED",
]
UNSCORED_FREQUENCY = {
    "INPUT_NOT_COLLECTED": ("NOT_COLLECTED", "MISSING_INPUT"),
    "INPUT_INSUFFICIENT_HISTORY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "INPUT_NOT_APPLICABLE": ("NOT_APPLICABLE", "NOT_APPLICABLE"),
    "PROFILE_NOT_SUPPLIED": ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE"),
    "PROFILE_EXPIRED": ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE"),
    "EMPTY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "INSUFFICIENT_SAMPLES": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "CALCULATION_FAILED": ("FAILED", "CALCULATION_FAILED"),
}


class PersonalFrequencyWindowResult(InputModel):
    window: Literal["RECENT", "LONG_TERM"]
    evidence: FeatureWindowEvidence | None = None
    result: FeatureScoreResult
    diagnostic: FrequencyDiagnostic
    calculation: FrequencyCalculation | None = None

    @model_validator(mode="after")
    def validate_candidate(self) -> Self:
        scored = self.diagnostic == "SCORED"
        if self.result.feature_id not in FREQUENCY_FEATURES or scored != (self.result.status == "SCORED"):
            raise ValueError("frequency candidate must preserve feature and diagnostic status")
        if scored != (self.calculation is not None):
            raise ValueError("only scored frequency candidates have calculation evidence")
        if self.evidence is not None and (self.evidence.window != self.window or
                self.evidence.distribution.feature_id != self.result.feature_id):
            raise ValueError("frequency evidence must match the feature and statistical window")
        if scored:
            reason = "WITHIN_REFERENCE" if self.result.score.value == 0 else "PATTERN_DEVIATION"
            if self.result.reasons != (reason,):
                raise ValueError("frequency score reason must match its value")
        elif (self.result.status, self.result.reasons) != (
            UNSCORED_FREQUENCY[self.diagnostic][0], (UNSCORED_FREQUENCY[self.diagnostic][1],)
        ):
            raise ValueError("unscored frequency candidate must preserve its cause")
        attempted = scored or self.diagnostic == "CALCULATION_FAILED"
        if attempted:
            if self.evidence is None or not self.evidence.distribution.observation.valid_sample_count:
                raise ValueError("frequency calculation requires observed evidence")
            if len(self.result.bases) != 1 or self.result.bases[0].kind != "PERSONAL_PROFILE":
                raise ValueError("frequency calculation requires one personal profile")
        elif self.result.bases:
            raise ValueError("unattempted frequency cannot claim an applied basis")
        return self


class PersonalFrequencyFeatureResult(InputModel):
    raw: RawFeatureValue
    minimum_samples: Annotated[int, Field(ge=2)]
    windows: Annotated[Array[PersonalFrequencyWindowResult], Field(min_length=2, max_length=2)]

    @model_validator(mode="after")
    def validate_feature(self) -> Self:
        if self.raw.feature_id not in FREQUENCY_FEATURES:
            raise ValueError("personal frequency requires a purchase or attempt count")
        if [row.window for row in self.windows] != ["RECENT", "LONG_TERM"]:
            raise ValueError("frequency candidates require both statistical windows in order")
        for row in self.windows:
            if row.result.feature_id != self.raw.feature_id:
                raise ValueError("frequency results cannot switch event kind or time window")
            if row.result.bases and (
                self.raw.status != "AVAILABLE" or
                row.evidence.distribution.observation.valid_sample_count < self.minimum_samples
            ):
                raise ValueError("frequency calculation requires input and enough feature samples")
        return self


class PersonalFrequencyResult(InputModel):
    schema_version: Literal["personal-frequency-result-v1"] = "personal-frequency-result-v1"
    input_sha256: Sha256
    policy: PersonalFrequencyPolicy
    policy_sha256: Sha256
    profile: ProfileAvailability
    features: Annotated[Array[PersonalFrequencyFeatureResult], Field(min_length=1, max_length=6)]

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if self.policy_sha256 != personalization_hash(self.policy):
            raise ValueError("frequency policy must match its recorded hash")
        if self.profile.scope != "PERSONAL":
            raise ValueError("frequency result requires personal profile availability")
        ids = [item.raw.feature_id for item in self.features]
        if ids != [name for name in FREQUENCY_FEATURES if name in ids]:
            raise ValueError("frequency results require unique features in schema order")
        for feature in self.features:
            for row in feature.windows:
                if (row.evidence is None) != (self.profile.status == "NOT_SUPPLIED"):
                    raise ValueError("supplied personal profile must retain its window evidence")
                if row.result.bases and (self.profile.status != "AVAILABLE" or
                    row.result.bases[0].profile != self.profile.context.profile.reference):
                    raise ValueError("frequency basis must match the available published profile")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        """Recompute separately decoded input, requested features, evidence and scores."""
        from copenhagen_fds.personal_frequency import score_personal_frequencies

        if self != score_personal_frequencies(supplied, self.policy):
            raise ValueError("frequency result must reproduce from its exact input and policy")
        return self
