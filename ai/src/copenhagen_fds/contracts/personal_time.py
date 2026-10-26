"""Versioned circular-bin rarity candidates, retaining exact smoothing evidence."""

from fractions import Fraction
from math import gcd
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.features import RawFeatureValue
from copenhagen_fds.contracts.personalization_inputs import (
    PersonalizedScoringInput, ProfileAvailability, personalization_hash,
)
from copenhagen_fds.contracts.personalization_results import FeatureWindowEvidence
from copenhagen_fds.contracts.values import Array, InputModel, Sha256


TIME_PERIODS = {"kst_hour": 24, "kst_day_of_week": 7}
TIME_SCORE_VERSION = "personal-time-circular-rarity-v1"
NeighborWeight = Annotated[float, Field(ge=0, le=0.5, allow_inf_nan=False)]


class PersonalTimePolicy(InputModel):
    version: Literal["personal-time-circular-rarity-v1"] = TIME_SCORE_VERSION
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    hour_neighbor_weight: NeighborWeight = 0.25
    weekday_neighbor_weight: NeighborWeight = 0.25
    pseudocount: Annotated[float, Field(gt=0, allow_inf_nan=False)] = 1.0


class ExactTimeMass(InputModel):
    """Reduced rational mass after circular smoothing and additive correction."""

    numerator: Annotated[int, Field(gt=0)]
    denominator: Annotated[int, Field(gt=0)]

    @model_validator(mode="after")
    def validate_reduced(self) -> Self:
        if gcd(self.numerator, self.denominator) != 1:
            raise ValueError("time mass must use a reduced fraction")
        return self

    @classmethod
    def from_fraction(cls, value: Fraction) -> Self:
        return cls(numerator=value.numerator, denominator=value.denominator)

    def as_fraction(self) -> Fraction:
        return Fraction(self.numerator, self.denominator)


class TimeCalculation(InputModel):
    period: Literal[7, 24]
    bin_index: Annotated[int, Field(ge=0)]
    sample_count: Annotated[int, Field(ge=2)]
    direct_count: Annotated[int, Field(ge=0)]
    previous_count: Annotated[int, Field(ge=0)]
    next_count: Annotated[int, Field(ge=0)]
    bin_status: Literal["OBSERVED", "UNOBSERVED"]
    smoothed_mass: ExactTimeMass
    peak_mass: ExactTimeMass
    total_mass: ExactTimeMass

    @model_validator(mode="after")
    def validate_mass(self) -> Self:
        if self.bin_index >= self.period:
            raise ValueError("time bin must lie within its circular period")
        if self.direct_count + self.previous_count + self.next_count > self.sample_count:
            raise ValueError("distinct neighboring bins cannot exceed observed samples")
        if (self.bin_status == "OBSERVED") != (self.direct_count > 0):
            raise ValueError("bin observation must describe unsmoothed direct counts")
        if not self.smoothed_mass.as_fraction() <= self.peak_mass.as_fraction() <= self.total_mass.as_fraction():
            raise ValueError("time mass must not exceed peak or total")
        return self


TimeDiagnostic = Literal[
    "SCORED", "INPUT_NOT_COLLECTED", "INPUT_INSUFFICIENT_HISTORY", "INPUT_NOT_APPLICABLE",
    "PROFILE_NOT_SUPPLIED", "PROFILE_EXPIRED", "EMPTY", "INSUFFICIENT_SAMPLES", "CALCULATION_FAILED",
]
UNSCORED_TIME = {
    "INPUT_NOT_COLLECTED": ("NOT_COLLECTED", "MISSING_INPUT"),
    "INPUT_INSUFFICIENT_HISTORY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "INPUT_NOT_APPLICABLE": ("NOT_APPLICABLE", "NOT_APPLICABLE"),
    "PROFILE_NOT_SUPPLIED": ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE"),
    "PROFILE_EXPIRED": ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE"),
    "EMPTY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "INSUFFICIENT_SAMPLES": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "CALCULATION_FAILED": ("FAILED", "CALCULATION_FAILED"),
}


class PersonalTimeWindowResult(InputModel):
    window: Literal["RECENT", "LONG_TERM"]
    evidence: FeatureWindowEvidence | None = None
    result: FeatureScoreResult
    diagnostic: TimeDiagnostic
    calculation: TimeCalculation | None = None

    @model_validator(mode="after")
    def validate_candidate(self) -> Self:
        scored = self.diagnostic == "SCORED"
        if self.result.feature_id not in TIME_PERIODS or scored != (self.result.status == "SCORED"):
            raise ValueError("time candidate must preserve feature and diagnostic status")
        if scored != (self.calculation is not None):
            raise ValueError("only scored time candidates have calculation evidence")
        if self.evidence is not None and (self.evidence.window != self.window or
                self.evidence.distribution.feature_id != self.result.feature_id):
            raise ValueError("time evidence must match feature and statistical window")
        if scored:
            reason = "WITHIN_REFERENCE" if self.result.score.value == 0 else "PATTERN_DEVIATION"
            if self.result.reasons != (reason,):
                raise ValueError("time score reason must match its value")
        elif (self.result.status, self.result.reasons) != (
            UNSCORED_TIME[self.diagnostic][0], (UNSCORED_TIME[self.diagnostic][1],)
        ):
            raise ValueError("unscored time candidate must preserve its cause")
        attempted = scored or self.diagnostic == "CALCULATION_FAILED"
        if attempted:
            if self.evidence is None or not self.evidence.distribution.observation.valid_sample_count:
                raise ValueError("time calculation requires observed evidence")
            if len(self.result.bases) != 1 or self.result.bases[0].kind != "PERSONAL_PROFILE":
                raise ValueError("time calculation requires one personal profile")
        elif self.result.bases:
            raise ValueError("unattempted time candidate cannot claim an applied basis")
        if self.calculation is not None:
            calc, dist = self.calculation, self.evidence.distribution
            counts = tuple(item.count for item in dist.histogram)
            i = calc.bin_index
            if calc.period != TIME_PERIODS[self.result.feature_id] or calc.sample_count != sum(counts):
                raise ValueError("time calculation must retain its period and sample count")
            if (calc.direct_count, calc.previous_count, calc.next_count) != (
                counts[i], counts[(i - 1) % calc.period], counts[(i + 1) % calc.period]
            ):
                raise ValueError("time calculation must retain circular histogram counts")
        return self


class PersonalTimeFeatureResult(InputModel):
    raw: RawFeatureValue
    minimum_samples: Annotated[int, Field(ge=2)]
    windows: Annotated[Array[PersonalTimeWindowResult], Field(min_length=2, max_length=2)]

    @model_validator(mode="after")
    def validate_feature(self) -> Self:
        if self.raw.feature_id not in TIME_PERIODS:
            raise ValueError("personal time requires an hour or weekday feature")
        if [row.window for row in self.windows] != ["RECENT", "LONG_TERM"]:
            raise ValueError("time candidates require both statistical windows in order")
        for row in self.windows:
            if row.result.feature_id != self.raw.feature_id:
                raise ValueError("time results cannot switch feature")
            if row.result.bases and (self.raw.status != "AVAILABLE" or
                    row.evidence.distribution.observation.valid_sample_count < self.minimum_samples):
                raise ValueError("time calculation requires input and enough feature samples")
            if row.calculation is not None and row.calculation.bin_index != self.raw.value:
                raise ValueError("time calculation must use the current raw bin")
        return self


class PersonalTimeResult(InputModel):
    schema_version: Literal["personal-time-result-v1"] = "personal-time-result-v1"
    input_sha256: Sha256
    policy: PersonalTimePolicy
    policy_sha256: Sha256
    profile: ProfileAvailability
    features: Annotated[Array[PersonalTimeFeatureResult], Field(min_length=1, max_length=2)]

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if self.policy_sha256 != personalization_hash(self.policy):
            raise ValueError("time policy must match its recorded hash")
        if self.profile.scope != "PERSONAL":
            raise ValueError("time result requires personal profile availability")
        ids = [item.raw.feature_id for item in self.features]
        if ids != [name for name in TIME_PERIODS if name in ids]:
            raise ValueError("time results require unique features in schema order")
        for feature in self.features:
            for row in feature.windows:
                if (row.evidence is None) != (self.profile.status == "NOT_SUPPLIED"):
                    raise ValueError("supplied personal profile must retain its window evidence")
                if row.result.bases and (self.profile.status != "AVAILABLE" or
                    row.result.bases[0].profile != self.profile.context.profile.reference):
                    raise ValueError("time basis must match the available published profile")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        """Recompute separately decoded input, evidence, smoothing and scores."""
        from copenhagen_fds.personal_time import score_personal_times

        if self != score_personal_times(supplied, self.policy):
            raise ValueError("time result must reproduce from its exact input and policy")
        return self
