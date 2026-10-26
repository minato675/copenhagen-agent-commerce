import math
from itertools import permutations

import pytest

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_policy import RiskPolicyConfig
from copenhagen_fds.risk_groups import aggregate_risk_group


@pytest.mark.parametrize("group,values,maximum,points,winner", [
    ("AMOUNT", [80.0], 80.0, 32.0, 0),
    ("FREQUENCY", [10.0, 60.0, 60.0, 20.0, 30.0, 40.0], 60.0, 21.0, 1),
    ("TIME", [20.0, 40.0], 40.0, 10.0, 1),
])
def test_group_hand_calculation(group_cases, group, values, maximum, points, winner):
    setting, features = group_cases(group, values)
    result = aggregate_risk_group(setting, features)
    assert result.score.value == maximum and result.weighted_points == points
    assert [row.weighted_points for row in result.features] == [
        points if i == winner else 0.0 for i in range(len(features))]
    assert tuple(row.feature for row in result.features) == features


@pytest.mark.parametrize("group,count,cap", [("FREQUENCY", 6, 35.0), ("TIME", 2, 25.0)])
def test_group_cap_does_not_grow_with_correlated_signals(group_cases, group, count, cap):
    one = aggregate_risk_group(*group_cases(group, [100.0] + [0.0] * (count - 1)))
    all_high = aggregate_risk_group(*group_cases(group, [100.0] * count))
    assert one.weighted_points == all_high.weighted_points == cap
    assert math.fsum(row.weighted_points for row in all_high.features) == cap


def test_all_input_orders_and_max_ties_reproduce_contributions_and_hash(group_cases):
    setting, features = group_cases("FREQUENCY", [10.0, 60.0, 60.0, 20.0, 30.0, 40.0])
    expected = aggregate_risk_group(setting, features)
    digest = personalization_hash(expected)
    for reordered in permutations(features):
        result = aggregate_risk_group(setting, reordered)
        assert result == expected
        assert personalization_hash(result) == digest
    assert expected.features[1].weighted_points == 21.0
    assert expected.features[2].weighted_points == 0.0


def test_zero_ties_are_valid_zero_contributions(group_cases):
    result = aggregate_risk_group(*group_cases("FREQUENCY", [0.0] * 6))
    assert result.score.value == result.weighted_points == 0.0
    assert all(row.weighted_points == 0.0 for row in result.features)


@pytest.mark.parametrize("state,reasons", [
    ("NOT_COLLECTED", ["MISSING_INPUT"]), ("NOT_APPLICABLE", ["NOT_APPLICABLE"]),
    ("FAILED", ["CALCULATION_FAILED"]),
])
def test_missing_member_keeps_valid_scores_without_partial_max(group_cases, state, reasons):
    setting, features = group_cases("TIME", [100.0, 10.0])
    missing = FeatureScoreResult(feature_id="kst_day_of_week", status=state, reasons=reasons)
    result = aggregate_risk_group(setting, [features[0], missing])
    assert result.score is result.weighted_points is None
    assert result.features[0].feature == features[0]
    assert result.features[1].feature == missing
    assert all(row.weighted_points is None for row in result.features)


@pytest.mark.parametrize("change", ["duplicate", "missing", "foreign", "extra"])
def test_duplicate_and_inexact_mapping_rejected(group_cases, change):
    setting, features = group_cases("TIME", [20.0, 40.0])
    foreign = group_cases("AMOUNT", [80.0])[1][0]
    rows = {"duplicate": [features[0], features[0]], "missing": [features[0]],
            "foreign": [features[0], foreign], "extra": [*features, foreign]}[change]
    with pytest.raises(ValueError, match="exactly once"):
        aggregate_risk_group(setting, rows)


@pytest.mark.parametrize("change", ["duplicate_group", "duplicate_feature", "foreign_mapping"])
def test_invalid_policy_mapping_rejected(risk_cases, change):
    data = risk_cases.policy().model_dump()
    groups = list(data["groups"])
    if change == "duplicate_group":
        groups[2] = groups[1]
    elif change == "duplicate_feature":
        groups[2]["features"] = (groups[2]["features"][0],) * 2
    else:
        groups[2]["features"][0]["feature_id"] = "amount_minor"
    data["groups"] = groups
    with pytest.raises(ValueError):
        RiskPolicyConfig.model_validate(data)


@pytest.mark.parametrize("value", [-1.0, 101.0, float("nan"), float("inf"), -float("inf"), True])
def test_invalid_score_in_unchecked_model_copy_rejected(group_cases, value):
    setting, features = group_cases("AMOUNT", [80.0])
    altered = features[0].model_copy(update={"score": features[0].score.model_copy(update={"value": value})})
    with pytest.raises(ValueError):
        aggregate_risk_group(setting, [altered])


@pytest.mark.parametrize("weight", [0.0, -0.1, 1.1, float("nan"), float("inf"), True])
def test_invalid_group_weight_rejected(group_cases, weight):
    setting, features = group_cases("AMOUNT", [80.0])
    with pytest.raises(ValueError):
        aggregate_risk_group(setting.model_copy(update={"weight": weight}), features)


def test_positive_underflow_preserves_deviation(group_cases):
    minimum = math.nextafter(0.0, 1.0)
    result = aggregate_risk_group(*group_cases("AMOUNT", [minimum]))
    assert result.score.value == result.weighted_points == minimum
    assert result.features[0].weighted_points == minimum
