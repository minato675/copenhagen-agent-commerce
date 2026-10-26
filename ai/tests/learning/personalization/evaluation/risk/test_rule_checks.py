import math
from itertools import permutations

import pytest

from copenhagen_fds.contracts.risk_policy import RiskFloorRule
from copenhagen_fds.contracts.risk_rule_checks import RiskRuleCheck
from copenhagen_fds.risk_rule_checks import evaluate_risk_rule


@pytest.mark.parametrize("value,status", [
    (math.nextafter(90.0, 0.0), "NOT_MET"), (90.0, "MATCHED"),
    (math.nextafter(90.0, 100.0), "MATCHED"), (0.0, "NOT_MET"),
])
def test_inclusive_threshold_boundary(rule_cases, value, status):
    rule = rule_cases.rule(AMOUNT=90.0)
    check = evaluate_risk_rule(rule, rule_cases.groups(AMOUNT=value))
    assert check.status == status
    assert check.observations[0].score.value == value and check.rule == rule


@pytest.mark.parametrize("amount,frequency,status", [
    (60.0, 60.0, "MATCHED"), (60.0, 59.0, "NOT_MET"), (59.0, 60.0, "NOT_MET"),
])
def test_concurrent_conditions_require_different_groups_and_all_thresholds(rule_cases, amount, frequency, status):
    rule = rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0)
    check = evaluate_risk_rule(rule, rule_cases.groups(AMOUNT=amount, FREQUENCY=frequency))
    assert check.status == status
    assert [row.group_id for row in check.observations] == ["AMOUNT", "FREQUENCY"]


@pytest.mark.parametrize("amount,missing,status", [
    (100.0, "FREQUENCY", "UNAVAILABLE"), (0.0, "FREQUENCY", "UNAVAILABLE"),
    (100.0, "TIME", "MATCHED"),
])
def test_missing_condition_is_not_a_match_or_inferred_zero(rule_cases, amount, missing, status):
    check = evaluate_risk_rule(rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0),
        rule_cases.groups(AMOUNT=amount, FREQUENCY=100.0, missing=missing))
    assert check.status == status
    assert (check.observations[1].score is None) == (missing == "FREQUENCY")


def test_group_input_order_and_check_roundtrip(rule_cases):
    rule = rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0)
    groups = rule_cases.groups(AMOUNT=100.0, FREQUENCY=100.0)
    expected = evaluate_risk_rule(rule, groups)
    for reordered in permutations(groups):
        assert evaluate_risk_rule(rule, reordered) == expected
    assert RiskRuleCheck.model_validate_json(expected.model_dump_json()) == expected


@pytest.mark.parametrize("change", ["duplicate_group", "omitted_group", "duplicate_condition"])
def test_duplicate_and_omitted_groups_rejected(rule_cases, change):
    rule, groups = rule_cases.rule(AMOUNT=60.0), rule_cases.groups()
    if change == "duplicate_group":
        groups = (groups[0], groups[0], groups[2])
    elif change == "omitted_group":
        groups = groups[:2]
    else:
        rule = rule.model_copy(update={"conditions": rule.conditions * 2})
    with pytest.raises(ValueError):
        evaluate_risk_rule(rule, groups)


@pytest.mark.parametrize("change", ["status", "missing_observation", "duplicate_observation", "unknown_group"])
def test_check_tamper_rejected(rule_cases, change):
    data = evaluate_risk_rule(rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0), rule_cases.groups()).model_dump()
    if change == "status":
        data["status"] = "MATCHED"
    elif change == "missing_observation":
        data["observations"] = data["observations"][:1]
    elif change == "duplicate_observation":
        data["observations"] = data["observations"][:1] * 2
    else:
        data["observations"][0]["group_id"] = "OTHER"
    with pytest.raises(ValueError):
        RiskRuleCheck.model_validate(data)


@pytest.mark.parametrize("value", [0.0, -1.0, 101.0, float("nan"), float("inf")])
def test_invalid_threshold_rejected(rule_cases, value):
    data = rule_cases.rule(AMOUNT=60.0).model_dump()
    data["conditions"][0]["minimum_score"] = value
    with pytest.raises(ValueError):
        RiskFloorRule.model_validate(data)
