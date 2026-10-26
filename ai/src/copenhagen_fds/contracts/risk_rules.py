"""Risk floor evidence without modifying scores or resolving failed decisions."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.risk_aggregation import RiskAggregationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_results import PolicyReason, RISK_RANK, RiskRuleEvidence, RuleObservation
from copenhagen_fds.contracts.risk_rule_checks import RiskRuleCheck
from copenhagen_fds.contracts.values import Array, Identifier, InputModel
from copenhagen_fds.risk_rule_checks import evaluate_risk_rule


def risk_rule_fields(aggregation: RiskAggregationResult) -> dict:
    rules = aggregation.assessment.coverage.policy.floor_rules
    checks = tuple(evaluate_risk_rule(rule, aggregation.groups) for rule in sorted(rules, key=lambda r: r.rule_id))
    evidence = tuple(RiskRuleEvidence(rule_id=check.rule.rule_id, minimum_risk=check.rule.minimum_risk,
        observations=tuple(RuleObservation(group_id=row.group_id, score=row.score) for row in check.observations))
        for check in checks if check.status == "MATCHED")
    floor = max((row.minimum_risk for row in evidence), key=RISK_RANK.get, default=None)
    return dict(checks=checks, rule_evidence=evidence, minimum_risk=floor,
        decisive_rule_ids=tuple(row.rule_id for row in evidence if row.minimum_risk == floor),
        reasons=tuple(PolicyReason(code="RULE_TRIGGERED", rule_id=row.rule_id) for row in evidence))


class RiskRuleResult(InputModel):
    schema_version: Literal["risk-rule-result-v1"] = "risk-rule-result-v1"
    evaluation_version: Literal["risk-floor-observed-v1"] = "risk-floor-observed-v1"
    aggregation: RiskAggregationResult
    checks: Array[RiskRuleCheck]
    rule_evidence: Array[RiskRuleEvidence]
    minimum_risk: Literal["MEDIUM", "HIGH"] | None
    decisive_rule_ids: Array[Identifier]
    reasons: Array[PolicyReason]

    @model_validator(mode="after")
    def validate_resolution(self) -> Self:
        for field, expected in risk_rule_fields(self.aggregation).items():
            if getattr(self, field) != expected:
                raise ValueError("rule resolution must retain every check, observed match and highest floor")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        self.aggregation.validate_for_input(supplied)
        return type(self).model_validate(self.model_dump())
