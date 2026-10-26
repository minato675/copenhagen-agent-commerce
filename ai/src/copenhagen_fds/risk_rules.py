"""Preserve weighted results and resolve observed strong/concurrent signal floors."""

from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_rules import RiskRuleResult, risk_rule_fields
from copenhagen_fds.risk_aggregation import aggregate_risk_behavior


def evaluate_risk_rules(supplied: RiskPolicyInput) -> RiskRuleResult:
    """A floor is a policy constraint, never a final classification or payment action."""
    aggregation = aggregate_risk_behavior(supplied)
    return RiskRuleResult(aggregation=aggregation, **risk_rule_fields(aggregation))
