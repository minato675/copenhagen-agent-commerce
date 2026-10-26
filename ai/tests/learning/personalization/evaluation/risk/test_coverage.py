import pytest

from copenhagen_fds.contracts.risk_coverage import RiskCoverageResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES
from copenhagen_fds.risk_coverage import evaluate_risk_coverage


@pytest.mark.parametrize("state,required,scope", [
    ("AVAILABLE", True, "FULL"), ("NOT_COLLECTED", True, "PARTIAL"),
    ("NOT_COLLECTED", False, "FULL"), ("INSUFFICIENT_HISTORY", True, "PARTIAL"),
    ("INSUFFICIENT_HISTORY", False, "FULL"), ("NOT_APPLICABLE", True, "PARTIAL"),
    ("NOT_APPLICABLE", False, "FULL"),
])
def test_required_optional_decision_table(risk_cases, state, required, scope):
    data = risk_cases.request(states={"amount_minor": state}).model_dump()
    data["policy"]["groups"][0]["features"][0]["required"] = required
    supplied = RiskPolicyInput.model_validate(data)
    result = evaluate_risk_coverage(supplied)
    assert result.evaluation_scope == scope
    assert result.score_coverage == ("COMPLETE" if state == "AVAILABLE" else "PARTIAL")
    assert result.can_aggregate == (state == "AVAILABLE")
    assert result.behavior_score is None  # This step never computes the total.
    row = result.features[0]
    assert row.raw.status == state and row.source == "RESULT"
    assert row.feature == supplied.behavior.result.features[0].combined
    if state == "AVAILABLE":
        assert row.feature.score.value == 0
        assert result.required_unavailable == result.optional_unavailable == ()
    else:
        assert row.feature.score is None
        assert (result.required_unavailable if required else result.optional_unavailable) == ("amount_minor",)
        assert all(row.feature.score.value == 0 for row in result.features[1:])


def test_missing_reference_decision_table(risk_cases):
    result = evaluate_risk_coverage(risk_cases.request(cold=False))
    assert result.evaluation_scope == "UNAVAILABLE" and result.score_coverage == "UNAVAILABLE"
    assert not result.can_aggregate and len(result.required_unavailable) == 9
    assert all(row.feature.status == "INSUFFICIENT_DATA" for row in result.features)
    assert all(row.feature.reasons == ("REFERENCE_UNAVAILABLE",) for row in result.features)


def test_omitted_features_do_not_shrink_policy_denominator(risk_cases):
    supplied = risk_cases.request(requested=("amount_minor",))
    assert supplied.behavior.result.behavior.coverage == "COMPLETE"  # Complete for only one requested feature.
    result = evaluate_risk_coverage(supplied)
    expected_ids = tuple(name for names in GROUP_FEATURES.values() for name in names)
    assert tuple(row.feature.feature_id for row in result.features) == expected_ids
    assert result.score_coverage == "PARTIAL" and not result.can_aggregate
    assert result.unrequested_features == expected_ids[1:]
    assert result.required_unavailable == expected_ids[1:]
    for row in result.features[1:]:
        assert not row.requested and row.source == "NOT_REQUESTED" and row.raw is None
        assert row.feature.status == "NOT_RUN" and row.feature.score is None


def test_behavior_not_run_preserves_raw_input_without_fabricating_score(risk_cases):
    data = risk_cases.request(requested=("amount_minor",)).model_dump()
    data["behavior"] = None
    result = evaluate_risk_coverage(RiskPolicyInput.model_validate(data))
    first = result.features[0]
    assert first.requested and first.source == "BEHAVIOR_NOT_RUN"
    assert first.raw.value == 10000 and first.feature.score is None
    assert result.score_coverage == "UNAVAILABLE" and not result.can_aggregate
    assert all(row.source == "NOT_REQUESTED" for row in result.features[1:])


def test_roundtrip_and_repeat_preserve_input_without_mutation(risk_cases):
    supplied = risk_cases.request(profile=True, states={"kst_hour": "NOT_COLLECTED"})
    before = supplied.model_dump_json()
    first = evaluate_risk_coverage(supplied)
    restored = RiskCoverageResult.model_validate_json(first.model_dump_json())
    assert restored.validate_for_input(supplied) == first == evaluate_risk_coverage(supplied)
    assert first.model_dump_json() == evaluate_risk_coverage(supplied).model_dump_json()
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("field,value", [("can_aggregate", True), ("score_coverage", "COMPLETE"),
    ("evaluation_scope", "FULL"), ("required_unavailable", ()), ("behavior_score", 0.0)])
def test_missing_score_state_tamper_rejected(risk_cases, field, value):
    data = evaluate_risk_coverage(risk_cases.request(states={"amount_minor": "NOT_COLLECTED"})).model_dump()
    data[field] = value
    with pytest.raises(ValueError):
        RiskCoverageResult.model_validate(data)


@pytest.mark.parametrize("change", ["requirement", "group", "raw", "duplicate", "source"])
def test_feature_evidence_tamper_rejected(risk_cases, change):
    data = evaluate_risk_coverage(risk_cases.request()).model_dump()
    row = data["features"][0]
    if change == "requirement":
        row["required"] = False
    elif change == "group":
        row["group_id"] = "TIME"
    elif change == "raw":
        row["raw"] = None
    elif change == "duplicate":
        data["features"] = (row,) * 9
    else:
        row["source"] = "BEHAVIOR_NOT_RUN"
    with pytest.raises(ValueError):
        RiskCoverageResult.model_validate(data)


def test_binding_rejects_different_policy_content_and_bypassed_request(risk_cases):
    supplied = risk_cases.request()
    result = evaluate_risk_coverage(supplied)
    changed = supplied.model_dump()
    changed["policy"]["intent_max_age_seconds"] += 1  # Same version label, different policy content.
    with pytest.raises(ValueError, match="exact request"):
        result.validate_for_input(RiskPolicyInput.model_validate(changed))
    wrong_request = supplied.request.model_copy(update={
        "context": supplied.request.context.model_copy(update={"user_id": "different-user"})})
    with pytest.raises(ValueError):
        evaluate_risk_coverage(supplied.model_copy(update={"request": wrong_request}))


def test_reduced_raw_observation_availability_never_enables_aggregation(risk_cases):
    complete = evaluate_risk_coverage(risk_cases.request())
    missing = evaluate_risk_coverage(risk_cases.request(states={"attempt_count_24h": "INSUFFICIENT_HISTORY"}))
    assert complete.can_aggregate and not missing.can_aggregate
    assert missing.required_unavailable == ("attempt_count_24h",)
    assert missing.features[6].raw.value is None and missing.features[6].feature.score is None
