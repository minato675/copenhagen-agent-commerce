"""Bounded offline GMS planning and collector compatibility (S15P21A207-360)."""

from copy import deepcopy
import json
from pathlib import Path

import httpx2
from openai import AsyncOpenAI
import pytest

from copenhagen_fds.analysis import OpenAIAnalysisAdapter
from copenhagen_fds.evaluation.offline import deny_network
from copenhagen_fds.openai_settings import load_openai_settings
from copenhagen_fds.temporal_benchmark import gms
from copenhagen_fds.temporal_benchmark.preparation import prepare, read_inputs
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan


@pytest.fixture(scope="module")
def source(tmp_path_factory):
    settings = json.loads((Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json").read_text())
    plan = BenchmarkPlan.model_validate(dict(settings=settings,
        simulation=dict(users=24, days=18, train_days=8, validation_days=5), purchase_limit=80))
    path = tmp_path_factory.mktemp("gms-input-parent") / "inputs"
    prepare(plan, path)
    return path


def test_sample_is_frozen_by_observable_order_and_plans_are_bounded(source, tmp_path):
    with deny_network():
        summary = gms.prepare_gms_plan(source, tmp_path / "plans")
        plans = gms.read_gms_plans(tmp_path / "plans")
    _, _, semantic, selected = read_inputs(source)
    eligible = [pid for pid in selected if semantic[pid].analysis.user_request.text is not None]
    sample = json.loads((tmp_path / "plans/sample.json").read_text())
    assert sample["purchase_ids"] == eligible[:12]
    assert summary["api_calls"] == 0 and summary["max_http_requests"] == 48
    assert summary["human_review"] == "NOT_PERFORMED" and summary["quality_targets"] == "NOT_CLAIMED"
    assert len(plans) == 2 and plans[0].case_ids == plans[1].case_ids
    assert plans[0].extraction_input_hashes == plans[1].extraction_input_hashes
    assert plans[0].experiment_id != plans[1].experiment_id
    assert all(plan.repetitions == 1 and plan.mode == "PIPELINE" and plan.human_review is None for plan in plans)
    for case in plans[0].dataset.cases:
        assert case.request == semantic[case.request.context.purchase_request_id].analysis
        assert case.mock_extraction.budget is None
        assert not case.case_id.startswith("fds-")
        case.mock_comparison.validate_for_request(case.request.with_extracted_intent(case.mock_extraction))


def test_plan_preparation_is_deterministic_and_does_not_overwrite(source, tmp_path):
    first = gms.prepare_gms_plan(source, tmp_path / "a", limit=3, repetitions=1)
    second = gms.prepare_gms_plan(source, tmp_path / "b", limit=3, repetitions=1)
    assert first == second and first["max_http_requests"] == 6
    assert gms.read_gms_plans(tmp_path / "a") == gms.read_gms_plans(tmp_path / "b")
    with pytest.raises(ValueError, match="new directory"):
        gms.prepare_gms_plan(source, tmp_path / "a")
    with pytest.raises(ValueError, match="overlap"):
        gms.prepare_gms_plan(source, source / "plans")


@pytest.mark.parametrize("limit,repetitions", [(0, 2), (13, 2), (True, 2), (1.0, 2), (1, 0), (1, 3), (1, True)])
def test_invalid_budgets_fail_before_reading_source(tmp_path, limit, repetitions):
    with pytest.raises(ValueError, match="integer"):
        gms.prepare_gms_plan(tmp_path / "absent", tmp_path / "out", limit, repetitions)


def test_reference_corpus_cannot_be_fabricated_from_too_few_cases(source, monkeypatch):
    original = gms.read_inputs

    def shortened(path):
        plan, observations, semantic, selected = original(path)
        return plan, observations, semantic, selected[:10]

    monkeypatch.setattr(gms, "read_inputs", shortened)
    with pytest.raises(ValueError, match="at least 20"):
        gms.build_gms_dataset(source)


def test_fraud_truth_and_personas_do_not_control_sampling_or_semantic_dataset(source, monkeypatch):
    original = gms.read_targets
    baseline = gms.build_gms_dataset(source)

    def no_fraud_labels(path):
        targets = original(path)
        targets["truth"] = object()
        targets["personas"] = object()
        return targets

    monkeypatch.setattr(gms, "read_targets", no_fraud_labels)
    assert gms.build_gms_dataset(source) == baseline


def test_foreign_reference_payload_is_rejected_before_plan_generation(source, monkeypatch):
    original = gms.read_targets

    def foreign_ref(path):
        targets = deepcopy(original(path))
        for reference in targets["semantic"].values():
            comparison = reference["expected_comparison"]
            if comparison and comparison["findings"]:
                comparison["findings"][0]["related_items"][0]["item_id"] = "foreign-item"
                break
        return targets

    monkeypatch.setattr(gms, "read_targets", foreign_ref)
    with pytest.raises(ValueError):
        gms.build_gms_dataset(source)


@pytest.mark.parametrize("artifact", ["sample.json", "dataset.json", "round-1.plan.json"])
def test_corrupt_plan_artifacts_fail_verification(source, tmp_path, artifact):
    output = tmp_path / "plans"
    gms.prepare_gms_plan(source, output)
    with (output / artifact).open("a") as handle:
        handle.write(" ")
    with pytest.raises(ValueError, match="artifact changed"):
        gms.read_gms_plans(output)


async def _scripted_round(plan, output, *, reject_access=False):
    cases = {case.case_id: case for case in plan.dataset.cases}
    sent = []

    async def handler(request):
        body = json.loads(request.content)
        stage = "extraction" if "extraction" in body["response_format"]["json_schema"]["name"] else "comparison"
        case = cases[plan.case_ids[len(sent) // 2]]
        sent.append(stage)
        if reject_access:
            return httpx2.Response(401, json={"error": {"message": "synthetic access rejection"}})
        payload = case.mock_extraction if stage == "extraction" else case.mock_comparison
        return httpx2.Response(200, json={"id": f"mock-{len(sent)}", "model": "synthetic-fixed-model",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
            "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": payload.model_dump_json()}}]})

    settings = load_openai_settings({"FDS_MODE": "MOCK", "FDS_OPENAI_TIMEOUT_SECONDS": "60",
                                    "FDS_EVALUATION_TIMEOUT_SECONDS": "120"})
    async with AsyncOpenAI(api_key="synthetic-offline-key", http_client=httpx2.AsyncClient(
        transport=httpx2.MockTransport(handler), follow_redirects=False, trust_env=False,
    )) as sdk:
        batch, report = await gms.collect_temporal_gms(plan, OpenAIAnalysisAdapter(settings, client=sdk), output)
    return batch, sent, report


def test_existing_collector_and_grader_accept_temporal_cases_without_quality_claim(source, tmp_path, run_async):
    gms.prepare_gms_plan(source, tmp_path / "plans", limit=12, repetitions=1)
    plan, = gms.read_gms_plans(tmp_path / "plans")
    with deny_network():
        batch, sent, report = run_async(_scripted_round(plan, tmp_path / "observations"))
    assert sent == ["extraction", "comparison"] * 12
    assert report["complete"] and report["metrics"]["label_accuracy"]["rate"] == 1
    assert report["metrics"]["extraction_field_checks"]["rate"] == 1
    assert report["metrics"]["reason_checks"]["paired"]["rate"] == 1
    assert report["quality_targets"] == "NOT_CLAIMED"
    assert report["mock_is_model_quality"] is False
    assert report["latency_is_gms_measurement"] is False
    assert report["interpretation"] == gms.INTERPRETATION
    assert report["provider_metrics"] is None and report["billed_cost"] is None
    assert json.loads((tmp_path / "observations/report.json").read_text()) == report


@pytest.mark.parametrize("change", ["plan", "input_hash", "journal", "model_version"])
def test_imported_round_tampering_is_rejected(source, tmp_path, run_async, change):
    gms.prepare_gms_plan(source, tmp_path / "plans", limit=2, repetitions=1)
    plan, = gms.read_gms_plans(tmp_path / "plans")
    batch, _, _ = run_async(_scripted_round(plan, tmp_path / "observations"))
    data = batch.model_dump(mode="json")
    if change == "plan":
        data["plan_sha256"] = "0" * 64
    elif change == "input_hash":
        data["observations"][0]["extraction_input_sha256"] = "0" * 64
    elif change == "journal":
        data["requests"].pop(0)
        for number, request in enumerate(data["requests"], 1):
            request["sequence"] = number
    else:
        data["observations"][0]["run"]["comparison"]["metadata"]["model_version"] = "different-model"
    from copenhagen_fds.evaluation.experiments import ExperimentBatch
    altered = ExperimentBatch.model_validate_json(json.dumps(data))
    with pytest.raises(ValueError):
        gms.summarize_temporal_round(plan, altered)


def test_missing_round_trials_remain_in_diagnostic_denominators(source, tmp_path, run_async):
    gms.prepare_gms_plan(source, tmp_path / "plans", limit=2, repetitions=1)
    plan, = gms.read_gms_plans(tmp_path / "plans")
    batch, _, _ = run_async(_scripted_round(plan, tmp_path / "observations"))
    from copenhagen_fds.evaluation.experiments import ExperimentBatch
    data = batch.model_dump(mode="json")
    data["observations"].pop()
    data.update(status="STOPPED", stop_reason="CALL_LIMIT")
    partial = ExperimentBatch.model_validate_json(json.dumps(data))
    report = gms.summarize_temporal_round(plan, partial)
    assert report["metrics"]["label_accuracy"] == dict(passed=1, total=2, rate=0.5)
    assert report["metrics"]["semantic_result_coverage"] == dict(passed=1, total=2, rate=0.5)
    assert report["metrics"]["outcomes"]["MISSING"] == 1
    assert report["requests_without_observation"] == 2
    assert not report["complete"]


def test_collector_access_failure_is_preserved_and_remaining_cases_are_missing(source, tmp_path, run_async):
    gms.prepare_gms_plan(source, tmp_path / "plans", limit=2, repetitions=1)
    plan, = gms.read_gms_plans(tmp_path / "plans")
    batch, sent, report = run_async(_scripted_round(plan, tmp_path / "observations", reject_access=True))
    assert sent == ["extraction"] and len(batch.requests) == 1
    assert report["status"] == "STOPPED" and report["stop_reason"] == "PROVIDER_ACCESS"
    assert report["metrics"]["outcomes"] == {"FAILED": 1, "MISSING": 1}
    assert report["metrics"]["label_accuracy"] == dict(passed=0, total=2, rate=0.0)
    assert report["metrics"]["semantic_result_coverage"] == dict(passed=0, total=2, rate=0.0)
    assert "synthetic access rejection" not in json.dumps(report)
    assert not report["complete"]
