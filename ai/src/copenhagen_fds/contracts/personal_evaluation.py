"""Complete, reproducible personal evaluation with scale and calculation evidence."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.personal_components import PersonalComponentsResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.personalization_results import PersonalizedScoringResult
from copenhagen_fds.contracts.values import Array, Identifier, InputModel


class FeatureScaleAgreement(InputModel):
    feature_id: Identifier
    cold_score_version: Literal["cold-distance-linear-v1"] = "cold-distance-linear-v1"
    personal_score_version: Identifier
    cold_method: Literal["UPPER_DISTANCE", "ABSOLUTE_DISTANCE", "CIRCULAR_DISTANCE"] | None
    rule: Literal["UPPER_DEVIATION", "CIRCULAR_DISTANCE_RARITY_HEURISTIC", "INCOMPATIBLE_DIRECTION", "NO_COLD_REFERENCE"]
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"


class PersonalizedEvaluationResult(InputModel):
    schema_version: Literal["personalized-evaluation-result-v1"] = "personalized-evaluation-result-v1"
    components: PersonalComponentsResult
    scale_agreements: Annotated[Array[FeatureScaleAgreement], Field(min_length=1, max_length=9)]
    result: PersonalizedScoringResult

    @model_validator(mode="after")
    def validate_assembly(self) -> Self:
        from copenhagen_fds.personal_evaluation import combine_feature, scale_agreement

        result, components = self.result, self.components
        support = components.transition.readiness
        if (result.input_sha256, result.config, result.config_sha256, result.profiles[0]) != (
            components.input_sha256, support.config, support.config_sha256, support.profile
        ):
            raise ValueError("evaluation must preserve its input, configuration and personal publication")
        ids = [f.combined.feature_id for f in result.features]
        if ids != [f.result.feature_id for f in components.features] or ids != [s.feature_id for s in self.scale_agreements]:
            raise ValueError("assembled scores, components and scale agreements must cover the same features")
        settings = {f.feature_id: f for f in result.config.features}
        for row, component, decision, readiness, agreement in zip(result.features, components.features,
                components.transition.features, support.features, self.scale_agreements):
            version = settings[row.combined.feature_id].score_version
            if agreement != scale_agreement(decision, version):
                raise ValueError("score compatibility must reflect the actual selected methods")
            expected = combine_feature(row.cold, component, decision, readiness, agreement, version)
            if row != expected:
                raise ValueError("final scores must follow the recorded transition and fallback policy")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        """Replay computations; a recorded transient failure may not reproduce on retry."""
        from copenhagen_fds.personal_evaluation import evaluate_personalized_features

        self.result.validate_for_input(supplied)
        if self != evaluate_personalized_features(supplied, self.components.policies):
            raise ValueError("evaluation must reproduce from its exact input and calculation policies")
        return self
