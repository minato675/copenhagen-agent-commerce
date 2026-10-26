"""S15P21A207-291: native entry point, legacy compatibility and interruption precedence."""

import asyncio
from datetime import timedelta

import pytest

from copenhagen_fds.contracts import FraudDecision, SiftFraudResult
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.engine_finalization import finalize_engine_execution
from copenhagen_fds.native_evaluation import NativeEvaluationCancelled, evaluate_fraud


@pytest.mark.parametrize("signal", ["MATCH", "UNKNOWN", "EXTRACTION_FAILED", "NOT_RUN"])
def test_native_entrypoint_needs_no_sift_or_loaders_and_runs_each_stage_once(final_cases, run_async, monkeypatch, signal):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.engine_finalization as finalization
    import copenhagen_fds.openai_settings as settings
    import copenhagen_fds.profile_updates as updates
    case = final_cases.prepare(signal=signal)
    calls = []
    behavior, policy = engine.evaluate_engine_behavior, finalization.evaluate_risk_policy
    def counted_behavior(request):
        calls.append("behavior")
        return behavior(request)
    def counted_policy(request):
        calls.append("policy")
        return policy(request)
    def forbidden(*args, **kwargs):
        pytest.fail("native evaluation touched a legacy service, loader or learning writer")
    for key in ("GMS_KEY", "SIFT_API_KEY", "SIFT_ACCOUNT_ID"):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setattr(SiftFraudResult, "__init__", forbidden)
    monkeypatch.setattr(settings, "load_openai_settings", forbidden)
    monkeypatch.setattr(updates, "update_profile", forbidden)
    monkeypatch.setattr(engine, "evaluate_engine_behavior", counted_behavior)
    monkeypatch.setattr(finalization, "evaluate_risk_policy", counted_policy)
    case.adapter.on_call = lambda stage: calls.append(stage)
    before = case.request.model_dump_json()
    result = run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
    expected = ["behavior"] + ([] if signal == "NOT_RUN" else ["extraction"] if signal.endswith("FAILED") else ["extraction", "comparison"]) + ["policy"]
    assert calls == expected and case.request.model_dump_json() == before
    assert all(budget is case.budget for _, budget in case.adapter.calls)
    assert "sift" not in result.model_dump_json().lower()
    assert result.outcome.recommendation not in {"ALLOW", "BLOCK"}
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()) == result


def test_legacy_public_contract_keeps_its_original_wire_format(decision_payload):
    result = FraudDecision.model_validate(decision_payload)
    restored = FraudDecision.model_validate_json(result.model_dump_json())
    assert restored == result and result.recommendation == "ALLOW"
    assert result.sift.score.unit == "SIFT_PAYMENT_ABUSE_0_1"
    assert "SIFT" in result.required_components
    with pytest.raises(ValueError):
        NativeEvaluationResult.model_validate(result.model_dump())


def test_default_entrypoint_creates_exactly_one_shared_budget(final_cases, run_async, monkeypatch):
    import copenhagen_fds.native_evaluation as evaluation
    case, starts = final_cases.prepare(), []
    def start(seconds):
        starts.append(seconds)
        return case.budget
    monkeypatch.setattr(evaluation.AnalysisBudget, "start", start)
    result = run_async(evaluate_fraud(case.request, case.adapter))
    assert starts == [case.request.execution.total_timeout_seconds]
    assert all(budget is case.budget for _, budget in case.adapter.calls)
    assert result.policy_result is not None and result.termination == "COMPLETED"


@pytest.mark.parametrize("error,code", [(ValueError, "INVALID_INPUT"), (RuntimeError, "INTERNAL_ERROR")])
def test_policy_failure_keeps_successful_upstream_and_sanitizes_error(final_cases, run_async, monkeypatch, error, code):
    import copenhagen_fds.engine_finalization as finalization
    case = final_cases.prepare()
    def fail(*args):
        raise error("private-policy-error")
    monkeypatch.setattr(finalization, "evaluate_risk_policy", fail)
    result = run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
    assert result.outcome.status == "FAILED" and result.outcome.risk_level == "UNKNOWN"
    assert result.policy_stage.failure_code == code and result.policy_result is None
    assert result.policy_input is not None and result.execution.comparison.status == "SUCCESS"
    assert "private-policy-error" not in result.model_dump_json()


@pytest.mark.parametrize("point", ["before_policy", "input_preparation", "policy", "validation"])
def test_same_budget_includes_final_preparation_calculation_and_validation(final_cases, run_async, monkeypatch, point):
    import copenhagen_fds.native_evaluation as evaluation
    import copenhagen_fds.engine_finalization as finalization
    import copenhagen_fds.contracts.native_evaluation as contract
    case = final_cases.prepare()
    calls = []
    policy = finalization.evaluate_risk_policy
    def counted(value):
        calls.append(True)
        result = policy(value)
        if point == "policy":
            case.now[0] = 10.0
        return result
    monkeypatch.setattr(finalization, "evaluate_risk_policy", counted)
    if point == "before_policy":
        original = evaluation.execute_engine
        async def expire(*args, **kwargs):
            result = await original(*args, **kwargs)
            case.now[0] = 10.0
            return result
        monkeypatch.setattr(evaluation, "execute_engine", expire)
    elif point in {"input_preparation", "validation"}:
        owner = finalization if point == "input_preparation" else contract
        original = owner.build_engine_intent_policy_input
        def expire(*args, **kwargs):
            result = original(*args, **kwargs)
            case.now[0] = 10.0
            return result
        monkeypatch.setattr(owner, "build_engine_intent_policy_input", expire)
    result = run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
    assert result.termination == "BUDGET_EXHAUSTED" and result.outcome.status == "FAILED"
    assert result.outcome.risk_level == "UNKNOWN" and result.outcome.recommendation is None
    assert len(calls) == (1 if point in {"policy", "validation"} else 0)
    assert result.execution.comparison.status == "SUCCESS"
    if calls:
        assert result.policy_result.decision.status == "SUCCESS"  # Historical policy only; outcome stays unresolved.
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()) == result


def test_partial_extraction_timeout_cannot_become_a_final_risk(final_cases, run_async, monkeypatch):
    import copenhagen_fds.engine as engine
    case = final_cases.prepare()
    original = engine.IntentAnalyzer.extract
    async def expire(self, *args, **kwargs):
        result = await original(self, *args, **kwargs)
        case.now[0] = 10.0
        return result
    monkeypatch.setattr(engine.IntentAnalyzer, "extract", expire)
    result = run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
    assert result.execution.extraction.status == "SUCCESS" and result.execution.comparison is None
    assert result.policy_stage.skip_reason == "BUDGET_EXHAUSTED" and result.policy_result is None
    assert result.outcome.status == "FAILED" and result.outcome.risk_level == "UNKNOWN"
    assert len(case.adapter.calls) == 1


@pytest.mark.parametrize("problem", ["expired", "behavior_error"])
def test_missing_execution_envelopes_do_not_fabricate_policy_input(final_cases, run_async, monkeypatch, problem):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.engine_finalization as finalization
    case = final_cases.prepare()
    if problem == "expired":
        case.now[0] = 10.0
    else:
        def fail(*args):
            raise RuntimeError("private-behavior-error")
        monkeypatch.setattr(engine, "evaluate_engine_behavior", fail)
    monkeypatch.setattr(finalization, "evaluate_risk_policy", lambda _: pytest.fail("unavailable evidence started policy"))
    result = run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
    assert result.execution.behavior is None and result.policy_input is result.policy_result is None
    assert result.outcome.status == "FAILED" and result.outcome.risk_level == "UNKNOWN"
    assert result.policy_stage.skip_reason == ("BUDGET_EXHAUSTED" if problem == "expired" else "UPSTREAM_FAILED")
    assert "private-behavior-error" not in result.model_dump_json()


@pytest.mark.parametrize("point", ["extraction", "comparison", "before_policy", "after_policy"])
def test_cancellation_remains_cancelled_and_retains_prior_evidence(final_cases, run_async, monkeypatch, point):
    import copenhagen_fds.native_evaluation as evaluation
    import copenhagen_fds.engine_finalization as finalization
    case = final_cases.prepare()
    if point in {"extraction", "comparison"}:
        original = case.adapter.complete
        async def cancel(prompt, *, budget):
            if prompt.stage == point:
                raise asyncio.CancelledError("private-cancellation-reason")
            return await original(prompt, budget=budget)
        case.adapter.complete = cancel
    elif point == "before_policy":
        original = evaluation.execute_engine
        async def cancel(*args, **kwargs):
            result = await original(*args, **kwargs)
            asyncio.current_task().cancel()
            return result
        monkeypatch.setattr(evaluation, "execute_engine", cancel)
    else:
        original = finalization.evaluate_risk_policy
        def cancel(value):
            result = original(value)
            asyncio.current_task().cancel()
            return result
        monkeypatch.setattr(finalization, "evaluate_risk_policy", cancel)
    with pytest.raises(NativeEvaluationCancelled) as caught:
        run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
    result = caught.value.result
    assert result.outcome.status == result.termination == "CANCELLED"
    assert result.outcome.risk_level == "UNKNOWN" and result.outcome.recommendation is None
    assert result.execution.behavior is not None
    assert (result.policy_result is not None) == (point == "after_policy")
    assert "private-cancellation-reason" not in str(caught.value)
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()) == result


def test_stale_gpt_and_restarted_budget_reject_instead_of_reclassifying(final_cases, monkeypatch):
    import copenhagen_fds.engine_finalization as finalization
    from copenhagen_fds.analysis.budget import AnalysisBudget
    case = final_cases.execute()
    case.now[0] = 1.0
    result = finalize_engine_execution(case.execution, budget=case.budget)
    from copenhagen_fds.engine_finalization import refresh_native_result
    with pytest.raises(ValueError, match="rewind"):
        refresh_native_result(result, AnalysisBudget.start(10, clock=lambda: 0.0))
    monkeypatch.setattr(finalization, "utc_now", lambda: case.execution.completed_at + timedelta(seconds=300))
    stale = finalize_engine_execution(case.execution, budget=case.budget)
    assert stale.policy_stage.failure_code == "INVALID_INPUT" and stale.policy_result is None
    assert stale.outcome.status == "FAILED" and stale.outcome.risk_level == "UNKNOWN"
