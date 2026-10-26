"""Extraction/comparison orchestration with typed stubs and offline SDK responses."""

import asyncio
from copy import deepcopy
import json
import logging

import httpx2
from openai import AsyncOpenAI
from pydantic import ValidationError
import pytest

from copenhagen_fds.analysis import AnalysisBudget, IntentAnalysisRun, IntentAnalyzer, OpenAIAnalysisAdapter
from copenhagen_fds.analysis.comparison_prompt import COMPARISON_INSTRUCTIONS
from copenhagen_fds.contracts import AnalysisFailure, IntentAnalysisRequest
from .support import completion, output, ref


def test_pipeline_shares_one_decreasing_budget_and_keeps_each_stage(run_async, adapter, analysis_request):
    now = [0.0]
    budget = AnalysisBudget.start(10, clock=lambda: now[0])
    adapter.on_call = lambda _: now.__setitem__(0, now[0] + 2)
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace", budget=budget))
    assert [p.stage for p, _, _ in adapter.calls] == ["extraction", "comparison"]
    assert all(b is budget for _, b, _ in adapter.calls)
    assert [remaining for _, _, remaining in adapter.calls] == [10, 8]
    assert result.extraction.telemetry.response_id == "synthetic-extraction"
    assert result.comparison.telemetry.response_id == "synthetic-comparison"
    assert result.to_common_result(analysis_request).comparison == "MATCH"
    assert IntentAnalysisRun.model_validate_json(result.model_dump_json()) == result
    body = json.loads(adapter.calls[1][0].user_message)
    assert body["intent"]["items"][0]["name"] == result.extraction.payload.items[0].name
    assert "request_text" not in body and analysis_request.user_request.text not in adapter.calls[1][0].user_message


def test_pipeline_starts_only_one_default_budget(run_async, adapter, analysis_request):
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert result.comparison.status == "SUCCESS"
    assert adapter.calls[0][1] is adapter.calls[1][1]
    assert adapter.calls[0][1].total_seconds == adapter.settings.evaluation_timeout_seconds


@pytest.mark.parametrize("kind", ["API_ERROR", "TIMEOUT", "REFUSAL", "INCOMPLETE_RESPONSE", "SCHEMA_ERROR"])
def test_extraction_failure_skips_comparison_without_hiding_failure(run_async, adapter, analysis_request, kind):
    adapter.outcomes["extraction"] = completion("extraction", failure=AnalysisFailure(kind=kind))
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert len(adapter.calls) == 1 and result.extraction.failure.kind == kind
    assert result.comparison.status == "NOT_RUN" and result.comparison.failure is None
    assert result.comparison.metadata.evaluated_at is None and result.comparison.telemetry.usage is None
    assert result.comparison.telemetry.duration_ms is None
    common = result.to_common_result(analysis_request)
    assert common.status == "FAILED" and common.comparison is None
    assert common.reasons == result.extraction.reasons and common.metadata is result.extraction.metadata


def test_missing_request_skips_both_stages(run_async, adapter, analysis_input_payload):
    analysis_input_payload["user_request"]["text"] = None
    comparison_request = IntentAnalysisRequest.model_validate(analysis_input_payload)
    result = run_async(IntentAnalyzer(adapter).analyze(comparison_request, trace_id="trace"))
    assert not adapter.calls and result.extraction.status == result.comparison.status == "NOT_RUN"
    assert result.to_common_result(comparison_request).status == "NOT_RUN"
    assert result.to_common_result(comparison_request).reasons == result.extraction.reasons


def test_unusable_extraction_skips_comparison(run_async, adapter, analysis_request):
    payload = dict.fromkeys(("items", "budget", "purpose", "explicit_conditions", "preferences", "ambiguities"))
    payload["ambiguities"] = ["구매 요청이 필요함"]
    adapter.outcomes["extraction"] = completion("extraction", payload)
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert result.extraction.failure.kind == "INSUFFICIENT_INPUT" and result.comparison.status == "NOT_RUN"
    assert len(adapter.calls) == 1 and result.to_common_result(analysis_request).status == "FAILED"


def test_mapping_failure_preserves_extracted_currency_without_comparison_call(run_async, adapter, analysis_request, extracted):
    extracted["budget"]["amount"]["currency"] = "USD"
    adapter.outcomes["extraction"] = completion("extraction", extracted)
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert result.extraction.status == "SUCCESS" and result.extraction.to_purchase_intent(analysis_request).budget.amount.currency == "USD"
    assert len(adapter.calls) == 1 and result.comparison.status == "FAILED"
    assert result.comparison.failure.kind == "INVALID_INPUT" and result.comparison.failure.retryable is False
    assert result.comparison.telemetry.duration_ms is None and result.comparison.telemetry.response_id is None
    assert result.comparison.reasons[0].source == "FDS"
    assert result.to_common_result(analysis_request).status == "FAILED"


def test_pipeline_forwards_fds_clarifications_without_rewriting_extraction(run_async, adapter, analysis_request):
    payload = dict.fromkeys(("items", "budget", "purpose", "explicit_conditions", "preferences", "ambiguities"))
    payload["purpose"] = "생일 선물"
    adapter.outcomes["extraction"] = completion("extraction", payload)
    adapter.outcomes["comparison"] = completion("comparison", output("UNKNOWN", "INTENT_UNCERTAIN", confirmation="CLARIFY_INTENT"))
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert result.extraction.payload.ambiguities is None and result.extraction.clarifications
    body = json.loads(adapter.calls[1][0].user_message)
    assert body["intent"]["ambiguities"] is None and body["clarifications"] == list(result.extraction.clarifications)
    assert result.to_common_result(analysis_request).comparison == "UNKNOWN"


def test_deadline_expiring_between_stages_prevents_second_call(run_async, adapter, analysis_request, monkeypatch):
    now = [0.0]
    budget = AnalysisBudget.start(10, clock=lambda: now[0])
    analyzer = IntentAnalyzer(adapter)
    original = analyzer._extractor.extract
    async def finish_at_deadline(*args, **kwargs):
        extracted = await original(*args, **kwargs)
        now[0] = 10
        return extracted
    monkeypatch.setattr(analyzer._extractor, "extract", finish_at_deadline)
    result = run_async(analyzer.analyze(analysis_request, trace_id="trace", budget=budget))
    assert result.extraction.status == "SUCCESS" and result.comparison.failure.kind == "TIMEOUT"
    assert len(adapter.calls) == 1 and result.comparison.telemetry.usage is None
    assert result.to_common_result(analysis_request).status == "FAILED"


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_pipeline_propagates_cancellation_and_stops(run_async, adapter, analysis_request, stage):
    adapter.outcomes[stage] = asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError):
        run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert len(adapter.calls) == (1 if stage == "extraction" else 2)


@pytest.mark.parametrize("field", ["user_id", "evaluation_id", "purchase_request_id", "intent_version", "snapshot_id", "version", "snapshot_hash", "source"])
def test_result_cannot_be_reused_for_another_target_or_source(run_async, adapter, analysis_request, field):
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    data = analysis_request.model_dump(mode="json")
    if field == "purchase_request_id":
        for section in ("user_request", "order", "context"):
            data[section][field] = "other-purchase"
    elif field in {"user_id", "evaluation_id"}:
        data["context"][field] = "other"
    elif field == "intent_version":
        data["user_request"]["version"] = "other"
    elif field == "source":
        data["user_request"]["source"] = "user_direct"
    else:
        data["order"]["reference"][field] = "b" * 64 if field == "snapshot_hash" else "other"
    with pytest.raises(ValueError):
        result.to_common_result(IntentAnalysisRequest.model_validate(data))


@pytest.mark.parametrize("field", ["trace_id", "target"])
def test_pipeline_envelope_rejects_mixed_stage_metadata(run_async, adapter, analysis_request, field):
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    data = result.model_dump(mode="json")
    if field == "target":
        data["comparison"]["metadata"][field]["user_id"] = "other"
    else:
        data["comparison"]["metadata"][field] = "other"
    with pytest.raises(ValidationError, match="share target and trace"):
        IntentAnalysisRun.model_validate(data)


@pytest.mark.parametrize("mode", [
    "valid", "unknown", "comparison-401", "extraction-429", "refusal", "truncated",
    "extra-authority", "extra-target", "wrong-reference", "missing-field", "invalid-match", "duplicate-reference",
])
def test_real_sdk_offline_extraction_to_comparison(run_async, settings, analysis_request, extracted, mode, caplog):
    caplog.set_level(logging.DEBUG)
    requests = []
    def handler(http_request):
        body = json.loads(http_request.content)
        requests.append(body)
        stage = "extraction" if len(requests) == 1 else "comparison"
        assert str(http_request.url) == "https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions"
        assert body["response_format"]["json_schema"]["name"] == f"fds_intent_{stage}_v1"
        assert body["response_format"]["json_schema"]["strict"] is True
        assert [m["role"] for m in body["messages"]] == ["developer", "user"]
        assert set(body) == {"model", "messages", "response_format", "stream"} and body["stream"] is False
        if (stage, mode) in {("extraction", "extraction-429"), ("comparison", "comparison-401")}:
            return httpx2.Response(429 if stage == "extraction" else 401, json={"error": {"message": "synthetic-private-error"}})
        payload = deepcopy(extracted) if stage == "extraction" else output()
        finish, refusal = "stop", None
        if stage == "comparison":
            if mode in {"unknown", "wrong-reference", "duplicate-reference", "invalid-match"}:
                payload = output("UNKNOWN", "INSUFFICIENT_DATA", refs=[ref()], confirmation="PROVIDE_MISSING_DATA")
            if mode == "wrong-reference":
                payload["findings"][0]["related_items"] = [ref("other-order")]
            if mode == "duplicate-reference":
                payload["findings"][0]["related_items"] = [ref(), ref()]
            if mode == "invalid-match":
                payload["comparison"] = "MATCH"
            if mode == "extra-authority":
                payload["recommendation"] = "ALLOW"
            if mode == "extra-target":
                payload["target"] = {"user_id": "other-user"}
            if mode == "missing-field":
                del payload["findings"]
            if mode == "refusal":
                refusal = "synthetic-private-refusal"
            if mode == "truncated":
                finish = "length"
        return httpx2.Response(200, json={
            "id": f"synthetic-{stage}", "model": "gpt-5.4-mini-synthetic",
            "choices": [{"finish_reason": finish, "message": {
                "role": "assistant", "content": json.dumps(payload), "refusal": refusal,
            }}], "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
        }, headers={"x-comparison_request-id": f"comparison_request-{stage}"})
    async def scenario():
        async with AsyncOpenAI(api_key="synthetic-key", http_client=httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
        )) as sdk:
            adapter = OpenAIAnalysisAdapter(settings, client=sdk)
            return await IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace")
    result = run_async(scenario())
    assert len(requests) == (1 if mode == "extraction-429" else 2)
    if mode in {"valid", "unknown"}:
        assert result.comparison.status == "SUCCESS"
        assert result.to_common_result(analysis_request).comparison == ("MATCH" if mode == "valid" else "UNKNOWN")
        assert result.comparison.telemetry.usage.total_tokens == 8
    elif mode == "extraction-429":
        assert result.extraction.failure.api_error == "RATE_LIMIT" and result.comparison.status == "NOT_RUN"
        assert result.to_common_result(analysis_request).status == "FAILED"
    else:
        kinds = {"comparison-401": "API_ERROR", "refusal": "REFUSAL", "truncated": "INCOMPLETE_RESPONSE"}
        assert result.comparison.failure.kind == kinds.get(mode, "SCHEMA_ERROR")
        assert result.to_common_result(analysis_request).comparison is None
    if len(requests) == 2:
        assert requests[1]["messages"][0]["content"] == COMPARISON_INSTRUCTIONS
        assert "request_text" not in json.loads(requests[1]["messages"][1]["content"])
    assert "synthetic-private" not in result.model_dump_json() + caplog.text
    assert analysis_request.user_request.text not in result.model_dump_json() + caplog.text


def test_sdk_second_stage_timeout_uses_remaining_pipeline_budget(run_async, settings, analysis_request, extracted):
    now = [0.0]
    shared = AnalysisBudget.start(6, clock=lambda: now[0])
    timeouts = []
    def handler(http_request):
        timeouts.append(http_request.extensions["timeout"]["read"])
        stage = "extraction" if len(timeouts) == 1 else "comparison"
        if stage == "extraction":
            now[0] = 5.75
        return httpx2.Response(200, json={
            "id": f"synthetic-{stage}", "model": "gpt-5.4-mini-synthetic",
            "choices": [{"finish_reason": "stop", "message": {
                "role": "assistant", "content": json.dumps(extracted if stage == "extraction" else output()),
            }}],
        })
    async def scenario():
        async with AsyncOpenAI(api_key="synthetic-key", http_client=httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
        )) as sdk:
            return await IntentAnalyzer(OpenAIAnalysisAdapter(settings, client=sdk)).analyze(
                analysis_request, trace_id="trace", budget=shared,
            )
    result = run_async(scenario())
    assert result.to_common_result(analysis_request).comparison == "MATCH"
    assert timeouts == [5, 0.25]
    assert result.extraction.telemetry.usage is None and result.comparison.telemetry.usage is None


def test_comparison_failure_does_not_erase_successful_extraction(run_async, adapter, analysis_request):
    adapter.outcomes["comparison"] = completion("comparison", failure=AnalysisFailure(kind="TIMEOUT"))
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    assert result.extraction.status == "SUCCESS" and result.extraction.payload is not None
    assert result.comparison.failure.kind == "TIMEOUT" and len(adapter.calls) == 2
    assert result.to_common_result(analysis_request).metadata is result.comparison.metadata


def test_run_rejects_mapping_failure_with_valid_input(run_async, adapter, analysis_request):
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    data = result.model_dump(mode="json")
    data["comparison"].update(
        status="FAILED", payload=None, failure={"kind": "INVALID_INPUT", "retryable": False},
        reasons=[{"code": "EVALUATION_FAILED", "source": "FDS"}],
    )
    with pytest.raises(ValueError, match="cannot claim mapping failure"):
        IntentAnalysisRun.model_validate(data).to_common_result(analysis_request)


def test_run_rejects_comparison_execution_after_failed_extraction(run_async, adapter, analysis_request):
    result = run_async(IntentAnalyzer(adapter).analyze(analysis_request, trace_id="trace"))
    data = result.model_dump(mode="json")
    data["extraction"].update(
        status="FAILED", payload=None, failure={"kind": "API_ERROR"},
        reasons=[{"code": "EVALUATION_FAILED", "source": "MOCK"}],
    )
    with pytest.raises(ValidationError, match="requires successful extraction"):
        IntentAnalysisRun.model_validate(data)
