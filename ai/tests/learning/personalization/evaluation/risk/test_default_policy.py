"""Project policy regression: extreme features request review without a HIGH floor."""

import math

import pytest

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_evaluation import evaluate_risk_policy
from copenhagen_fds.risk_groups import aggregate_risk_group
from copenhagen_fds.risk_rule_checks import evaluate_risk_rule
from copenhagen_fds.risk_settings import default_risk_policy


FEATURES = (
    "amount_minor", "purchase_count_10m", "purchase_count_1h", "purchase_count_24h",
    "attempt_count_10m", "attempt_count_1h", "attempt_count_24h", "kst_hour", "kst_day_of_week",
)


@pytest.mark.parametrize("feature_id", FEATURES)
@pytest.mark.parametrize("value,expected", [
    (math.nextafter(100.0, 0.0), "NOT_MET"), (100.0, "MATCHED"), (None, "UNAVAILABLE"),
])
def test_each_feature_extreme_boundary_and_missing_evidence(feature_id, value, expected):
    policy, groups = default_risk_policy(), []
    for setting in policy.groups:
        features = []
        for member in setting.features:
            score = value if member.feature_id == feature_id else 0.0
            features.append(FeatureScoreResult(feature_id=member.feature_id,
                status="NOT_COLLECTED" if score is None else "SCORED",
                score=None if score is None else dict(value=score, unit="BEHAVIOR_ANOMALY_0_100"),
                bases=[] if score is None else [dict(kind="INITIAL_POLICY", policy_version="boundary-fixture")],
                reasons=["MISSING_INPUT"] if score is None else
                    ["WITHIN_REFERENCE" if score == 0 else "PATTERN_DEVIATION"]))
        groups.append(aggregate_risk_group(setting, features))
    containing = next(g.group_id for g in policy.groups if feature_id in {f.feature_id for f in g.features})
    checks = [evaluate_risk_rule(rule, groups) for rule in policy.floor_rules]
    matched_group = next(check for check in checks if check.rule.conditions[0].group_id == containing)
    assert matched_group.status == expected
    assert all(check.rule.minimum_risk == "MEDIUM" for check in checks)
    assert all(check.status == "NOT_MET" for check in checks if check is not matched_group)
    if value is not None:
        assert math.fsum(group.weighted_points for group in groups) < 70.0


def _evaluate(supplied):
    data = supplied.model_dump()
    data["policy"] = default_risk_policy()
    return evaluate_risk_policy(RiskPolicyInput.model_validate(data))


def test_time_extreme_preserves_25_points_but_requires_review(risk_cases):
    result = _evaluate(risk_cases.request(values={"kst_hour": 20},
        cold_parameters={"amount_minor": dict(center=10000.0, scale=10000.0),
                         "kst_hour": dict(center=14.0, scale=6.0)}))
    decision = result.decision
    assert decision.behavior_score.value == 25.0
    assert (decision.risk_level, decision.recommendation) == ("MEDIUM", "REVIEW_ORDER")
    assert [rule.rule_id for rule in decision.rule_evidence] == ["maximum-time"]


@pytest.mark.parametrize("behavior,state,status,risk,advice", [
    ("NORMAL", "MATCH", "SUCCESS", "LOW", "NO_ADDITIONAL_REVIEW"),
    ("STRONG", "MATCH", "SUCCESS", "MEDIUM", "REVIEW_ORDER"),
    ("HIGH", "MATCH", "SUCCESS", "HIGH", "REQUIRE_AUTH"),
    ("MISSING", "MATCH", "INCOMPLETE", "UNKNOWN", None),
    ("NOT_RUN", "MATCH", "INCOMPLETE", "UNKNOWN", None),
    ("FAILED", "MATCH", "FAILED", "UNKNOWN", None),
    ("HIGH", "UNKNOWN", "INCOMPLETE", "UNKNOWN", None),
    ("HIGH", "FAILED", "FAILED", "UNKNOWN", None),
    ("HIGH", "NOT_RUN", "INCOMPLETE", "UNKNOWN", None),
])
def test_total_and_failure_precedence_with_project_policy(classification_cases, behavior, state, status, risk, advice):
    decision = _evaluate(classification_cases.request(behavior=behavior, state=state)).decision
    assert (decision.status, decision.risk_level, decision.recommendation) == (status, risk, advice)
    if behavior == "STRONG":
        assert decision.behavior_score.value == 40.0
        assert [rule.rule_id for rule in decision.rule_evidence] == ["maximum-amount"]
    elif behavior == "HIGH":
        assert decision.behavior_score.value == 75.0
