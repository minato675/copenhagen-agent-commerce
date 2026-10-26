import math
from copy import deepcopy
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput
from copenhagen_fds.contracts.risk_results import GroupContribution, NativeFraudDecision


def score(value):
    return dict(value=value, unit="BEHAVIOR_ANOMALY_0_100")


def decision_payload(supplied):
    """Hand-assembled contract example, not the future production classifier."""
    original = {f.feature_id: f for f in supplied.behavior.result.behavior.features} if supplied.behavior else {}
    groups = []
    for setting in supplied.policy.groups:
        features = [original.get(f.feature_id) for f in setting.features]
        complete = all(f is not None and f.score is not None for f in features)
        maximum = max(f.score.value for f in features) if complete else None
        winner = next((f.feature_id for f in features if f.score.value == maximum), None) if complete else None
        groups.append(dict(group_id=setting.group_id, weight=setting.weight,
            score=score(maximum) if complete else None, weighted_points=maximum * setting.weight if complete else None,
            features=[dict(feature=f or dict(feature_id=member.feature_id, status="NOT_RUN", reasons=["NOT_EXECUTED"]),
                           weighted_points=(maximum * setting.weight if f.feature_id == winner else 0.0) if complete else None)
                      for member, f in zip(setting.features, features)]))
    available = sum(f is not None and f.score is not None for f in original.values())
    complete = available == 9
    return dict(input_reference=RiskInputReference.from_input(supplied), policy=supplied.policy,
        policy_sha256=personalization_hash(supplied.policy), evaluated_at=supplied.evaluated_at,
        status="SUCCESS" if complete else "INCOMPLETE",
        evaluation_scope="FULL" if complete else "PARTIAL" if available else "UNAVAILABLE",
        score_coverage="COMPLETE" if complete else "PARTIAL" if available else "UNAVAILABLE",
        groups=groups, behavior_score=score(math.fsum(g["weighted_points"] for g in groups)) if complete else None,
        risk_level="LOW" if complete else "UNKNOWN", recommendation="NO_ADDITIONAL_REVIEW" if complete else None,
        reasons=[dict(code="WITHIN_POLICY" if complete else "INSUFFICIENT_DATA")],
        review_actions=[] if complete else ["CHECK_INPUT"])


def test_zero_score_roundtrip_and_legacy_independence(risk_cases):
    supplied = risk_cases.request()
    decision = NativeFraudDecision.model_validate(decision_payload(supplied)).validate_for_input(supplied)
    assert decision.behavior_score.value == 0.0
    assert decision.status == "SUCCESS" and decision.risk_level == "LOW"
    assert decision.input_reference.behavior_sha256 is not None
    restored = NativeFraudDecision.model_validate_json(decision.model_dump_json())
    assert restored.validate_for_input(supplied) == decision
    assert "sift" not in type(decision).model_fields
    assert "SiftFraudResult" not in str(NativeFraudDecision.model_json_schema())


@pytest.mark.parametrize("states", [{"amount_minor": "NOT_COLLECTED"},
                                    {"amount_minor": "INSUFFICIENT_HISTORY"},
                                    {"purchase_count_10m": "NOT_APPLICABLE"}])
def test_partial_scores_preserved_but_total_is_null(risk_cases, states):
    supplied = risk_cases.request(states=states)
    decision = NativeFraudDecision.model_validate(decision_payload(supplied)).validate_for_input(supplied)
    assert decision.score_coverage == "PARTIAL" and decision.status == "INCOMPLETE"
    assert decision.behavior_score is None and decision.risk_level == "UNKNOWN"
    assert any(g.score is not None and g.score.value == 0 for g in decision.groups)
    data = decision.model_dump()
    data["behavior_score"] = score(0.0)
    with pytest.raises(ValueError, match="missing is never zero"):
        NativeFraudDecision.model_validate(data)


def test_unavailable_and_not_run_have_no_fake_scores(risk_cases):
    supplied = risk_cases.request()
    data = supplied.model_dump()
    data["behavior"] = None
    supplied = RiskPolicyInput.model_validate(data)
    payload = decision_payload(supplied)
    unavailable = NativeFraudDecision.model_validate(payload).validate_for_input(supplied)
    assert unavailable.evaluation_scope == "UNAVAILABLE" and unavailable.behavior_score is None
    payload.update(status="NOT_RUN", evaluated_at=None, groups=[], reasons=[dict(code="NOT_EXECUTED")],
                   review_actions=["RETRY_EVALUATION"])
    unexecuted = NativeFraudDecision.model_validate(payload).validate_for_input(supplied)
    assert NativeFraudDecision.model_validate_json(unexecuted.model_dump_json()) == unexecuted


@pytest.mark.parametrize("field,value", [("value", -1), ("value", 101), ("value", float("nan")),
                                        ("value", float("inf")), ("value", True), ("unit", "FRAUD_PROBABILITY")])
def test_nonfinite_out_of_range_and_wrong_score_units_rejected(risk_cases, field, value):
    data = decision_payload(risk_cases.request())
    data["behavior_score"][field] = value
    with pytest.raises(ValueError):
        NativeFraudDecision.model_validate(data)


@pytest.mark.parametrize("change", ["total", "weight", "group_score", "points", "coverage", "scope", "risk", "advice", "time", "version", "hash"])
def test_contradictory_result_rejected(risk_cases, change):
    data = decision_payload(risk_cases.request())
    if change == "total":
        data["behavior_score"] = score(1.0)
    elif change == "weight":
        data["groups"][0]["weight"] = 0.5
    elif change == "group_score":
        data["groups"][0]["score"] = score(1.0)
    elif change == "points":
        data["groups"][0]["features"][0]["weighted_points"] = 1.0
    elif change == "coverage":
        data["score_coverage"] = "PARTIAL"
    elif change == "scope":
        data["evaluation_scope"] = "UNAVAILABLE"
    elif change == "risk":
        data["risk_level"] = "UNKNOWN"
    elif change == "advice":
        data["recommendation"] = "REQUIRE_AUTH"
    elif change == "time":
        data["evaluated_at"] += timedelta(seconds=1)
    elif change == "version":
        data["schema_version"] = "unsupported-v2"
    else:
        data["policy_sha256"] = "0" * 64
    with pytest.raises(ValueError):
        NativeFraudDecision.model_validate(data)


def test_policy_and_input_content_changes_break_result_binding(risk_cases):
    supplied = risk_cases.request()
    decision = NativeFraudDecision.model_validate(decision_payload(supplied))
    data = supplied.model_dump()
    data["policy"]["version"] = "changed-policy"
    with pytest.raises(ValueError, match="exact input"):
        decision.validate_for_input(RiskPolicyInput.model_validate(data))
    data = supplied.model_dump()
    data["policy"]["intent_max_age_seconds"] = 299  # Same named version, different configuration.
    with pytest.raises(ValueError, match="exact input"):
        decision.validate_for_input(RiskPolicyInput.model_validate(data))


def test_failed_execution_can_retain_complete_coverage(risk_cases):
    supplied = risk_cases.request(intent_state="FAILED")
    payload = decision_payload(supplied)
    payload.update(status="FAILED", risk_level="UNKNOWN", recommendation=None,
                   reasons=[dict(code="INTENT_FAILED")], review_actions=["RETRY_EVALUATION"])
    decision = NativeFraudDecision.model_validate(payload).validate_for_input(supplied)
    assert decision.score_coverage == "COMPLETE" and decision.behavior_score.value == 0
    data = decision_payload(supplied)
    with pytest.raises(ValueError, match="component execution failure"):
        NativeFraudDecision.model_validate(data).validate_for_input(supplied)


def test_cold_fallback_failure_preserved_without_replaying_calculation(risk_cases, monkeypatch):
    import copenhagen_fds.personal_amount as module

    with monkeypatch.context() as patch:
        def fail(*args):
            raise ArithmeticError("injected failure")
        patch.setattr(module, "_calculation", fail)
        supplied = risk_cases.request(profile=True)
    data = decision_payload(supplied)
    data.update(status="FAILED", risk_level="UNKNOWN", recommendation=None,
                reasons=[dict(code="EVALUATION_FAILED")], review_actions=["RETRY_EVALUATION"])
    result = NativeFraudDecision.model_validate(data).validate_for_input(supplied)
    assert result.score_coverage == "COMPLETE" and result.behavior_score is not None
    assert NativeFraudDecision.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result


@pytest.mark.parametrize("meaning,code", [("UNKNOWN", "INTENT_UNKNOWN"), ("NOT_RUN", "INTENT_NOT_RUN")])
def test_required_unknown_intent_is_incomplete_not_normal(risk_cases, meaning, code):
    supplied = risk_cases.request(intent_state=meaning)
    data = decision_payload(supplied)
    data.update(status="INCOMPLETE", risk_level="UNKNOWN", recommendation=None,
                reasons=[dict(code=code)], review_actions=["VERIFY_USER_INTENT"])
    assert NativeFraudDecision.model_validate(data).validate_for_input(supplied).behavior_score.value == 0


def test_known_mismatch_requires_review_without_changing_behavior_score(risk_cases):
    supplied = risk_cases.request(intent_state="MISMATCH")
    data = decision_payload(supplied)
    data.update(recommendation="VERIFY_USER_INTENT", reasons=[dict(code="INTENT_MISMATCH")],
                review_actions=["VERIFY_USER_INTENT"])
    decision = NativeFraudDecision.model_validate(data).validate_for_input(supplied)
    assert decision.behavior_score.value == 0
    data.update(recommendation="NO_ADDITIONAL_REVIEW", review_actions=[])
    with pytest.raises(ValueError):
        NativeFraudDecision.model_validate(data)


def test_rule_floor_and_hand_calculated_contribution(risk_cases):
    supplied = risk_cases.request(cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)})
    data = decision_payload(supplied)
    data.update(risk_level="HIGH", recommendation="REQUIRE_AUTH", review_actions=["REQUIRE_AUTH"],
        reasons=[dict(code="RULE_TRIGGERED", rule_id="strong-amount")],
        rule_evidence=[dict(rule_id="strong-amount", minimum_risk="HIGH",
                            observations=[dict(group_id="AMOUNT", score=score(100.0))])])
    decision = NativeFraudDecision.model_validate(data).validate_for_input(supplied)
    assert decision.groups[0].weighted_points == 40.0 and decision.behavior_score.value == 40.0
    data["risk_level"] = "MEDIUM"
    with pytest.raises(ValueError, match="rule floor"):
        NativeFraudDecision.model_validate(data)
    data["risk_level"] = "HIGH"
    data["rule_evidence"][0]["observations"][0]["score"] = score(89.0)
    with pytest.raises(ValueError, match="qualifying group"):
        NativeFraudDecision.model_validate(data)


def test_changed_feature_evidence_cannot_rebind(risk_cases):
    supplied = risk_cases.request()
    data = decision_payload(supplied)
    data["groups"][0]["features"][0]["feature"] = data["groups"][0]["features"][0]["feature"].model_dump()
    data["groups"][0]["features"][0]["feature"]["bases"][0]["policy_version"] = "different-cold-policy"
    with pytest.raises(ValueError, match="original final feature"):
        NativeFraudDecision.model_validate(data).validate_for_input(supplied)


def test_incomplete_cannot_be_claimed_for_fully_resolved_input(risk_cases):
    supplied = risk_cases.request()
    data = decision_payload(supplied)
    data.update(status="INCOMPLETE", risk_level="UNKNOWN", recommendation=None,
                reasons=[dict(code="INSUFFICIENT_DATA")], review_actions=["CHECK_INPUT"])
    with pytest.raises(ValueError, match="unavailable score"):
        NativeFraudDecision.model_validate(data).validate_for_input(supplied)


def test_max_frequency_does_not_count_six_signals_six_times(risk_cases):
    supplied = risk_cases.request(values={"purchase_count_10m": 1, "purchase_count_1h": 1,
                                         "purchase_count_24h": 1})
    data = decision_payload(supplied)
    data.update(risk_level="MEDIUM", recommendation="REVIEW_ORDER", review_actions=["REVIEW_ORDER"],
                reasons=[dict(code="BEHAVIOR_DEVIATION")])
    decision = NativeFraudDecision.model_validate(data).validate_for_input(supplied)
    assert decision.groups[1].score.value == 100.0 and decision.behavior_score.value == 35.0
    duplicated = deepcopy(data["groups"][1])
    duplicated["features"][1]["weighted_points"] = 35.0
    with pytest.raises(ValueError, match="sum to their group"):
        GroupContribution.model_validate(duplicated)


def test_match_cannot_lower_risk_below_behavior_threshold(risk_cases):
    supplied = risk_cases.request(values={"purchase_count_10m": 1})
    data = decision_payload(supplied)  # 35 behavior points, but the fixture claims LOW.
    with pytest.raises(ValueError, match="cannot discount"):
        NativeFraudDecision.model_validate(data).validate_for_input(supplied)


def test_optional_missing_feature_keeps_full_scope_but_no_total(risk_cases):
    data = risk_cases.request(states={"kst_day_of_week": "NOT_COLLECTED"}).model_dump()
    data["policy"]["groups"][2]["features"][1]["required"] = False
    supplied = RiskPolicyInput.model_validate(data)
    payload = decision_payload(supplied)
    payload["evaluation_scope"] = "FULL"
    result = NativeFraudDecision.model_validate(payload).validate_for_input(supplied)
    assert result.status == "INCOMPLETE" and result.score_coverage == "PARTIAL"
    assert result.behavior_score is None


def test_positive_underflow_is_not_relabelled_as_normal_zero(risk_cases):
    data = decision_payload(risk_cases.request())["groups"][0]
    feature = data["features"][0]["feature"].model_dump()
    feature.update(score=score(math.nextafter(0.0, 1.0)), reasons=["PATTERN_DEVIATION"])
    data.update(score=feature["score"], weighted_points=math.nextafter(0.0, 1.0))
    data["features"] = [dict(feature=feature, weighted_points=math.nextafter(0.0, 1.0))]
    assert GroupContribution.model_validate(data).weighted_points > 0
    data["weighted_points"] = data["features"][0]["weighted_points"] = 0.0
    with pytest.raises(ValueError, match="weighted points"):
        GroupContribution.model_validate(data)
