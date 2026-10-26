"""Explicit offline replay settings, evaluation points and auditable results."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.baselines import InitialPolicy
from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.contracts.personal_components import PersonalScoringPolicies
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.contracts.personal_profiles import PersonalProfileConfig
from copenhagen_fds.contracts.personalization_config import PersonalizationConfig
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.profile_policy import ProfilePolicyConfig
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, InputModel, Identifier, Sha256


class PersonalReplaySettings(InputModel):
    version: Literal["personal-replay-settings-v1"] = "personal-replay-settings-v1"
    personalization: PersonalizationConfig
    scoring: PersonalScoringPolicies = PersonalScoringPolicies()
    profile: PersonalProfileConfig = PersonalProfileConfig()
    admission: ProfilePolicyConfig = ProfilePolicyConfig()
    profile_valid_for_seconds: Annotated[int, Field(gt=0, le=31536000)]
    cold_policy: InitialPolicy | None


class PersonalReplayCase(InputModel):
    case_id: Identifier
    mode: Literal["DATASET", "CONTROLLED_PROBE"]
    profile_as_of: Timestamp | None
    input: FeatureTransformInput

    @model_validator(mode="after")
    def validate_cutoff(self) -> Self:
        if self.profile_as_of is not None and self.profile_as_of >= self.input.evaluation.evaluated_at:
            raise ValueError("profile cutoff must strictly precede evaluation")
        return self


class PersonalReplayRequest(InputModel):
    version: Literal["personal-replay-request-v1"] = "personal-replay-request-v1"
    settings: PersonalReplaySettings
    cases: Annotated[Array[PersonalReplayCase], Field(min_length=1, max_length=100)]

    @model_validator(mode="after")
    def validate_cases(self) -> Self:
        if len({c.case_id for c in self.cases}) != len(self.cases):
            raise ValueError("replay case identities must be unique")
        return self


class PersonalReplayRow(InputModel):
    case: PersonalReplayCase
    input: PersonalizedScoringInput
    evaluation: PersonalizedEvaluationResult

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        from copenhagen_fds.features import transform_features

        self.evaluation.result.validate_for_input(self.input)
        self.input.cold.raw_features.validate_for_request(self.case.input.evaluation)
        if self.input.cold.raw_features != transform_features(self.case.input):
            raise ValueError("replay raw features must reproduce from the recorded observable input")
        if self.input.cold.request.evaluation != self.case.input.evaluation:
            raise ValueError("replay must preserve its declared evaluation request")
        personal = self.input.personal
        if (personal is None) != (self.case.profile_as_of is None):
            raise ValueError("only an explicit cutoff may produce a personal profile")
        if personal is not None and personal.state.published.profile.as_of != self.case.profile_as_of:
            raise ValueError("published profile must use the declared replay cutoff")
        return self


class PersonalReplayResult(InputModel):
    version: Literal["personal-replay-result-v1"] = "personal-replay-result-v1"
    request_sha256: Sha256
    rows: Annotated[Array[PersonalReplayRow], Field(min_length=1, max_length=100)]

    def validate_for_request(self, supplied: PersonalReplayRequest) -> Self:
        from datetime import timedelta
        from copenhagen_fds.baselines import select_cold_baselines

        if self.request_sha256 != personalization_hash(supplied) or tuple(r.case for r in self.rows) != supplied.cases:
            raise ValueError("replay must preserve its exact request and ordered cases")
        for row in self.rows:
            if row.input.config != supplied.settings.personalization or row.evaluation.components.policies != supplied.settings.scoring:
                raise ValueError("replay must use its declared scoring settings")
            if row.input.cold.selection != select_cold_baselines(row.input.cold.request, policy=supplied.settings.cold_policy):
                raise ValueError("replay Cold selection must use the declared policy")
            if row.input.personal is not None:
                state = row.input.personal.state
                if state.published.config != supplied.settings.profile or state.evaluation.config != supplied.settings.admission:
                    raise ValueError("replay must use its declared profile and admission settings")
                if state.published.profile.valid_until != row.case.profile_as_of + timedelta(seconds=supplied.settings.profile_valid_for_seconds):
                    raise ValueError("replay profile validity must follow the declared duration")
        return self
