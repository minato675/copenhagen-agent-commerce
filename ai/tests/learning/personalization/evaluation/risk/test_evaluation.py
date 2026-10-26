from datetime import timedelta

import pytest

from copenhagen_fds.contracts.risk_evaluation import RiskEvaluationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_evaluation import evaluate_risk_policy


@pytest.mark.parametrize("behavior,state,required,recommendation,actions", [
    ("NORMAL", "MATCH", True, "NO_ADDITIONAL_REVIEW", ()),
    ("MEDIUM", "MATCH", True, "REVIEW_ORDER", ("REVIEW_ORDER",)),
    ("STRONG", "MATCH", True, "REQUIRE_AUTH", ("REQUIRE_AUTH",)),
    ("COMPOUND", "MISMATCH", True, "REQUIRE_AUTH", ("REVIEW_ORDER", "VERIFY_USER_INTENT", "REQUIRE_AUTH")),
    ("NORMAL", "MISMATCH", True, "REVIEW_ORDER", ("REVIEW_ORDER", "VERIFY_USER_INTENT")),
    ("NORMAL", "UNKNOWN", True, None, ("CHECK_INPUT", "VERIFY_USER_INTENT")),
    ("NORMAL", "UNKNOWN", False, "VERIFY_USER_INTENT", ("CHECK_INPUT", "VERIFY_USER_INTENT")),
    ("NORMAL", "NOT_RUN", False, "VERIFY_USER_INTENT", ("RETRY_EVALUATION", "VERIFY_USER_INTENT")),
    ("HIGH", "UNKNOWN", False, "REQUIRE_AUTH", ("CHECK_INPUT", "VERIFY_USER_INTENT", "REQUIRE_AUTH")),
    ("NORMAL", "FAILED", False, None, ("RETRY_EVALUATION",)),
    ("MISSING", "MATCH", True, None, ("CHECK_INPUT",)),
    ("FAILED", "MATCH", True, None, ("RETRY_EVALUATION",)),
])
def test_final_advice_decision_table(classification_cases, behavior, state, required, recommendation, actions):
    supplied = classification_cases.request(behavior=behavior, state=state, required=required)
    result = evaluate_risk_policy(supplied)
    assert result.decision.recommendation == recommendation and result.decision.review_actions == actions
    assert result.decision.status == result.classification.status
    assert result.decision.risk_level == result.classification.risk_level
    result.validate_for_input(supplied)
    assert RiskEvaluationResult.model_validate_json(result.model_dump_json()) == result


def test_score_contribution_rule_gpt_and_versions_trace_to_original(classification_cases):
    supplied = classification_cases.request(behavior="HIGH", state="MISMATCH", detailed=True)
    result = evaluate_risk_policy(supplied)
    decision = result.decision
    evidence = result.classification.evidence
    assert decision.behavior_score.value == 75.0
    assert [g.weighted_points for g in decision.groups] == [40.0, 35.0, 0.0]
    for group in decision.groups:
        assert sum(row.weighted_points for row in group.features) == group.weighted_points
    assert decision.rule_evidence == evidence.rules.rule_evidence
    assert decision.rule_evidence[0].rule_id == "strong-amount"
    assert evidence.intent.source == supplied.intent
    assert evidence.intent.source.payload.confirmation_requests
    assert decision.input_reference == evidence.intent.input_reference
    assert decision.policy == supplied.policy and decision.policy.version == "fixture-risk-v1"
    assert decision.policy.validation_status == "UNVALIDATED"
    assert {r.code for r in decision.reasons} == {"BEHAVIOR_DEVIATION", "RULE_TRIGGERED", "INTENT_MISMATCH"}
    deviations = [r.feature_id for r in decision.reasons if r.code == "BEHAVIOR_DEVIATION"]
    assert "amount_minor" in deviations and "purchase_count_10m" in deviations


def test_unresolved_result_preserves_each_missing_feature_and_gpt_reason(classification_cases):
    result = evaluate_risk_policy(classification_cases.request(behavior="MISSING", state="UNKNOWN"))
    assert result.decision.behavior_score is None and result.decision.recommendation is None
    assert [(r.group_id, r.feature_id) for r in result.decision.reasons if r.code == "INSUFFICIENT_DATA"] == [
        ("TIME", "kst_day_of_week")]
    assert "INTENT_UNKNOWN" in {r.code for r in result.decision.reasons}


def test_both_failures_preserve_cold_fallback_and_ordered_unique_actions(classification_cases):
    supplied = classification_cases.request(behavior="FAILED", state="FAILED",
        failure=dict(kind="API_ERROR", api_error="AUTHENTICATION", retryable=False, http_status=401))
    result = evaluate_risk_policy(supplied)
    decision = result.decision
    assert decision.status == "FAILED" and decision.risk_level == "UNKNOWN"
    assert [group.weighted_points for group in decision.groups] == [40.0, 0.0, 2.5]
    assert decision.behavior_score.value == 42.5 and decision.rule_evidence
    assert {"EVALUATION_FAILED", "INTENT_FAILED", "RULE_TRIGGERED"} <= {r.code for r in decision.reasons}
    assert decision.review_actions == ("CHECK_INPUT", "RETRY_EVALUATION")
    assert result.classification.evidence.rules.aggregation.assessment.cold_fallback_features == ("amount_minor",)
    assert result.classification.evidence.intent.source.failure.http_status == 401
    assert RiskEvaluationResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result


def test_policy_execution_is_incomplete_when_both_components_not_run(classification_cases):
    supplied = classification_cases.request(behavior="NOT_RUN", state="NOT_RUN")
    result = evaluate_risk_policy(supplied)
    assert result.decision.status == "INCOMPLETE" and result.decision.evaluated_at == supplied.evaluated_at
    assert len(result.decision.groups) == 3 and result.decision.score_coverage == "UNAVAILABLE"
    assert {"NOT_EXECUTED", "INTENT_NOT_RUN", "INSUFFICIENT_DATA"} <= {r.code for r in result.decision.reasons}
    assert result.decision.review_actions == ("CHECK_INPUT", "RETRY_EVALUATION")


def test_within_policy_is_advice_without_payment_authority(classification_cases):
    result = evaluate_risk_policy(classification_cases.request())
    assert [r.code for r in result.decision.reasons] == ["WITHIN_POLICY"]
    assert result.decision.recommendation == "NO_ADDITIONAL_REVIEW"
    assert not {"approved", "authorized", "payment_token", "payment_status"}.intersection(type(result.decision).model_fields)


def test_replay_is_pure_without_model_scorer_or_profile_update(classification_cases, monkeypatch):
    import copenhagen_fds.personal_evaluation as module
    from openai import OpenAI

    supplied = classification_cases.request(behavior="HIGH", state="MISMATCH", profile=True, detailed=True)
    before = supplied.model_dump_json()

    def forbidden(*args, **kwargs):
        pytest.fail("final policy cannot call a model or recalculate features")

    monkeypatch.setattr(module, "evaluate_personalized_features", forbidden)
    monkeypatch.setattr(OpenAI, "__init__", forbidden)
    result = evaluate_risk_policy(supplied)
    assert evaluate_risk_policy(RiskPolicyInput.model_validate_json(before)) == result
    assert RiskEvaluationResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("field", ["recommendation", "reasons", "groups", "rule_evidence", "version"])
def test_final_projection_tamper_rejected(classification_cases, field):
    supplied = classification_cases.request(behavior="HIGH", state="MISMATCH")
    result = evaluate_risk_policy(supplied)
    data = result.model_dump()
    if field == "recommendation":
        data["decision"][field] = "VERIFY_USER_INTENT"  # Legal advice, but not this policy's representative.
    elif field == "reasons":
        data["decision"][field] = tuple(r for r in data["decision"][field] if r["code"] != "BEHAVIOR_DEVIATION")
    elif field == "groups":
        data["decision"][field][0]["features"][0]["weighted_points"] = 0.0
    elif field == "rule_evidence":
        data["decision"][field] = ()
    else:
        data["evaluation_version"] = "unsupported-v2"
    with pytest.raises(ValueError):
        RiskEvaluationResult.model_validate(data)


def test_result_rebinding_and_unchecked_model_copy_rejected(classification_cases):
    supplied = classification_cases.request()
    result = evaluate_risk_policy(supplied)
    with pytest.raises(ValueError):
        result.validate_for_input(classification_cases.request(required=False))
    with pytest.raises(ValueError):
        result.model_copy(update={"evaluation_version": "unsupported-v2"}).validate_for_input(supplied)


@pytest.mark.parametrize("change", ["expired", "other_order", "policy"])
def test_final_entry_revalidates_input(classification_cases, change):
    supplied = classification_cases.request()
    if change == "policy":
        supplied = supplied.model_copy(update={"policy": supplied.policy.model_copy(update={"intent_max_age_seconds": 0})})
    else:
        metadata = supplied.intent.metadata
        if change == "expired":
            metadata = metadata.model_copy(update={"evaluated_at": supplied.evaluated_at - timedelta(seconds=300)})
        else:
            order = metadata.target.order.model_copy(update={"version": "other-order-version"})
            metadata = metadata.model_copy(update={"target": metadata.target.model_copy(update={"order": order})})
        supplied = supplied.model_copy(update={"intent": supplied.intent.model_copy(update={"metadata": metadata})})
    with pytest.raises(ValueError):
        evaluate_risk_policy(supplied)
