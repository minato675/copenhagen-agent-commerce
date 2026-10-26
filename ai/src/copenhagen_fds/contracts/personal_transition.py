"""Reference-use decisions before score calculation; not final behavior results."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.baselines import SelectedFeatureBasis
from copenhagen_fds.contracts.features import RawFeatureValue
from copenhagen_fds.contracts.personal_readiness import PersonalReadinessResult
from copenhagen_fds.contracts.personalization_config import FractionValue
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.values import Array, Identifier, InputModel


class FeatureTransition(InputModel):
    raw: RawFeatureValue
    cold_reference: SelectedFeatureBasis
    selected_window: Literal["RECENT", "LONG_TERM"] | None
    preferred_personal_weight: FractionValue
    stage: Literal["COLD", "TRANSITION", "PERSONALIZED", "UNAVAILABLE"]
    personal_weight: FractionValue | None
    cold_weight: FractionValue | None
    reasons: Annotated[Array[Identifier], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_weights(self) -> Self:
        if self.cold_reference.feature_id != self.raw.feature_id or len(set(self.reasons)) != len(self.reasons):
            raise ValueError("transition must preserve feature and unique reasons")
        if (self.selected_window is not None) != (self.preferred_personal_weight > 0):
            raise ValueError("positive personal support requires a selected window")
        if self.stage == "UNAVAILABLE":
            if self.personal_weight is not None or self.cold_weight is not None:
                raise ValueError("unavailable transition cannot claim applied weights")
            return self
        if self.raw.status != "AVAILABLE" or self.personal_weight is None or self.cold_weight != 1.0 - self.personal_weight:
            raise ValueError("usable transition requires current input and complementary weights")
        expected = "COLD" if self.personal_weight == 0 else "PERSONALIZED" if self.personal_weight == 1 else "TRANSITION"
        if self.stage != expected or self.personal_weight != self.preferred_personal_weight:
            raise ValueError("transition cannot promote or renormalize personal support")
        if self.cold_weight > 0 and self.cold_reference.status != "SELECTED":
            raise ValueError("positive Cold weight requires a valid selected reference")
        return self


class PersonalTransitionResult(InputModel):
    schema_version: Literal["personal-transition-result-v1"] = "personal-transition-result-v1"
    # Proposed policy weights, conditional on successful component calculation.
    readiness: PersonalReadinessResult
    features: Annotated[Array[FeatureTransition], Field(min_length=1, max_length=9)]

    @model_validator(mode="after")
    def validate_features(self) -> Self:
        if [f.raw.feature_id for f in self.features] != [f.feature_id for f in self.readiness.features]:
            raise ValueError("transition must preserve all ordered readiness features")
        for result, support in zip(self.features, self.readiness.features):
            best = max(support.windows, key=lambda w: w.personal_weight)
            window = best.window if best.personal_weight > 0 else None
            if result.selected_window != window or result.preferred_personal_weight != best.personal_weight:
                raise ValueError("transition must select maximum support with recent winning ties")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        from copenhagen_fds.personal_transition import decide_personal_transition

        if self != decide_personal_transition(supplied):
            raise ValueError("transition must reproduce from its exact input and policy")
        return self
