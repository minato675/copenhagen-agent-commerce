"""Cross-stage corpus regressions, target binding, fault and injection boundaries."""

import json
import logging
import socket

import httpx2
import pytest

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_INSTRUCTIONS
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_INSTRUCTIONS
from copenhagen_fds.evaluation.cases import ATTACK, load_dataset
from copenhagen_fds.evaluation.models import EvaluationDataset, ObservationBatch
from copenhagen_fds.evaluation.offline import OfflineViolation, deny_network, run_mock_case


def test_fixed_dataset_is_reproducible_and_covers_required_categories(dataset):
    assert len(dataset.cases) == 35 and dataset.sha256 == load_dataset().sha256
    assert EvaluationDataset.model_validate_json(dataset.model_dump_json()) == dataset
    categories = {category for case in dataset.cases for category in case.categories}
    assert {"normal", "condition-mismatch", "approximate-budget", "missing-quantity", "missing-currency",
            "multiple-merchants", "unrequested-product", "substitution", "missing-data", "conflict",
            "prompt-injection", "mcp-data", "provider-failure", "refusal", "truncated", "schema"} <= categories
    for case in dataset.cases:
        assert case.rubric and case.expected.fields or case.expected.extraction_status != "SUCCESS"


@pytest.mark.parametrize("index", range(35))
def test_corpus_full_path_preserves_stage_results_and_references(dataset, batch, index):
    case, observation = dataset.cases[index], batch.observations[index]
    run = observation.run.validate_for_request(case.request)
    expected = case.expected
    assert run.extraction.status == expected.extraction_status
    assert run.comparison.status == expected.comparison_status
    assert run.to_common_result(case.request).comparison == expected.comparison
    failure = run.extraction.failure or run.comparison.failure
    assert (failure.kind if failure else None) == expected.failure_kind
    assert run.extraction.input_source == case.request.user_request.source
    assert run.extraction.metadata.target == run.comparison.metadata.target == case.request.target
    if run.extraction.payload:
        assert run.extraction.payload == case.mock_extraction
    if run.comparison.payload:
        assert run.comparison.payload == case.mock_comparison
    if run.comparison.status == "NOT_RUN":
        assert run.comparison.metadata.evaluated_at is None and run.comparison.telemetry.duration_ms is None
    assert run.extraction.telemetry.usage is None and run.comparison.telemetry.usage is None
    assert case.request.user_request.text is None or case.request.user_request.text not in run.model_dump_json()


def test_full_batch_json_roundtrip_and_dataset_content_digest(dataset, batch):
    assert ObservationBatch.model_validate_json(batch.model_dump_json()) == batch
    data = dataset.model_dump(mode="json")
    data["cases"][0]["rubric"] += " 추가 검토"
    assert EvaluationDataset.model_validate_json(json.dumps(data)).sha256 != dataset.sha256


@pytest.mark.parametrize("case_id", ["fds-20-user-injection", "fds-21-mcp-injection", "fds-22-name-injection", "fds-23-injection-mismatch"])
def test_injection_stays_in_user_data_across_real_sdk_pipeline(run_async, dataset, case_id, monkeypatch, caplog):
    caplog.set_level(logging.DEBUG)
    original = httpx2.MockTransport.handle_async_request
    captured = []
    async def observe(transport, request):
        captured.append(json.loads(request.content))
        return await original(transport, request)
    monkeypatch.setattr(httpx2.MockTransport, "handle_async_request", observe)
    case = next(case for case in dataset.cases if case.case_id == case_id)
    observation = run_async(run_mock_case(case))
    assert len(captured) == 2
    assert captured[0]["messages"][0]["content"] == EXTRACTION_INSTRUCTIONS
    assert captured[1]["messages"][0]["content"] == COMPARISON_INSTRUCTIONS
    assert all(ATTACK not in body["messages"][0]["content"] for body in captured)
    if case_id == "fds-20-user-injection":
        assert ATTACK in captured[0]["messages"][1]["content"]
        assert ATTACK not in captured[1]["messages"][1]["content"]
    else:
        assert ATTACK not in captured[0]["messages"][1]["content"]
        assert ATTACK in captured[1]["messages"][1]["content"]
    assert "synthetic-private-marker" not in observation.model_dump_json() + caplog.text
    assert observation.run.to_common_result(case.request).comparison == case.expected.comparison


def test_provider_errors_do_not_retain_raw_error_or_key(run_async, dataset, caplog):
    caplog.set_level(logging.DEBUG)
    for case in dataset.cases:
        if case.fault is not None:
            result = run_async(run_mock_case(case))
            assert "synthetic-private" not in result.model_dump_json()
    assert "synthetic-private" not in caplog.text and "synthetic-offline-key" not in caplog.text


def test_offline_guard_rejects_network_even_inside_provider_error_boundary():
    original = socket.getaddrinfo
    with deny_network():
        with pytest.raises(OfflineViolation):
            socket.getaddrinfo("example.invalid", 443)
        with socket.socket() as connection:
            with pytest.raises(OfflineViolation):
                connection.connect(("127.0.0.1", 443))
    assert socket.getaddrinfo is original
