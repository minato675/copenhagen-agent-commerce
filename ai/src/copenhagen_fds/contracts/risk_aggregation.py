"""Reproducible weighted behavior scores with preserved assessment evidence."""

import math
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import AnomalyScore
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_assessment import RiskBehaviorAssessment
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_results import GroupContribution
from copenhagen_fds.contracts.values import Array, InputModel, Sha256
from copenhagen_fds.risk_groups import AGGREGATION_VERSION, aggregate_risk_group


def aggregation_fields(assessment: RiskBehaviorAssessment) -> dict:
    coverage = assessment.coverage
    groups = tuple(aggregate_risk_group(setting,
        [row.feature for row in coverage.features if row.group_id == setting.group_id])
        for setting in coverage.policy.groups)
    total = None
    if coverage.can_aggregate:
        # The policy accepts only a tiny floating-point tolerance around sum(weight)=1.
        total = AnomalyScore(value=min(100.0, math.fsum(g.weighted_points for g in groups)),
                             unit="BEHAVIOR_ANOMALY_0_100")
    digest = personalization_hash(dict(aggregation_version=AGGREGATION_VERSION,
        policy=coverage.policy, groups=groups, behavior_score=total))
    return dict(groups=groups, behavior_score=total, aggregation_sha256=digest)


class RiskAggregationResult(InputModel):
    schema_version: Literal["risk-aggregation-result-v1"] = "risk-aggregation-result-v1"
    aggregation_version: Literal["risk-group-max-first-v1"] = AGGREGATION_VERSION
    assessment: RiskBehaviorAssessment
    groups: Annotated[Array[GroupContribution], Field(min_length=3, max_length=3)]
    behavior_score: AnomalyScore | None
    # Arithmetic evidence only; assessment.coverage.input_reference binds the exact input.
    aggregation_sha256: Sha256

    @model_validator(mode="after")
    def validate_aggregation(self) -> Self:
        for field, expected in aggregation_fields(self.assessment).items():
            if getattr(self, field) != expected:
                raise ValueError("aggregation must preserve canonical MAX contributions, coverage and content hash")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        self.assessment.validate_for_input(supplied)
        # Revalidate even instances made through unchecked model_copy/model_construct.
        return type(self).model_validate(self.model_dump())
