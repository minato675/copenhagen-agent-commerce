"""Amount score candidates with exact calculation evidence and publication context."""

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


AMOUNT_SCORE_VERSION = "personal-amount-upper-linear-v1"


class PersonalAmountPolicy(InputModel):
    version: Literal["personal-amount-upper-linear-v1"] = AMOUNT_SCORE_VERSION
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    spread_multiplier: Annotated[float, Field(gt=0, allow_inf_nan=False)] = 3.0
    relative_scale_floor: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)] = 0.1
    minimum_scale_minor: Annotated[int, Field(ge=1)] = 1


class ExactAmount(InputModel):
    """A nonnegative rational number of minor units, without float rounding."""

    numerator: Annotated[int, Field(ge=0)]
    denominator: Annotated[int, Field(gt=0)]

    @model_validator(mode="after")
    def validate_reduced(self) -> Self:
        if gcd(self.numerator, self.denominator) != 1:
            raise ValueError("exact amount must use a reduced fraction")
        return self

    @classmethod
    def from_fraction(cls, value: Fraction) -> Self:
        return cls(numerator=value.numerator, denominator=value.denominator)

    def as_fraction(self) -> Fraction:
        return Fraction(self.numerator, self.denominator)


class AmountCalculation(InputModel):
    median_minor: ExactAmount
    q95_minor: ExactAmount
    iqr_minor: ExactAmount
    mad_minor: ExactAmount
    saturation_distance_minor: ExactAmount
    zero_spread: bool

    @model_validator(mode="after")
    def validate_scale(self) -> Self:
        if self.saturation_distance_minor.numerator == 0:
            raise ValueError("amount saturation distance must be positive")
        if self.q95_minor.as_fraction() < self.median_minor.as_fraction():
            raise ValueError("upper quantile must not be below median")
        return self


AmountDiagnostic = Literal[
    "SCORED", "INPUT_NOT_COLLECTED", "INPUT_INSUFFICIENT_HISTORY", "INPUT_NOT_APPLICABLE",
    "PROFILE_NOT_SUPPLIED", "PROFILE_EXPIRED", "EMPTY", "INSUFFICIENT_SAMPLES", "CALCULATION_FAILED",
]
UNSCORED_AMOUNT = {
    "INPUT_NOT_COLLECTED": ("NOT_COLLECTED", "MISSING_INPUT"),
    "INPUT_INSUFFICIENT_HISTORY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "INPUT_NOT_APPLICABLE": ("NOT_APPLICABLE", "NOT_APPLICABLE"),
    "PROFILE_NOT_SUPPLIED": ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE"),
    "PROFILE_EXPIRED": ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE"),
    "EMPTY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "INSUFFICIENT_SAMPLES": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "CALCULATION_FAILED": ("FAILED", "CALCULATION_FAILED"),
}


class PersonalAmountWindowResult(InputModel):
    window: Literal["RECENT", "LONG_TERM"]
    evidence: FeatureWindowEvidence | None = None
    result: FeatureScoreResult
    diagnostic: AmountDiagnostic
    calculation: AmountCalculation | None = None

    @model_validator(mode="after")
    def validate_candidate(self) -> Self:
        scored = self.diagnostic == "SCORED"
        if self.result.feature_id != "amount_minor" or scored != (self.result.status == "SCORED"):
            raise ValueError("amount candidate must preserve feature and diagnostic status")
        if scored != (self.calculation is not None):
            raise ValueError("only a scored amount candidate has calculation evidence")
        if self.evidence is not None and (
            self.evidence.window != self.window or self.evidence.distribution.feature_id != "amount_minor"
        ):
            raise ValueError("amount evidence must match its candidate window")
        if scored:
            reason = "WITHIN_REFERENCE" if self.result.score.value == 0 else "PATTERN_DEVIATION"
            if self.result.reasons != (reason,):
                raise ValueError("amount score reason must match its value")
        elif (self.result.status, self.result.reasons) != (
            UNSCORED_AMOUNT[self.diagnostic][0], (UNSCORED_AMOUNT[self.diagnostic][1],)
        ):
            raise ValueError("unscored amount candidate must preserve its cause")
        attempted = scored or self.diagnostic == "CALCULATION_FAILED"
        if attempted:
            if self.evidence is None or not self.evidence.distribution.observation.valid_sample_count:
                raise ValueError("amount calculation requires observed window evidence")
            if len(self.result.bases) != 1 or self.result.bases[0].kind != "PERSONAL_PROFILE":
                raise ValueError("amount calculation must use one personal profile")
        elif self.result.bases:
            raise ValueError("unattempted amount candidate cannot claim an applied basis")
        return self


class PersonalAmountResult(InputModel):
    schema_version: Literal["personal-amount-result-v1"] = "personal-amount-result-v1"
    input_sha256: Sha256
    policy: PersonalAmountPolicy
    policy_sha256: Sha256
    raw: RawFeatureValue
    profile: ProfileAvailability
    minimum_samples: Annotated[int, Field(ge=2)]
    windows: Annotated[Array[PersonalAmountWindowResult], Field(min_length=2, max_length=2)]

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if self.policy_sha256 != personalization_hash(self.policy):
            raise ValueError("amount policy must match its recorded hash")
        if self.raw.feature_id != "amount_minor" or self.profile.scope != "PERSONAL":
            raise ValueError("amount result requires amount input and personal availability")
        if [row.window for row in self.windows] != ["RECENT", "LONG_TERM"]:
            raise ValueError("amount candidates require both windows in order")
        for row in self.windows:
            if (row.evidence is None) != (self.profile.status == "NOT_SUPPLIED"):
                raise ValueError("supplied profile must retain both candidate windows")
            if row.result.bases:
                if self.raw.status != "AVAILABLE" or self.profile.status != "AVAILABLE":
                    raise ValueError("attempted amount requires available input and profile")
                if row.result.bases[0].profile != self.profile.context.profile.reference:
                    raise ValueError("amount basis must match the published personal profile")
                if row.evidence.distribution.observation.valid_sample_count < self.minimum_samples:
                    raise ValueError("amount calculation requires the configured sample minimum")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        """Recompute to check separately decoded input, policy, evidence and scores."""
        from copenhagen_fds.personal_amount import score_personal_amount

        if self != score_personal_amount(supplied, self.policy):
            raise ValueError("amount result must reproduce from its exact input and policy")
        return self
