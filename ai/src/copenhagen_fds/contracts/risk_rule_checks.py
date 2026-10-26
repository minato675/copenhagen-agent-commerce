"""Observed group conditions, with unavailable evidence distinct from a match."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import AnomalyScore
from copenhagen_fds.contracts.risk_policy import GroupId, RiskFloorRule
from copenhagen_fds.contracts.values import Array, InputModel


RuleCheckStatus = Literal["MATCHED", "NOT_MET", "UNAVAILABLE"]


class RiskRuleObservation(InputModel):
    group_id: GroupId
    score: AnomalyScore | None


def rule_check_status(rule: RiskFloorRule, observations: tuple[RiskRuleObservation, ...]) -> RuleCheckStatus:
    # All condition groups must be observed before either a positive or negative check.
    if any(row.score is None for row in observations):
        return "UNAVAILABLE"
    return "MATCHED" if all(row.score.value >= condition.minimum_score
        for row, condition in zip(observations, rule.conditions, strict=True)) else "NOT_MET"


class RiskRuleCheck(InputModel):
    schema_version: Literal["risk-rule-check-v1"] = "risk-rule-check-v1"
    rule: RiskFloorRule
    observations: Annotated[Array[RiskRuleObservation], Field(min_length=1, max_length=3)]
    status: RuleCheckStatus

    @model_validator(mode="after")
    def validate_check(self) -> Self:
        if [row.group_id for row in self.observations] != [c.group_id for c in self.rule.conditions]:
            raise ValueError("rule observations must retain every condition group in order")
        if self.status != rule_check_status(self.rule, self.observations):
            raise ValueError("rule check must preserve unavailable evidence and inclusive thresholds")
        return self
