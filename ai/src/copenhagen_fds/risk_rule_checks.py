"""Check one configured strong or concurrent signal over existing group scores."""

from collections.abc import Sequence

from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES, RiskFloorRule
from copenhagen_fds.contracts.risk_results import GroupContribution
from copenhagen_fds.contracts.risk_rule_checks import RiskRuleCheck, RiskRuleObservation, rule_check_status


def evaluate_risk_rule(rule: RiskFloorRule, groups: Sequence[GroupContribution]) -> RiskRuleCheck:
    """All conditions are inclusive AND; unavailable scores never imply a match."""
    rule = RiskFloorRule.model_validate(rule.model_dump())
    groups = tuple(GroupContribution.model_validate(group.model_dump()) for group in groups)
    indexed = {group.group_id: group for group in groups}
    if len(indexed) != len(groups) or set(indexed) != set(GROUP_FEATURES):
        raise ValueError("rule input must retain all three groups exactly once, including unavailable groups")
    observations = tuple(RiskRuleObservation(group_id=condition.group_id,
        score=indexed[condition.group_id].score) for condition in rule.conditions)
    return RiskRuleCheck(rule=rule, observations=observations, status=rule_check_status(rule, observations))
