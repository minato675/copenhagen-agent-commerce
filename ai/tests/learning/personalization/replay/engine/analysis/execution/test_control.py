"""S15P21A207-290: shared deadline, cancellation and independent failure control."""

import asyncio

import pytest

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.engine import EngineExecutionCancelled, execute_engine


@pytest.mark.parametrize("signal", ["MATCH", "MISMATCH", "UNKNOWN"])
def test_engine_calls_each_existing_stage_once_and_preserves_input(analysis_cases, run_async, monkeypatch, signal):
    import copenhagen_fds.engine as engine

    adapter, before = analysis_cases.adapter(signal), analysis_cases.request.model_dump_json()
    calls, original = [], engine.evaluate_engine_behavior
    def behavior(request):
        calls.append("behavior")
        return original(request)
    monkeypatch.setattr(engine, "evaluate_engine_behavior", behavior)
    adapter.on_call = lambda stage: calls.append(stage)
    result = run_async(execute_engine(analysis_cases.request, adapter))
    assert calls == ["behavior", "extraction", "comparison"]
    assert result.termination == "COMPLETED" and result.comparison.payload.comparison == signal
    assert adapter.calls[0][1] is adapter.calls[1][1]
    assert result.request.model_dump_json() == before
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result


def test_uncollected_text_and_history_remain_incomplete_without_gpt_calls(analysis_cases, run_async):
    data = analysis_cases.request.model_dump()
    data["analysis"]["user_request"]["text"] = None
    data["feature_input"]["history"]["purchases"] = dict(status="NOT_COLLECTED")
    adapter = analysis_cases.adapter()
    result = run_async(execute_engine(EngineEvaluationRequest.model_validate(data), adapter))
    assert not adapter.calls and result.status == "INCOMPLETE"
    assert result.behavior.evaluation.result.behavior.coverage == "PARTIAL"
    assert result.extraction.status == result.comparison.status == "NOT_RUN"
    assert result.to_analysis_result().analysis.extraction.clarifications


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_gpt_and_behavior_failures_remain_independent(analysis_cases, run_async, monkeypatch, stage):
    import copenhagen_fds.personal_amount as amount

    def fail(*args, **kwargs):
        raise ArithmeticError("private-calculation-error")
    monkeypatch.setattr(amount, "_calculation", fail)
    adapter = analysis_cases.adapter()
    adapter.outcomes[stage] = AnalysisFailure(kind="API_ERROR", api_error="AUTHENTICATION", http_status=401)
    result = run_async(execute_engine(analysis_cases.request, adapter))
    assert result.status == "FAILED" and result.stage("BEHAVIOR").failure_code == "CALCULATION_FAILED"
    assert result.stage(stage.upper()).failure_code == "API_ERROR"
    assert getattr(result, stage).failure.http_status == 401
    assert result.behavior.evaluation.result.features[0].personal.status == "FAILED"
    assert "private-calculation-error" not in result.model_dump_json()


@pytest.mark.parametrize("error,code,calls", [(ValueError, "INVALID_INPUT", 0), (RuntimeError, "INTERNAL_ERROR", 2)])
def test_bad_behavior_input_stops_gpt_but_internal_failure_keeps_independent_evidence(analysis_cases, run_async, monkeypatch, error, code, calls):
    import copenhagen_fds.engine as engine

    def fail(*args):
        raise error("private-input-error")
    monkeypatch.setattr(engine, "evaluate_engine_behavior", fail)
    adapter = analysis_cases.adapter()
    result = run_async(execute_engine(analysis_cases.request, adapter))
    assert result.status == "FAILED" and result.stage("BEHAVIOR").failure_code == code
    assert len(adapter.calls) == calls and "private-input-error" not in result.model_dump_json()


@pytest.mark.parametrize("consumed", [10.0, 11.0])
def test_late_sync_behavior_is_preserved_and_no_gpt_starts(analysis_cases, run_async, monkeypatch, consumed):
    import copenhagen_fds.engine as engine

    now, original = [0.0], engine.evaluate_engine_behavior
    budget = AnalysisBudget.start(10.0, clock=lambda: now[0])
    def behavior(request):
        value = original(request)
        now[0] = consumed
        return value
    monkeypatch.setattr(engine, "evaluate_engine_behavior", behavior)
    adapter = analysis_cases.adapter()
    result = run_async(execute_engine(analysis_cases.request, adapter, budget=budget))
    assert result.status == "FAILED" and result.termination == "BUDGET_EXHAUSTED"
    assert result.elapsed_seconds == consumed and not adapter.calls and result.behavior is not None
    assert result.stage("EXTRACTION").skip_reason == result.stage("COMPARISON").skip_reason == "BUDGET_EXHAUSTED"


def test_expired_supplied_budget_starts_no_stage(analysis_cases, run_async, monkeypatch):
    import copenhagen_fds.engine as engine

    now = [0.0]
    budget = AnalysisBudget.start(10.0, clock=lambda: now[0])
    now[0] = 12.0
    monkeypatch.setattr(engine, "evaluate_engine_behavior", lambda _: pytest.fail("expired work started"))
    adapter = analysis_cases.adapter()
    result = run_async(execute_engine(analysis_cases.request, adapter, budget=budget))
    assert result.elapsed_seconds == 12.0 and not adapter.calls
    assert all(s.status == "NOT_RUN" and s.skip_reason == "BUDGET_EXHAUSTED" for s in result.stages)


def test_boundary_after_extraction_keeps_success_and_prevents_comparison(analysis_cases, run_async, monkeypatch):
    import copenhagen_fds.engine as engine

    now = [0.0]
    budget = AnalysisBudget.start(10.0, clock=lambda: now[0])
    original = engine.IntentAnalyzer.extract
    async def finish_then_expire(self, *args, **kwargs):
        result = await original(self, *args, **kwargs)
        now[0] = 10.0
        return result
    monkeypatch.setattr(engine.IntentAnalyzer, "extract", finish_then_expire)
    adapter = analysis_cases.adapter()
    result = run_async(execute_engine(analysis_cases.request, adapter, budget=budget))
    assert result.extraction.status == "SUCCESS" and result.comparison is None
    assert len(adapter.calls) == 1 and result.termination == "BUDGET_EXHAUSTED"
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_caller_cancellation_propagates_with_partial_evidence_and_cleanup(analysis_cases, run_async, monkeypatch, stage):
    adapter = analysis_cases.adapter()
    original, started, cleaned = adapter.complete, asyncio.Event(), []
    async def blocked(prompt, *, budget):
        if prompt.stage != stage:
            return await original(prompt, budget=budget)
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            cleaned.append(stage)
    adapter.complete = blocked
    async def scenario():
        task = asyncio.create_task(execute_engine(analysis_cases.request, adapter))
        await started.wait()
        task.cancel("private-cancel-reason")
        with pytest.raises(EngineExecutionCancelled) as caught:
            await task
        assert task.cancelled() and cleaned == [stage]
        return caught.value
    error = run_async(scenario())
    result = error.result
    assert result.status == result.termination == "CANCELLED"
    assert result.behavior is not None and result.stage(stage.upper()).status == "CANCELLED"
    assert (result.extraction is not None) == (stage == "comparison")
    assert result.comparison is None and "private-cancel-reason" not in str(error)
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result


def test_queued_cancellation_after_sync_work_prevents_first_gpt_call(analysis_cases, run_async, monkeypatch):
    import copenhagen_fds.engine as engine

    original = engine.evaluate_engine_behavior
    def behavior(request):
        result = original(request)
        asyncio.current_task().cancel()
        return result
    monkeypatch.setattr(engine, "evaluate_engine_behavior", behavior)
    adapter = analysis_cases.adapter()
    with pytest.raises(EngineExecutionCancelled) as caught:
        run_async(execute_engine(analysis_cases.request, adapter))
    assert caught.value.result.behavior is not None and not adapter.calls
    assert caught.value.result.stage("EXTRACTION").skip_reason == "CANCELLED"


def test_cancellation_after_budget_stop_preserves_skips_and_still_propagates(analysis_cases, run_async, monkeypatch):
    import copenhagen_fds.engine as engine

    now = [0.0]
    budget = AnalysisBudget.start(10, clock=lambda: now[0])
    now[0] = 10.0
    original = engine.EngineStageRecorder.skip_remaining
    def skip_then_cancel(self, reason):
        original(self, reason)
        if reason == "BUDGET_EXHAUSTED":
            asyncio.current_task().cancel()
    monkeypatch.setattr(engine.EngineStageRecorder, "skip_remaining", skip_then_cancel)
    adapter = analysis_cases.adapter()
    with pytest.raises(EngineExecutionCancelled) as caught:
        run_async(execute_engine(analysis_cases.request, adapter, budget=budget))
    result = caught.value.result
    assert result.termination == "CANCELLED" and result.elapsed_seconds == 10.0 and not adapter.calls
    assert all(s.skip_reason == "BUDGET_EXHAUSTED" for s in result.stages)
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result
