"""Exercise the real SDK using HTTPX2 MockTransport; sockets are blocked."""

import asyncio
import json
import logging
import socket

import httpx2
from openai import AsyncOpenAI
import pytest
from pydantic import ValidationError

from copenhagen_fds.analysis import AnalysisBudget, CompletionPrompt, OpenAIAnalysisAdapter
from copenhagen_fds.contracts import (
    AnalysisFailure, AnalysisTelemetry, EvaluationMetadata, EvaluationReason,
    IntentComparisonOutput, IntentExtractionOutput, IntentExtractionResult,
    analysis_response_format,
)
from copenhagen_fds.openai_settings import load_openai_settings
from copenhagen_fds.settings import GMS_CHAT_COMPLETIONS_URL, GMS_OPENAI_BASE_URL, SettingsError


@pytest.fixture
def run_async():
    # Windows creates a loopback socketpair for asyncio's own wake-up pipe.
    # Build only that pipe before installing the network guard.
    with asyncio.Runner() as runner:
        runner.get_loop()
        yield runner.run


@pytest.fixture(autouse=True)
def offline_analysis_tests(monkeypatch, run_async):
    def forbidden(*args, **kwargs):
        pytest.fail("adapter tests must not open network connections")
    monkeypatch.setattr(socket, "create_connection", forbidden)
    monkeypatch.setattr(socket.socket, "connect", forbidden)
    monkeypatch.setattr(socket.socket, "connect_ex", forbidden)


@pytest.fixture
def adapter_settings():
    return load_openai_settings({
        "FDS_OPENAI_TIMEOUT_SECONDS": "1", "FDS_EVALUATION_TIMEOUT_SECONDS": "2",
    })


@pytest.fixture
def prompt():
    return CompletionPrompt(
        stage="extraction", developer_message="synthetic developer instruction",
        user_message="synthetic private purchase text", version="synthetic-prompt-v1",
    )


@pytest.fixture
def response_body(extraction_payload):
    return {
        "id": "chatcmpl-synthetic", "model": "gpt-5.4-mini-synthetic-snapshot",
        "choices": [{"index": 0, "finish_reason": "stop", "message": {
            "role": "assistant", "content": json.dumps(extraction_payload), "refusal": None,
        }}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
    }


async def invoke(settings, prompt, handler, *, budget=None):
    # Deliberately use the SDK's defaults (2 retries) and an incorrect base URL.
    # The adapter must explicitly override them before dispatching each call.
    async with AsyncOpenAI(
        api_key="synthetic-only-key", base_url="https://invalid.example/v1",
        http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(handler)),
    ) as client:
        async with OpenAIAnalysisAdapter(settings, client=client) as adapter:
            return await adapter.complete(prompt, budget=budget or adapter.new_budget())


def test_sdk_request_mapping_and_success(run_async, adapter_settings, prompt, response_body, extraction_payload):
    requests = []
    def handler(request):
        requests.append(request)
        assert str(request.url) == GMS_CHAT_COMPLETIONS_URL
        assert request.method == "POST"
        assert request.headers["authorization"] == "Bearer synthetic-only-key"
        body = json.loads(request.content)
        assert body == {
            "model": "gpt-5.4-mini", "stream": False,
            "messages": [{"role": "developer", "content": prompt.developer_message},
                         {"role": "user", "content": prompt.user_message}],
            "response_format": analysis_response_format("extraction"),
        }
        assert 0 < request.extensions["timeout"]["read"] <= 1
        return httpx2.Response(200, json=response_body, headers={"x-request-id": "req-synthetic"})
    result = run_async(invoke(adapter_settings, prompt, handler))
    assert len(requests) == 1
    assert result.status == "SUCCESS" and result.failure is None
    assert result.payload == IntentExtractionOutput.model_validate(extraction_payload)
    assert result.response_model == response_body["model"]
    assert result.prompt_version == prompt.version
    assert result.telemetry.gateway == "MOCK"
    assert result.telemetry.request_id == "req-synthetic"
    assert result.telemetry.response_id == "chatcmpl-synthetic"
    assert result.telemetry.usage.total_tokens == 30
    assert result.telemetry.duration_ms >= 0
    assert result.completed_at.utcoffset().total_seconds() == 0
    assert "private purchase" not in repr(prompt) + repr(result)
    assert type(result).model_validate_json(result.model_dump_json()) == result


def test_comparison_schema_selected_and_unknown_is_success(run_async, adapter_settings, response_body, comparison_payload):
    prompt = CompletionPrompt(stage="comparison", developer_message="test", user_message="test", version="v2")
    response_body["choices"][0]["message"]["content"] = json.dumps(comparison_payload)
    def handler(request):
        assert json.loads(request.content)["response_format"] == analysis_response_format("comparison")
        return httpx2.Response(200, json=response_body)
    result = run_async(invoke(adapter_settings, prompt, handler))
    assert result.status == "SUCCESS"
    assert isinstance(result.payload, IntentComparisonOutput)
    assert result.payload.comparison == "UNKNOWN"


@pytest.mark.parametrize(("status", "category", "retryable"), [
    (301, "REQUEST", False), (400, "REQUEST", False), (401, "AUTHENTICATION", False),
    (403, "PERMISSION", False), (404, "REQUEST", False), (408, "REQUEST", True),
    (409, "REQUEST", True), (422, "REQUEST", False), (429, "RATE_LIMIT", True),
    (500, "SERVER", True), (503, "SERVER", True),
])
def test_http_errors_no_retry_or_fallback(run_async, adapter_settings, prompt, status, category, retryable, caplog):
    caplog.set_level(logging.DEBUG)
    calls = []
    def handler(request):
        calls.append(str(request.url))
        return httpx2.Response(status, json={"error": {"message": "private upstream error body"}},
                              headers={"x-request-id": "req-error", "location": "https://api.openai.com/v1"})
    result = run_async(invoke(adapter_settings, prompt, handler))
    assert calls == [GMS_CHAT_COMPLETIONS_URL]
    assert result.status == "FAILED" and result.payload is None
    assert result.failure.kind == "API_ERROR"
    assert result.failure.http_status == status
    assert result.failure.api_error == category and result.failure.retryable == retryable
    assert result.telemetry.request_id == "req-error"
    assert result.telemetry.usage is None and result.response_model is None
    text = result.model_dump_json() + caplog.text
    assert "private upstream" not in text and "synthetic-only-key" not in text
    assert "private purchase" not in text


@pytest.mark.parametrize("kind", ["timeout", "connection", "unexpected"])
def test_transport_errors_are_sanitized(run_async, adapter_settings, prompt, kind, caplog):
    caplog.set_level(logging.DEBUG)
    calls = []
    def handler(request):
        calls.append(request)
        cause = ValueError("private nested secret")
        if kind == "timeout":
            raise httpx2.ReadTimeout("private timeout secret", request=request) from cause
        if kind == "connection":
            raise httpx2.ConnectError("private connection secret", request=request) from cause
        raise RuntimeError("private unexpected secret") from cause
    result = run_async(invoke(adapter_settings, prompt, handler))
    assert len(calls) == 1
    assert result.payload is None
    assert result.failure.kind == ("TIMEOUT" if kind == "timeout" else "API_ERROR")
    assert "private" not in result.model_dump_json() + caplog.text
    assert result.telemetry.usage is None


@pytest.mark.parametrize(("change", "failure"), [
    ({"refusal": "private refusal text"}, "REFUSAL"),
    ({"content": None}, "INCOMPLETE_RESPONSE"), ({"content": ""}, "INCOMPLETE_RESPONSE"),
    ({"content": "{"}, "SCHEMA_ERROR"), ({"content": "null"}, "SCHEMA_ERROR"),
    ({"content": "{}"}, "SCHEMA_ERROR"), ({"role": "user"}, "SCHEMA_ERROR"),
    ({"tool_calls": [{"id": "call-forbidden"}]}, "SCHEMA_ERROR"),
    ({"function_call": {"name": "pay"}}, "SCHEMA_ERROR"),
])
def test_invalid_message_never_becomes_success(run_async, adapter_settings, prompt, response_body, change, failure):
    response_body["choices"][0]["message"].update(change)
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.status == "FAILED" and result.payload is None and result.failure.kind == failure
    assert result.telemetry.usage.total_tokens == 30
    assert result.response_model == response_body["model"]
    assert "private refusal" not in result.model_dump_json()


@pytest.mark.parametrize("finish", [None, "length", "tool_calls", "function_call", "unknown"])
def test_unfinished_completion_is_not_success(run_async, adapter_settings, prompt, response_body, finish):
    response_body["choices"][0]["finish_reason"] = finish
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.kind == "INCOMPLETE_RESPONSE" and result.payload is None


def test_content_filter_is_refusal(run_async, adapter_settings, prompt, response_body):
    response_body["choices"][0]["finish_reason"] = "content_filter"
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.kind == "REFUSAL"


@pytest.mark.parametrize("choices", [None, [], [{}, {}]])
def test_missing_or_multiple_choices_are_incomplete(run_async, adapter_settings, prompt, response_body, choices):
    response_body["choices"] = choices
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.kind == "INCOMPLETE_RESPONSE"


@pytest.mark.parametrize("content", [b"<html>private error</html>", b"[]", b'{"choices":[],"choices":[]}'])
def test_bad_wire_json_is_schema_error(run_async, adapter_settings, prompt, content):
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, content=content)))
    assert result.failure.kind == "SCHEMA_ERROR"
    assert result.telemetry.usage is None


@pytest.mark.parametrize("usage", [None, {}, {"completion_tokens": 20}])
def test_missing_measurements_not_inferred(run_async, adapter_settings, prompt, response_body, usage):
    response_body.pop("id")
    response_body["usage"] = usage
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.status == "SUCCESS"
    assert result.telemetry.response_id is None and result.telemetry.request_id is None
    if usage is None:
        assert result.telemetry.usage is None
    else:
        assert result.telemetry.usage.total_tokens is None
        assert result.telemetry.usage.prompt_tokens is None


@pytest.mark.parametrize("usage", [{"prompt_tokens": "10"}, {"total_tokens": True},
                                  {"total_tokens": -1}, {"prompt_tokens": 10, "total_tokens": 9}])
def test_invalid_usage_is_not_silently_replaced(run_async, adapter_settings, prompt, response_body, usage):
    response_body["usage"] = usage
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.kind == "SCHEMA_ERROR" and result.payload is None


def test_configured_model_passed_without_substitution(run_async, prompt, response_body):
    settings = load_openai_settings({"FDS_OPENAI_MODEL": "explicit-unsupported-model",
                                   "FDS_OPENAI_TIMEOUT_SECONDS": "1", "FDS_EVALUATION_TIMEOUT_SECONDS": "2"})
    calls = []
    def handler(request):
        calls.append(json.loads(request.content)["model"])
        return httpx2.Response(400, json={"error": {"message": "model or schema unsupported"}})
    result = run_async(invoke(settings, prompt, handler))
    assert calls == ["explicit-unsupported-model"] and result.failure.http_status == 400


def test_missing_applied_model_fails(run_async, adapter_settings, prompt, response_body):
    response_body.pop("model")
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.kind == "SCHEMA_ERROR" and result.response_model is None


def test_deadline_exhausted_skips_dispatch(run_async, adapter_settings, prompt):
    now = [10.0]
    budget = AnalysisBudget.start(2, clock=lambda: now[0])
    now[0] = 13.0
    def forbidden(request):
        pytest.fail("expired budget must not dispatch")
    result = run_async(invoke(adapter_settings, prompt, forbidden, budget=budget))
    assert result.failure.kind == "TIMEOUT" and result.failure.retryable is False
    assert result.telemetry.request_id is None and result.telemetry.usage is None


def test_two_stages_share_remaining_time(run_async, adapter_settings, prompt, response_body):
    async def scenario():
        now = [10.0]
        budget = AnalysisBudget.start(2, clock=lambda: now[0])
        timeouts = []
        def handler(request):
            timeouts.append(request.extensions["timeout"]["read"])
            return httpx2.Response(200, json=response_body)
        async with AsyncOpenAI(api_key="synthetic", http_client=httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler),
        )) as client:
            adapter = OpenAIAnalysisAdapter(adapter_settings, client=client)
            await adapter.complete(prompt, budget=budget)
            now[0] += 1.75
            await adapter.complete(prompt, budget=budget)
            assert timeouts == [1, 0.25]
    run_async(scenario())


def test_wall_time_timeout_cancels_in_flight_transport(run_async, prompt):
    settings = load_openai_settings({"FDS_OPENAI_TIMEOUT_SECONDS": "0.5", "FDS_EVALUATION_TIMEOUT_SECONDS": "1"})
    cancelled = []
    async def handler(request):
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.append(True)
    result = run_async(invoke(settings, prompt, handler))
    assert result.failure.kind == "TIMEOUT" and cancelled == [True]


def test_caller_cancellation_propagates(run_async, adapter_settings, prompt):
    async def scenario():
        entered = asyncio.Event()
        async def handler(request):
            entered.set()
            await asyncio.Event().wait()
        task = asyncio.create_task(invoke(adapter_settings, prompt, handler))
        await entered.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    run_async(scenario())


def test_mock_never_constructs_default_network_client(adapter_settings):
    with pytest.raises(SettingsError, match="injected"):
        OpenAIAnalysisAdapter(adapter_settings)


def test_new_failure_details_remain_compatible():
    failure = AnalysisFailure(kind="API_ERROR", api_error="AUTHENTICATION", http_status=401)
    assert failure.reason_code == "EVALUATION_FAILED"
    with pytest.raises(ValidationError):
        AnalysisFailure(kind="TIMEOUT", api_error="AUTHENTICATION")
    assert AnalysisTelemetry(gateway="GMS", request_id="req-1").source == "OPENAI"


def test_live_factory_uses_gms_key_and_closes_client(run_async, monkeypatch, prompt, response_body):
    settings = load_openai_settings({
        "FDS_MODE": "LIVE", "GMS_KEY": "synthetic-live-key",
        "FDS_OPENAI_TIMEOUT_SECONDS": "1", "FDS_EVALUATION_TIMEOUT_SECONDS": "2",
    })
    for name, value in {
        "OPENAI_API_KEY": "private-direct-key", "OPENAI_ADMIN_KEY": "private-admin-key",
        "OPENAI_BASE_URL": "https://api.openai.com/v1", "OPENAI_ORG_ID": "private-org",
        "OPENAI_PROJECT_ID": "private-project", "HTTPS_PROXY": "https://invalid.example",
        "OPENAI_CUSTOM_HEADERS": "Authorization: Bearer private-header-key\nHost: invalid.example\nX-Private: private-value",
    }.items():
        monkeypatch.setenv(name, value)
    clients = []
    def handler(request):
        assert str(request.url) == GMS_CHAT_COMPLETIONS_URL
        assert request.headers["authorization"] == "Bearer synthetic-live-key"
        assert request.headers["host"] == "gms.ssafy.io"
        assert "private-" not in str(request.headers)
        return httpx2.Response(200, json=response_body)
    class OfflineClient(httpx2.AsyncClient):
        def __init__(self, **kwargs):
            assert kwargs["trust_env"] is False
            assert kwargs["follow_redirects"] is False
            super().__init__(**kwargs, transport=httpx2.MockTransport(handler))
            clients.append(self)
    monkeypatch.setattr(httpx2, "AsyncClient", OfflineClient)
    async def scenario():
        async with OpenAIAnalysisAdapter(settings) as adapter:
            assert adapter._client.max_retries == 0
            assert str(adapter._client.base_url).rstrip("/") == GMS_OPENAI_BASE_URL
            result = await adapter.complete(prompt, budget=adapter.new_budget())
            # This exercises the LIVE construction path with a replaced transport,
            # not a real GMS call or proof of gateway capabilities.
            assert result.telemetry.gateway == "GMS" and result.status == "SUCCESS"
        assert clients[0].is_closed
    run_async(scenario())


def test_injected_client_cannot_claim_live_provenance(run_async):
    settings = load_openai_settings({
        "FDS_MODE": "LIVE", "GMS_KEY": "synthetic",
        "FDS_OPENAI_TIMEOUT_SECONDS": "1", "FDS_EVALUATION_TIMEOUT_SECONDS": "2",
    })
    async def scenario():
        async with AsyncOpenAI(api_key="synthetic") as client:
            with pytest.raises(SettingsError, match="labelled MOCK"):
                OpenAIAnalysisAdapter(settings, client=client)
    run_async(scenario())


def test_client_ownership_and_closed_adapter(run_async, adapter_settings, prompt):
    async def scenario():
        async with AsyncOpenAI(api_key="synthetic") as client:
            adapter = OpenAIAnalysisAdapter(adapter_settings, client=client)
            await adapter.aclose()
            await adapter.aclose()
            assert not client.is_closed()
            with pytest.raises(ValueError, match="closed"):
                await adapter.complete(prompt, budget=adapter.new_budget())
    run_async(scenario())


def test_budget_larger_than_config_is_rejected_before_call(run_async, adapter_settings, prompt):
    def forbidden(request):
        pytest.fail("oversized budget must not dispatch")
    with pytest.raises(ValueError, match="exceeds"):
        run_async(invoke(adapter_settings, prompt, forbidden, budget=AnalysisBudget.start(3)))


@pytest.mark.parametrize("seconds", [0, -1, float("inf"), float("nan"), True, "5"])
def test_invalid_budget(seconds):
    with pytest.raises(ValueError):
        AnalysisBudget.start(seconds)


def test_response_after_shared_deadline_is_timeout(run_async, adapter_settings, prompt, response_body):
    now = [10.0]
    budget = AnalysisBudget.start(2, clock=lambda: now[0])
    def handler(request):
        now[0] += 3
        return httpx2.Response(200, json=response_body)
    result = run_async(invoke(adapter_settings, prompt, handler, budget=budget))
    assert result.failure.kind == "TIMEOUT" and result.payload is None


def test_duplicate_payload_fields_rejected(run_async, adapter_settings, prompt, response_body):
    response_body["choices"][0]["message"]["content"] = '{"items":[],"items":null}'
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.kind == "SCHEMA_ERROR"


def test_sdk_logs_suppressed_only_during_our_call(run_async, adapter_settings, prompt, response_body, caplog):
    caplog.set_level(logging.DEBUG)
    logger = logging.getLogger("openai._base_client")
    def handler(request):
        logger.debug("private SDK response text")
        logging.getLogger("application").info("application still logs")
        return httpx2.Response(200, json=response_body)
    run_async(invoke(adapter_settings, prompt, handler))
    logger.debug("outside provider call")
    assert "private SDK" not in caplog.text
    assert "application still logs" in caplog.text and "outside provider call" in caplog.text


def test_not_run_contract_rejects_request_id(extraction_result_payload):
    extraction_result_payload.update(status="NOT_RUN", payload=None, reasons=[{
        "code": "NOT_EXECUTED", "source": "MOCK",
    }])
    extraction_result_payload["metadata"]["evaluated_at"] = None
    extraction_result_payload["metadata"]["valid_until"] = None
    extraction_result_payload["telemetry"] = {"gateway": "MOCK", "request_id": "req-impossible"}
    with pytest.raises(ValidationError):
        IntentExtractionResult.model_validate(extraction_result_payload)


@pytest.mark.parametrize("status", [200, 401])
def test_outcome_maps_to_common_analysis_contract(
    run_async, adapter_settings, prompt, response_body, analysis_request, status,
):
    completion = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(status, json=response_body)))
    metadata = EvaluationMetadata(
        target=analysis_request.target, trace_id="synthetic-caller-trace",
        sources=[completion.telemetry.source], evaluated_at=completion.completed_at,
        model_version=completion.response_model, prompt_version=completion.prompt_version,
    )
    reasons = [] if completion.failure is None else [EvaluationReason(
        code=completion.failure.reason_code, source=completion.telemetry.source,
    )]
    result = IntentExtractionResult(
        status=completion.status, metadata=metadata, reasons=reasons,
        payload=completion.payload, failure=completion.failure, telemetry=completion.telemetry,
    ).validate_for_request(analysis_request)
    assert result.status == completion.status
    assert result.failure is completion.failure
    assert result.telemetry is completion.telemetry
    if status == 200:
        assert result.to_evaluation_request(analysis_request).intent.purchase_request_id == analysis_request.target.purchase_request_id
    else:
        assert result.failure.api_error == "AUTHENTICATION"


def test_unexpected_success_status_is_not_normalized_to_200(run_async, adapter_settings, prompt, response_body):
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(202, json=response_body)))
    assert result.failure.http_status == 202 and result.payload is None


@pytest.mark.parametrize("content,phase,code,path", [
    ('{', "PAYLOAD_JSON", "INVALID_JSON", ()),
    ('{"items":null,"items":[]}', "PAYLOAD_JSON", "DUPLICATE_FIELD", ()),
    ('{}', "PAYLOAD_SCHEMA", "MISSING_FIELD", ("items",)),
    ('{"comparison":"MISMATCH","findings":[],"confirmation_requests":[]}',
     "COMPARISON_STATE", "STATE_CONFLICT", ()),
])
def test_diagnostics_distinguish_json_fields_and_comparison_state(
    run_async, adapter_settings, response_body, content, phase, code, path,
):
    stage = "comparison" if phase == "COMPARISON_STATE" else "extraction"
    prompt = CompletionPrompt(stage=stage, developer_message="test", user_message="test", version="test")
    response_body["choices"][0]["message"]["content"] = content
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    diagnostic = result.failure.diagnostics[0]
    assert result.failure.kind == "SCHEMA_ERROR" and result.failure.http_status == 200
    assert (diagnostic.phase, diagnostic.code, diagnostic.path) == (phase, code, path)
    assert result.telemetry.usage.total_tokens == 30


def test_diagnostic_locations_do_not_leak_unknown_keys_values_or_context(
    run_async, adapter_settings, prompt, response_body, extraction_payload, caplog,
):
    caplog.set_level(logging.DEBUG)
    extraction_payload["items"][0]["quantity"] = "synthetic-private-quantity"
    extraction_payload["synthetic-private-field"] = "synthetic-private-value"
    response_body["choices"][0]["message"]["content"] = json.dumps(extraction_payload)
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert {d.path for d in result.failure.diagnostics} == {("items", 0, "quantity"), ("*",)}
    assert {d.code for d in result.failure.diagnostics} == {"INVALID_TYPE", "EXTRA_FIELD"}
    assert "synthetic-private" not in result.model_dump_json() + repr(result) + caplog.text


def test_diagnostics_distinguish_wire_json_and_usage(run_async, adapter_settings, prompt, response_body):
    bad_json = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, content=b'{')))
    assert bad_json.failure.diagnostics[0].phase == "RESPONSE_JSON"
    response_body["usage"] = {"total_tokens": -1}
    bad_usage = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert bad_usage.failure.diagnostics[0].phase == "USAGE"
    assert bad_usage.failure.diagnostics[0].path == ("total_tokens",)
    assert bad_usage.telemetry.usage is None


def test_duplicate_references_have_fixed_diagnostic(run_async, adapter_settings, response_body):
    prompt = CompletionPrompt(stage="comparison", developer_message="test", user_message="test", version="test")
    ref = {"order_id": "order-1", "item_id": "item-1"}
    response_body["choices"][0]["message"]["content"] = json.dumps({
        "comparison": "MISMATCH", "findings": [{"code": "INTENT_MISMATCH", "dimension": "PRODUCT",
                                                "related_items": [ref, ref]}], "confirmation_requests": [],
    })
    result = run_async(invoke(adapter_settings, prompt, lambda _: httpx2.Response(200, json=response_body)))
    assert result.failure.diagnostics[0].phase == "ITEM_REFERENCE"
    assert result.failure.diagnostics[0].code == "DUPLICATE_REFERENCE"


def test_diagnostics_are_bounded_and_legacy_failure_remains_valid():
    from copenhagen_fds.contracts.analysis_diagnostics import ValidationDiagnostic
    old = AnalysisFailure.model_validate({"kind": "SCHEMA_ERROR", "http_status": 200})
    assert old.diagnostics == ()
    with pytest.raises(ValidationError):
        ValidationDiagnostic(phase="PAYLOAD_SCHEMA", code="EXTRA_FIELD", path=["private-key"])
    with pytest.raises(ValidationError):
        AnalysisFailure(kind="TIMEOUT", diagnostics=[{"phase": "PAYLOAD_JSON", "code": "INVALID_JSON"}])
    with pytest.raises(ValidationError):
        AnalysisFailure(kind="SCHEMA_ERROR", diagnostics=[{"phase": "PAYLOAD_JSON", "code": "INVALID_JSON"}] * 9)
