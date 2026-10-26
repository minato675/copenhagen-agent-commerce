import math

import pytest

from copenhagen_fds.contracts.risk_classification import RiskClassificationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.statuses import RiskLevel
from copenhagen_fds.risk_classification import classify_risk


@pytest.mark.parametrize("required", [True, False])
@pytest.mark.parametrize("state", ["MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"])
def test_intent_decision_table_keeps_uncertainty_and_failure(classification_cases, required, state):
    supplied = classification_cases.request(state=state, required=required)
    result = classify_risk(supplied)
    expected = "FAILED" if state == "FAILED" else (
        "INCOMPLETE" if required and state in {"UNKNOWN", "NOT_RUN"} else "SUCCESS")
    assert result.status == expected
    assert result.risk_level == ("LOW" if expected == "SUCCESS" else "UNKNOWN")
    assert result.threshold_risk == "LOW"
    assert result.evidence.intent.signal == state


@pytest.mark.parametrize("behavior,status,risk,threshold,score", [
    ("NORMAL", "SUCCESS", "LOW", "LOW", 0.0), ("MEDIUM", "SUCCESS", "MEDIUM", "MEDIUM", 35.0),
    ("HIGH", "SUCCESS", "HIGH", "HIGH", 75.0), ("STRONG", "SUCCESS", "HIGH", "MEDIUM", 40.0),
    ("COMPOUND", "SUCCESS", "HIGH", "MEDIUM", 45.0),
    ("MISSING", "INCOMPLETE", "UNKNOWN", "UNKNOWN", None),
    ("OPTIONAL_MISSING", "INCOMPLETE", "UNKNOWN", "UNKNOWN", None),
    ("NOT_RUN", "INCOMPLETE", "UNKNOWN", "UNKNOWN", None),
    ("FAILED", "FAILED", "UNKNOWN", "MEDIUM", 42.5),
])
def test_behavior_decision_table_and_match_never_hide_failure(classification_cases, behavior, status, risk, threshold, score):
    supplied = classification_cases.request(behavior=behavior)
    result = classify_risk(supplied)
    total = result.evidence.rules.aggregation.behavior_score
    assert (result.status, result.risk_level, result.threshold_risk) == (status, risk, threshold)
    assert (None if total is None else total.value) == score
    if behavior == "OPTIONAL_MISSING":
        assert result.evidence.rules.aggregation.assessment.coverage.evaluation_scope == "FULL"
    if behavior == "FAILED":
        assert result.evidence.rules.minimum_risk == "HIGH"
        assert result.evidence.rules.aggregation.assessment.failures


@pytest.mark.parametrize("medium,high,risk", [
    (35.0, 70.0, "MEDIUM"), (math.nextafter(35.0, math.inf), 70.0, "LOW"),
    (10.0, 35.0, "HIGH"), (10.0, math.nextafter(35.0, math.inf), "MEDIUM"),
])
def test_threshold_boundaries_are_inclusive_without_rounding(classification_cases, medium, high, risk):
    data = classification_cases.request(behavior="MEDIUM").model_dump()
    data["policy"]["thresholds"] = dict(medium_from=medium, high_from=high)
    result = classify_risk(RiskPolicyInput.model_validate(data))
    assert result.evidence.rules.aggregation.behavior_score.value == 35.0
    assert result.risk_level == result.threshold_risk == risk


@pytest.mark.parametrize("behavior,state,expected", [
    ("HIGH", "UNKNOWN", "INCOMPLETE"), ("HIGH", "FAILED", "FAILED"),
    ("FAILED", "UNKNOWN", "FAILED"), ("MISSING", "FAILED", "FAILED"),
    ("NOT_RUN", "NOT_RUN", "INCOMPLETE"),
])
def test_failure_and_missing_precede_known_risk(classification_cases, behavior, state, expected):
    result = classify_risk(classification_cases.request(behavior=behavior, state=state))
    assert result.status == expected and result.risk_level == "UNKNOWN"
    if behavior in {"HIGH", "FAILED"}:
        assert result.evidence.rules.minimum_risk == "HIGH"


@pytest.mark.parametrize("state", ["MATCH", "MISMATCH", "UNKNOWN", "NOT_RUN"])
def test_optional_intent_never_discounts_high_behavior(classification_cases, state):
    result = classify_risk(classification_cases.request(behavior="HIGH", state=state, required=False))
    assert result.status == "SUCCESS" and result.risk_level == "HIGH"
    assert result.evidence.rules.aggregation.behavior_score.value == 75.0


def test_classification_replay_and_tamper_rejection(classification_cases):
    supplied = classification_cases.request(behavior="STRONG")
    result = classify_risk(supplied)
    assert RiskClassificationResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    assert classify_risk(RiskPolicyInput.model_validate_json(supplied.model_dump_json())) == result
    with pytest.raises(ValueError, match="observed risk floors"):
        result.model_copy(update={"risk_level": RiskLevel.LOW}).validate_for_input(supplied)
