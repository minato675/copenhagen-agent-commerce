"""One explicit event snapshot and pinned reference identities for Cold evaluation."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.contracts.profiles import ProfileReference
from copenhagen_fds.contracts.values import Identifier, InputModel


class ColdEvaluationInput(InputModel):
    format_version: Literal["cold-evaluation-v1"] = "cold-evaluation-v1"
    feature_input: FeatureTransformInput
    population_reference: ProfileReference | None
    policy_id: Identifier | None
    policy_version: Identifier | None
    trace_id: Identifier

    def baseline_request(self) -> BaselineSelectionRequest:
        return BaselineSelectionRequest(
            evaluation=self.feature_input.evaluation,
            population_reference=self.population_reference,
            policy_id=self.policy_id, policy_version=self.policy_version,
        )

    @model_validator(mode="after")
    def validate_reference_identity(self) -> Self:
        self.baseline_request()
        return self
