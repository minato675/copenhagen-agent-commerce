"""S15P21A207-289: original request binding and existing GPT execution semantics."""

import asyncio
from copy import deepcopy
from datetime import timedelta
import json
import logging

import httpx2
from openai import AsyncOpenAI
import pytest

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.openai_adapter import OpenAIAnalysisAdapter
from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.engine_analysis import EngineAnalysisResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.engine_analysis import evaluate_engine_analysis
from copenhagen_fds.settings import EvaluationMode


@pytest.mark.parametrize("signal", ["MATCH", "MISMATCH", "UNKNOWN"])
def test_existing_analyzer_receives_original_text_order_and_one_budget(analysis_cases, run_async, signal):
    request = analysis_cases.request
    adapter = analysis_cases.adapter(signal)
    before = request.model_dump_json()
    shared = AnalysisBudget.start(8.0)
    result = run_async(evaluate_engine_analysis(request, adapter, budget=shared))
    assert len(adapter.calls) == 2 and all(b is shared for _, b in adapter.calls)
    assert json.loads(adapter.calls[0][0].user_message) == {"request_text": request.analysis.user_request.text}
    compared = json.loads(adapter.calls[1][0].user_message)
    assert "request_text" not in compared
    assert compared["intent"]["items"][0]["name"] == "synthetic-extracted-product"
    assert compared["orders"][0]["items"][0]["name"] == request.analysis.order.orders[0].items[0].name
    assert result.analysis.comparison.payload.comparison == signal
    assert result.analysis.extraction.input_source == "agent_summary"
    assert result.request == request and request.model_dump_json() == before
    assert result.analysis.extraction.to_purchase_intent(request.analysis).source == "agent_summary"
    assert result.analysis.comparison.telemetry.usage.total_tokens == 7
    assert EngineAnalysisResult.model_validate_json(result.model_dump_json()).validate_for_request(request) == result
    assert "synthetic-private-intent" not in repr(result)


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
@pytest.mark.parametrize("kind", ["API_ERROR", "TIMEOUT", "REFUSAL", "INCOMPLETE_RESPONSE", "SCHEMA_ERROR"])
def test_failures_preserve_original_stage_and_stop_extraction_dependents(analysis_cases, run_async, stage, kind):
    adapter = analysis_cases.adapter()
    failure = AnalysisFailure(kind=kind, retryable=False)
    adapter.outcomes[stage] = failure
    record = run_async(evaluate_engine_analysis(analysis_cases.request, adapter))
    assert getattr(record.analysis, stage).failure == failure
    assert len(adapter.calls) == (1 if stage == "extraction" else 2)
    if stage == "extraction":
        skipped = record.analysis.comparison
        assert skipped.status == "NOT_RUN" and skipped.metadata.evaluated_at is None
        assert skipped.metadata.model_version is None and skipped.telemetry.duration_ms is None
    else:
        assert record.analysis.extraction.status == "SUCCESS"


def test_missing_text_skips_all_calls_and_unusable_extraction_keeps_clarifications(analysis_cases, run_async):
    data = analysis_cases.request.model_dump()
    data["analysis"]["user_request"]["text"] = None
    adapter = analysis_cases.adapter()
    record = run_async(evaluate_engine_analysis(EngineEvaluationRequest.model_validate(data), adapter))
    assert not adapter.calls and record.analysis.extraction.status == record.analysis.comparison.status == "NOT_RUN"
    assert record.analysis.extraction.clarifications
    adapter.outcomes["extraction"] = {**dict.fromkeys(analysis_cases.extracted), "ambiguities": ["synthetic-clarification"]}
    record = run_async(evaluate_engine_analysis(analysis_cases.request, adapter))
    assert record.analysis.extraction.failure.kind == "INSUFFICIENT_INPUT"
    assert record.analysis.extraction.clarifications == ("synthetic-clarification",)
    assert len(adapter.calls) == 1


def test_mapping_failure_keeps_original_currency_and_no_comparison_call(analysis_cases, run_async):
    adapter = analysis_cases.adapter()
    adapter.outcomes["extraction"] = {**analysis_cases.extracted,
        "budget": dict(kind="maximum", amount=dict(amount_minor=100, currency="USD"))}
    record = run_async(evaluate_engine_analysis(analysis_cases.request, adapter))
    assert record.analysis.extraction.payload.budget.amount.currency == "USD"
    assert record.request.analysis.order.total.currency == "KRW"
    assert record.analysis.comparison.failure.kind == "INVALID_INPUT" and len(adapter.calls) == 1


@pytest.mark.parametrize("field,value", [("openai_model", "different"), ("openai_timeout_seconds", 4.0),
    ("evaluation_timeout_seconds", 9.0), ("mode", EvaluationMode.LIVE)])
def test_settings_mismatch_rejected_before_calls(analysis_cases, run_async, field, value):
    adapter = analysis_cases.adapter()
    values = {field: value}
    if field == "mode":
        from pydantic import SecretStr
        values["gms_key"] = SecretStr("synthetic-private-key")
    adapter.settings = adapter.settings.model_copy(update=values)
    with pytest.raises(ValueError) as caught:
        run_async(evaluate_engine_analysis(analysis_cases.request, adapter))
    assert not adapter.calls and "synthetic-private-key" not in str(caught.value)


def test_supplied_budget_limit_and_future_cutoff_reject_before_calls(analysis_cases, run_async, monkeypatch):
    import copenhagen_fds.engine_analysis as module

    adapter = analysis_cases.adapter()
    with pytest.raises(ValueError, match="budget"):
        run_async(evaluate_engine_analysis(analysis_cases.request, adapter, budget=AnalysisBudget.start(11)))
    class BeforeCutoff:
        @staticmethod
        def now(zone):
            return analysis_cases.request.feature_input.evaluation.evaluated_at - timedelta(seconds=1)
    monkeypatch.setattr(module, "datetime", BeforeCutoff)
    with pytest.raises(ValueError, match="cutoff"):
        run_async(evaluate_engine_analysis(analysis_cases.request, adapter))
    assert not adapter.calls


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_cancellation_propagates_without_followup_call(analysis_cases, run_async, stage):
    adapter = analysis_cases.adapter()
    adapter.outcomes[stage] = asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError):
        run_async(evaluate_engine_analysis(analysis_cases.request, adapter))
    assert len(adapter.calls) == (1 if stage == "extraction" else 2)


@pytest.mark.parametrize("change", ["user", "purchase", "order", "intent", "text", "trace", "gateway",
    "prompt", "model", "source", "before_start", "after_end", "reversed_stages"])
def test_record_rejects_changed_references_configuration_and_time(analysis_cases, run_async, change):
    record = run_async(evaluate_engine_analysis(analysis_cases.request, analysis_cases.adapter()))
    data = record.model_dump()
    metadata = data["analysis"]["extraction"]["metadata"]
    if change in {"user", "purchase", "order", "intent"}:
        target = metadata["target"]
        if change == "order":
            target["order"]["version"] = "other"
        else:
            target[{"user": "user_id", "purchase": "purchase_request_id", "intent": "intent_version"}[change]] = "other"
    elif change == "text":
        data["request"]["analysis"]["user_request"]["text"] = "synthetic-private-altered"
    elif change in {"trace", "prompt"}:
        metadata["trace_id" if change == "trace" else "prompt_version"] = "other"
    elif change in {"gateway", "model"}:
        data["analysis"]["extraction"]["telemetry"]["gateway" if change == "gateway" else "requested_model"] = "GMS" if change == "gateway" else "other"
    elif change == "source":
        data["analysis"]["extraction"]["input_source"] = "user_direct"
    elif change == "before_start":
        metadata["evaluated_at"] = record.started_at - timedelta(microseconds=1)
    elif change == "after_end":
        metadata["evaluated_at"] = record.completed_at + timedelta(microseconds=1)
    else:
        data["analysis"]["comparison"]["metadata"]["evaluated_at"] = record.started_at - timedelta(microseconds=1)
    with pytest.raises(ValueError) as caught:
        EngineAnalysisResult.model_validate(data)
    assert "synthetic-private-altered" not in str(caught.value)


@pytest.mark.parametrize("mode", ["MATCH", "UNKNOWN", "extraction-429", "comparison-401", "invalid-payload"])
def test_real_adapter_and_sdk_mock_transport_keep_calls_and_measurements(analysis_cases, run_async, mode, caplog, monkeypatch):
    caplog.set_level(logging.DEBUG)
    monkeypatch.delenv("GMS_KEY", raising=False)
    request = analysis_cases.request
    seen = []
    now = [0.0]
    budget = AnalysisBudget.start(6, clock=lambda: now[0])
    def handler(http_request):
        body = json.loads(http_request.content)
        stage = "extraction" if not seen else "comparison"
        seen.append((body, http_request.extensions["timeout"]["read"]))
        assert str(http_request.url) == "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
        assert body["model"] == request.execution.requested_model
        assert body["response_format"]["json_schema"]["name"] == f"fds_intent_{stage}_v1"
        if stage == "extraction":
            now[0] = 5.75
        if (stage, mode) in {("extraction", "extraction-429"), ("comparison", "comparison-401")}:
            return httpx2.Response(429 if stage == "extraction" else 401,
                json={"error": {"message": "synthetic-private-provider-error"}})
        payload = deepcopy(analysis_cases.extracted) if stage == "extraction" else analysis_cases.comparison("UNKNOWN" if mode == "UNKNOWN" else "MATCH")
        if mode == "invalid-payload" and stage == "comparison":
            payload["recommendation"] = "ALLOW"
        return httpx2.Response(200, json=dict(id=f"synthetic-{stage}", model="synthetic-response-model",
            choices=[dict(finish_reason="stop", message=dict(role="assistant", content=json.dumps(payload)))],
            usage=dict(prompt_tokens=4, completion_tokens=3, total_tokens=7)), headers={"x-request-id": f"synthetic-http-{stage}"})
    async def scenario():
        async with AsyncOpenAI(api_key="synthetic-key", http_client=httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler))) as sdk:
            async with OpenAIAnalysisAdapter(analysis_cases.adapter().settings, client=sdk) as adapter:
                return await evaluate_engine_analysis(request, adapter, budget=budget)
    result = run_async(scenario())
    assert [timeout for _, timeout in seen] == ([5.0] if mode == "extraction-429" else [5.0, 0.25])
    if mode in {"MATCH", "UNKNOWN"}:
        assert result.analysis.comparison.payload.comparison == mode
        assert result.analysis.comparison.telemetry.usage.total_tokens == 7
        assert result.analysis.comparison.telemetry.request_id == "synthetic-http-comparison"
    elif mode == "extraction-429":
        assert result.analysis.extraction.failure.api_error == "RATE_LIMIT" and result.analysis.comparison.status == "NOT_RUN"
    else:
        assert result.analysis.comparison.failure.kind == ("API_ERROR" if mode == "comparison-401" else "SCHEMA_ERROR")
    if len(seen) == 2:
        assert "request_text" not in json.loads(seen[1][0]["messages"][1]["content"])
    assert "synthetic-key" not in result.model_dump_json() + caplog.text
    assert "synthetic-private-provider-error" not in result.model_dump_json() + caplog.text
    assert "synthetic-private-intent" not in caplog.text
