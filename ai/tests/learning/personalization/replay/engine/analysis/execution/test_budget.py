"""S15P21A207-290: deterministic deadlines and real SDK requests over an offline transport."""

import asyncio
from datetime import timedelta
import json

import httpx2
from openai import AsyncOpenAI
import pytest

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.openai_adapter import OpenAIAnalysisAdapter
from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.engine import execute_engine


@pytest.mark.parametrize("consumed", [1.0, 9.875])
def test_async_timeout_uses_smaller_limit_and_cleans_active_work(analysis_cases, run_async, monkeypatch, consumed):
    import copenhagen_fds.engine as engine

    now, limits, cleaned = [0.0], [], []
    budget = AnalysisBudget.start(10, clock=lambda: now[0])
    original_behavior, original_timeout = engine.evaluate_engine_behavior, asyncio.timeout
    request_data = analysis_cases.request.model_dump()
    request_data["execution"]["provider_timeout_seconds"] = 0.25
    request = EngineEvaluationRequest.model_validate(request_data)
    adapter = analysis_cases.adapter()
    adapter.settings = adapter.settings.model_copy(update={"openai_timeout_seconds": 0.25})
    def behavior(value):
        result = original_behavior(value)
        now[0] = consumed
        return result
    def deterministic_timeout(delay):
        limits.append(delay)
        now[0] += delay
        # Expire on the next suspension, without sleeping or measuring a real SLA.
        return original_timeout(0)
    async def blocked(*args, **kwargs):
        try:
            await asyncio.Event().wait()
        finally:
            cleaned.append(True)
    monkeypatch.setattr(engine, "evaluate_engine_behavior", behavior)
    monkeypatch.setattr(engine.asyncio, "timeout", deterministic_timeout)
    adapter.complete = blocked
    result = run_async(execute_engine(request, adapter, budget=budget))
    assert limits == [min(0.25, 10 - consumed)] and cleaned == [True]
    assert result.stage("EXTRACTION").failure_code == "TIMEOUT" and result.extraction is None
    assert result.stage("COMPARISON").status == "NOT_RUN" and result.behavior is not None
    assert result.termination == ("COMPLETED" if consumed == 1.0 else "BUDGET_EXHAUSTED")
    assert result.status == "FAILED"


@pytest.mark.parametrize("mode", ["success", "expires_during_extraction", "expires_before_http"])
def test_sdk_receives_budget_remaining_after_behavior_without_reset(analysis_cases, run_async, monkeypatch, mode):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.analysis.intent_extractor as extractor

    now, seen = [0.0], []
    budget = AnalysisBudget.start(6, clock=lambda: now[0])
    original_behavior, original_prompt = engine.evaluate_engine_behavior, extractor.build_extraction_prompt
    def behavior(value):
        result = original_behavior(value)
        now[0] = 2.0
        return result
    def prompt(value):
        result = original_prompt(value)
        if mode == "expires_before_http":
            now[0] = 6.0
        return result
    monkeypatch.setattr(engine, "evaluate_engine_behavior", behavior)
    monkeypatch.setattr(extractor, "build_extraction_prompt", prompt)
    def handler(request):
        stage = "extraction" if not seen else "comparison"
        seen.append(request.extensions["timeout"]["read"])
        if stage == "extraction":
            now[0] = 6.0 if mode == "expires_during_extraction" else 5.75
        payload = analysis_cases.extracted if stage == "extraction" else analysis_cases.comparison()
        return httpx2.Response(200, json=dict(id=f"synthetic-{stage}", model="synthetic-model",
            choices=[dict(finish_reason="stop", message=dict(role="assistant", content=json.dumps(payload)))]))
    async def scenario():
        async with AsyncOpenAI(api_key="synthetic-key", http_client=httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler))) as sdk:
            async with OpenAIAnalysisAdapter(analysis_cases.adapter().settings, client=sdk) as adapter:
                return await execute_engine(analysis_cases.request, adapter, budget=budget)
    result = run_async(scenario())
    assert seen == {"success": [4.0, 0.25], "expires_during_extraction": [4.0], "expires_before_http": []}[mode]
    assert result.behavior is not None and "synthetic-key" not in result.model_dump_json()
    if mode == "success":
        assert result.termination == "COMPLETED" and result.comparison.payload.comparison == "MATCH"
    else:
        assert result.termination == "BUDGET_EXHAUSTED" and result.extraction.failure.kind == "TIMEOUT"
        assert result.comparison is None and result.stage("COMPARISON").skip_reason == "BUDGET_EXHAUSTED"


@pytest.mark.parametrize("point", ["comparison", "snapshot"])
def test_final_work_consumes_budget_without_discarding_valid_evidence(analysis_cases, run_async, monkeypatch, point):
    import copenhagen_fds.engine as engine

    now = [0.0]
    budget = AnalysisBudget.start(10, clock=lambda: now[0])
    if point == "comparison":
        original = engine.IntentAnalyzer.compare_extraction
        async def late(self, *args, **kwargs):
            result = await original(self, *args, **kwargs)
            now[0] = 10.0
            return result
        monkeypatch.setattr(engine.IntentAnalyzer, "compare_extraction", late)
    else:
        original = engine.EngineStageRecorder.snapshot
        def late(self, **kwargs):
            result = original(self, **kwargs)
            now[0] = 10.0
            return result
        monkeypatch.setattr(engine.EngineStageRecorder, "snapshot", late)
    adapter = analysis_cases.adapter()
    result = run_async(execute_engine(analysis_cases.request, adapter, budget=budget))
    assert result.status == "FAILED" and result.termination == "BUDGET_EXHAUSTED"
    assert len(adapter.calls) == 2 and all(s.status == "SUCCESS" for s in result.stages)
    assert result.extraction.payload is not None and result.comparison.payload is not None
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("problem", ["request", "settings", "budget", "future"])
def test_preflight_rejects_without_starting_behavior_or_gpt(analysis_cases, run_async, monkeypatch, problem):
    import copenhagen_fds.engine as engine

    request, adapter = analysis_cases.request, analysis_cases.adapter()
    budget = AnalysisBudget.start(11 if problem == "budget" else 10)
    if problem == "request":
        request = request.model_copy(update={"trace_id": ""})
    elif problem == "settings":
        adapter.settings = adapter.settings.model_copy(update={"openai_model": "other"})
    elif problem == "future":
        monkeypatch.setattr(engine, "utc_now", lambda: request.feature_input.evaluation.evaluated_at - timedelta(seconds=1))
    monkeypatch.setattr(engine, "evaluate_engine_behavior", lambda _: pytest.fail("invalid preflight started scoring"))
    with pytest.raises(ValueError):
        run_async(execute_engine(request, adapter, budget=budget))
    assert not adapter.calls
