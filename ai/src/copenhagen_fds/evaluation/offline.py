"""Full SDK/pipeline regression using scripted responses, never model inference."""

from contextlib import contextmanager
import json
import socket
from unittest.mock import patch

import httpx2
from openai import AsyncOpenAI

from copenhagen_fds.analysis import IntentAnalyzer, OpenAIAnalysisAdapter
from copenhagen_fds.contracts import analysis_response_format
from copenhagen_fds.evaluation.models import EvaluationCase, EvaluationDataset, Observation, ObservationBatch
from copenhagen_fds.openai_settings import load_openai_settings


class OfflineViolation(BaseException):
    """Cannot be swallowed by the adapter's ordinary provider error handling."""


@contextmanager
def deny_network():
    """Process-wide guard for the standalone CLI; initialize asyncio first."""
    def reject(*args, **kwargs):
        raise OfflineViolation("offline evaluation attempted network access")
    with patch.object(socket, "create_connection", reject), patch.object(socket, "getaddrinfo", reject), \
            patch.object(socket.socket, "connect", reject), patch.object(socket.socket, "connect_ex", reject):
        yield


async def run_mock_case(case: EvaluationCase) -> Observation:
    settings = load_openai_settings({
        "FDS_MODE": "MOCK", "FDS_OPENAI_MODEL": "gpt-5.4-mini",
        "FDS_OPENAI_TIMEOUT_SECONDS": "5", "FDS_EVALUATION_TIMEOUT_SECONDS": "10",
    })
    calls = []
    def handler(request):
        stage = "extraction" if not calls else "comparison"
        calls.append(stage)
        body = json.loads(request.content)
        if len(calls) > 2 or request.method != "POST" or str(request.url) != settings.openai_base_url + "/chat/completions":
            raise OfflineViolation("unexpected offline request destination or count")
        if body.get("response_format") != analysis_response_format(stage) or body.get("model") != settings.openai_model:
            raise OfflineViolation("unexpected offline model or response schema")
        if [message["role"] for message in body["messages"]] != ["developer", "user"]:
            raise OfflineViolation("unexpected prompt roles")
        fault = case.fault.kind if case.fault and case.fault.stage == stage else None
        statuses = {"authentication": 401, "rate_limit": 429, "server": 503}
        if fault in statuses:
            return httpx2.Response(statuses[fault], json={"error": {"message": "synthetic-private-provider-error"}})
        if fault == "timeout":
            raise httpx2.ReadTimeout("synthetic-private-timeout", request=request)
        payload = (case.mock_extraction if stage == "extraction" else case.mock_comparison).model_dump(mode="json")
        if fault == "schema":
            del payload["items" if stage == "extraction" else "findings"]
        if fault == "foreign_reference":
            payload = {"comparison": "MISMATCH", "findings": [{"code": "INTENT_MISMATCH", "dimension": "PRODUCT",
                       "related_items": [{"order_id": "foreign-order", "item_id": "foreign-item"}]}], "confirmation_requests": []}
        if fault == "extra_authority":
            payload["recommendation"] = "ALLOW"
        return httpx2.Response(200, json={
            "id": f"mock-{case.case_id}-{stage}", "model": "gpt-5.4-mini-mock-fixture",
            "choices": [{"finish_reason": "length" if fault == "truncated" else "stop", "message": {
                "role": "assistant", "content": json.dumps(payload, ensure_ascii=False),
                "refusal": "synthetic-private-refusal" if fault == "refusal" else None,
            }}],
        }, headers={"x-request-id": f"mock-request-{case.case_id}-{stage}"})
    async with AsyncOpenAI(api_key="synthetic-offline-key", http_client=httpx2.AsyncClient(
        transport=httpx2.MockTransport(handler), follow_redirects=False, trust_env=False,
    )) as sdk:
        run = await IntentAnalyzer(OpenAIAnalysisAdapter(settings, client=sdk)).analyze(
            case.request, trace_id=f"trace-{case.case_id}",
        )
    return Observation(case_id=case.case_id, run=run)


async def run_mock_dataset(dataset: EvaluationDataset) -> ObservationBatch:
    observations = [await run_mock_case(case) for case in dataset.cases]
    return ObservationBatch(
        dataset_version=dataset.version, dataset_sha256=dataset.sha256, source="MOCK", observations=observations,
    )
