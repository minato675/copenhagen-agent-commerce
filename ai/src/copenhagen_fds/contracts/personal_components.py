"""Versioned score semantics and selected personal calculation evidence."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.personal_amount import PersonalAmountPolicy, PersonalAmountWindowResult
from copenhagen_fds.contracts.personal_frequency import PersonalFrequencyPolicy, PersonalFrequencyWindowResult
from copenhagen_fds.contracts.personal_time import PersonalTimePolicy, PersonalTimeWindowResult
from copenhagen_fds.contracts.personal_transition import PersonalTransitionResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


BLEND_SCALE_VERSION = "behavior-deviation-blend-v1"


class PersonalScoringPolicies(InputModel):
    """Explicit heuristic scale agreement; this is not probability calibration."""

    scale_version: Literal["behavior-deviation-blend-v1"] = BLEND_SCALE_VERSION
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    numeric_semantics: Literal["UPPER_DEVIATION_ONLY"] = "UPPER_DEVIATION_ONLY"
    time_semantics: Literal["CIRCULAR_DISTANCE_RARITY_HEURISTIC"] = "CIRCULAR_DISTANCE_RARITY_HEURISTIC"
    failure_policy: Literal["COLD_FALLBACK_WITH_FAILURE_RETAINED"] = "COLD_FALLBACK_WITH_FAILURE_RETAINED"
    amount: PersonalAmountPolicy = PersonalAmountPolicy()
    frequency: PersonalFrequencyPolicy = PersonalFrequencyPolicy()
    time: PersonalTimePolicy = PersonalTimePolicy()


PersonalCandidate = PersonalAmountWindowResult | PersonalFrequencyWindowResult | PersonalTimeWindowResult


class PersonalComponentResult(InputModel):
    result: FeatureScoreResult
    diagnostic: Identifier
    candidate: PersonalCandidate | None = None

    @model_validator(mode="after")
    def validate_candidate(self) -> Self:
        if self.candidate is not None:
            if self.result != self.candidate.result or self.diagnostic != self.candidate.diagnostic:
                raise ValueError("personal component must retain the exact selected calculation")
        elif self.result.bases or self.result.status in {"SCORED", "FAILED"}:
            raise ValueError("personal calculation requires its candidate evidence")
        return self


class PersonalComponentsResult(InputModel):
    schema_version: Literal["personal-components-result-v1"] = "personal-components-result-v1"
    input_sha256: Sha256
    policies: PersonalScoringPolicies
    policies_sha256: Sha256
    transition: PersonalTransitionResult
    features: Annotated[Array[PersonalComponentResult], Field(min_length=1, max_length=9)]

    @model_validator(mode="after")
    def validate_components(self) -> Self:
        if self.policies_sha256 != personalization_hash(self.policies):
            raise ValueError("personal calculation policies must match their hash")
        if self.input_sha256 != self.transition.readiness.input_sha256:
            raise ValueError("personal calculation and transition require the same input")
        if [f.result.feature_id for f in self.features] != [f.raw.feature_id for f in self.transition.features]:
            raise ValueError("personal components must cover the ordered requested features")
        for component, decision in zip(self.features, self.transition.features):
            if component.candidate is not None and component.candidate.window != decision.selected_window:
                raise ValueError("personal calculation cannot switch away from the policy-selected window")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        from copenhagen_fds.personal_components import evaluate_personal_components

        if self != evaluate_personal_components(supplied, self.policies):
            raise ValueError("personal components must reproduce from their exact input and policies")
        return self
