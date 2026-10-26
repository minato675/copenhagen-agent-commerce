"""S15P21A207-291: native assembly retains the existing decision table and all provenance."""

from datetime import timedelta

import pytest

from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.engine_finalization import finalize_engine_execution
from copenhagen_fds.risk_evaluation import evaluate_risk_policy


@pytest.mark.parametrize("signal,status", [("MATCH", "SUCCESS"), ("MISMATCH", "SUCCESS"),
    ("UNKNOWN", "INCOMPLETE"), ("NOT_RUN", "INCOMPLETE"),
    ("EXTRACTION_FAILED", "FAILED"), ("COMPARISON_FAILED", "FAILED")])
def test_existing_decision_table_and_original_components_survive(final_cases, signal, status):
    case = final_cases.execute(signal=signal)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    assert result.policy_result == evaluate_risk_policy(result.policy_input)
    assert result.outcome.status == status and result.execution == case.execution
    assert result.policy_stage.status == "SUCCESS"  # Calculation success is not a normal trade verdict.
    decision = result.policy_result.decision
    assert result.outcome.risk_level == decision.risk_level
    assert result.outcome.recommendation == decision.recommendation
    assert result.outcome.review_actions == decision.review_actions
    if status != "SUCCESS":
        assert result.outcome.risk_level == "UNKNOWN" and result.outcome.recommendation is None
    if signal == "EXTRACTION_FAILED":
        assert result.policy_input.intent.failure == case.execution.extraction.failure
        assert result.execution.comparison.status == "NOT_RUN"
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()).validate_for_request(case.request) == result


@pytest.mark.parametrize("profile", [False, True])
def test_gpt_match_does_not_cancel_high_behavior_or_floor_rule(final_cases, profile):
    case = final_cases.execute(amount=500000, profile=profile)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    assert result.outcome.status == "SUCCESS" and result.outcome.risk_level == "HIGH"
    assert result.outcome.recommendation == "REQUIRE_AUTH"
    assert result.policy_result.decision.rule_evidence
    assert result.policy_input.intent.payload.comparison == "MATCH"
    originals = {f.feature_id: f for f in case.execution.behavior.evaluation.result.behavior.features}
    for group in result.policy_result.decision.groups:
        assert all(row.feature == originals[row.feature.feature_id] for row in group.features)
    assert result.policy_input.scoring_input == case.execution.behavior.scoring_input
    assert result.policy_input.behavior == case.execution.behavior.evaluation


@pytest.mark.parametrize("signal", ["MATCH", "MISMATCH", "EXTRACTION_FAILED"])
def test_missing_scores_remain_null_and_failure_takes_priority(final_cases, signal):
    case = final_cases.execute(signal=signal, missing=True)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    assert result.outcome.status == ("FAILED" if signal.endswith("FAILED") else "INCOMPLETE")
    assert result.outcome.risk_level == "UNKNOWN" and result.outcome.recommendation is None
    assert result.policy_result.decision.behavior_score is None
    assert any(row.feature.score is None and row.weighted_points is None
        for g in result.policy_result.decision.groups for row in g.features)


@pytest.mark.parametrize("signal", ["UNKNOWN", "NOT_RUN"])
def test_optional_intent_still_requires_verification_without_approval(final_cases, signal):
    case = final_cases.execute(signal=signal, required=False)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    assert result.outcome.status == "SUCCESS" and result.outcome.risk_level == "LOW"
    assert result.outcome.recommendation == "VERIFY_USER_INTENT"


@pytest.mark.parametrize("signal", ["MATCH", "EXTRACTION_FAILED"])
def test_calculation_fallback_failure_is_not_hidden_by_a_usable_score(final_cases, monkeypatch, signal):
    import copenhagen_fds.personal_amount as amount
    def fail(*args, **kwargs):
        raise ArithmeticError("private-calculation-detail")
    monkeypatch.setattr(amount, "_calculation", fail)
    case = final_cases.execute(profile=True, amount=100000, signal=signal)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    assert result.outcome.status == "FAILED" and result.outcome.risk_level == "UNKNOWN"
    assert result.execution.behavior.evaluation.result.features[0].combined.score is not None
    codes = {r.code for r in result.policy_result.decision.reasons}
    assert "EVALUATION_FAILED" in codes
    assert ("INTENT_FAILED" in codes) == (signal == "EXTRACTION_FAILED")
    assert "private-calculation-detail" not in result.model_dump_json()


def test_restore_checks_provenance_without_rerunning_models_or_policy(final_cases, monkeypatch):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.engine_finalization as finalization
    import copenhagen_fds.personal_amount as amount
    case = final_cases.execute(profile=True)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    def forbidden(*args, **kwargs):
        pytest.fail("restoration reran an evaluator")
    for owner, name in [(engine, "evaluate_engine_behavior"), (engine.IntentAnalyzer, "extract"),
        (engine.IntentAnalyzer, "compare_extraction"), (amount, "_calculation"), (finalization, "evaluate_risk_policy")]:
        monkeypatch.setattr(owner, name, forbidden)
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()) == result
    assert "합성 구매 요청" not in repr(result)


@pytest.mark.parametrize("change", ["risk", "recommendation", "status", "trace", "profile", "policy", "time", "elapsed", "failure"])
def test_final_result_rejects_changed_decision_provenance_and_time(final_cases, change):
    case = final_cases.execute(profile=True)
    result = finalize_engine_execution(case.execution, budget=case.budget)
    data = result.model_dump()
    if change == "risk":
        data["outcome"]["risk_level"] = "UNKNOWN"
    elif change == "recommendation":
        data["outcome"]["recommendation"] = "ALLOW"
    elif change == "status":
        data["outcome"]["status"] = "FAILED"
    elif change == "trace":
        data["execution"]["reference"]["trace_id"] = "other"
    elif change == "profile":
        data["policy_input"]["scoring_input"]["personal"]["available_at"] -= timedelta(seconds=1)
    elif change == "policy":
        data["policy_input"]["policy"]["version"] = "other"
    elif change == "time":
        data["policy_stage"]["started_at"] = result.execution.started_at - timedelta(seconds=1)
    elif change == "elapsed":
        data["elapsed_seconds"] = 10.0
    else:
        data["policy_stage"].update(status="FAILED", failure_code="INTERNAL_ERROR")
    with pytest.raises(ValueError):
        NativeEvaluationResult.model_validate(data)
