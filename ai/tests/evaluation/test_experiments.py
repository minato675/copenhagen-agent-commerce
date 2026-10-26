"""Experiment integrity and denominators, using scripted SDK responses only."""

import asyncio
from copy import deepcopy
import json

import httpx2
from openai import AsyncOpenAI
import pytest

from copenhagen_fds.analysis import OpenAIAnalysisAdapter
from copenhagen_fds.evaluation.collection import collect
from copenhagen_fds.evaluation.experiment_cli import read_model
from copenhagen_fds.evaluation.experiment_scoring import score_experiment
from copenhagen_fds.evaluation.experiments import ExperimentBatch, ExperimentPlan, make_plan
from copenhagen_fds.evaluation.offline import deny_network
from copenhagen_fds.openai_settings import load_openai_settings


def plan_for(dataset, *, repeated=False, cap=10, repetitions=1):
    return make_plan(dataset, experiment_id="offline-experiment", case_ids=("fds-01-clear",),
                     mode="COMPARISON_REPEAT" if repeated else "PIPELINE",
                     repetitions=repetitions, max_http_requests=cap)


async def scripted_collect(plan, output, responder=None):
    cases = {c.case_id: c for c in plan.dataset.cases}
    case = cases[plan.case_ids[0]]
    sent = []

    async def handler(request):
        body = json.loads(request.content)
        stage = "extraction" if "extraction" in body["response_format"]["json_schema"]["name"] else "comparison"
        sent.append(stage)
        if responder:
            response = responder(len(sent), stage)
            if response is not None:
                return response
        payload = (case.mock_extraction if stage == "extraction" else case.mock_comparison).model_dump(mode="json")
        return sdk_response(payload)

    settings = load_openai_settings({"FDS_MODE": "MOCK", "FDS_OPENAI_TIMEOUT_SECONDS": "60",
                                     "FDS_EVALUATION_TIMEOUT_SECONDS": "120"})
    async with AsyncOpenAI(api_key="synthetic-offline-key", http_client=httpx2.AsyncClient(
        transport=httpx2.MockTransport(handler), follow_redirects=False, trust_env=False,
    )) as sdk:
        adapter = OpenAIAnalysisAdapter(settings, client=sdk)
        result = await collect(plan, adapter, output)
    return result, sent


def sdk_response(payload):
    return httpx2.Response(200, json={"id": "mock-evaluation", "model": "synthetic-fixed-model",
        "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13},
        "choices": [{"finish_reason": "stop", "message": {"role": "assistant", "content": json.dumps(payload)}}]})


def test_pipeline_roundtrip_reuses_legacy_grader_without_network(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)
    with deny_network():
        batch, sent = run_async(scripted_collect(plan, tmp_path / "run"))
        report = score_experiment(plan, batch)
    assert sent == ["extraction", "comparison"]
    assert report["complete"] and report["metrics"]["label_accuracy"]["rate"] == 1
    assert report["provider_metrics"] is None and report["http_request_count"] == 2
    assert report["timing"]["two_stage_pipeline_ms"]["count"] == 1
    assert read_model(tmp_path / "run" / "observations.json", ExperimentBatch) == batch
    assert read_model(tmp_path / "run" / "plan.json", ExperimentPlan) == plan


def test_cap_stops_before_extra_dispatch_and_preserves_orphan_request(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=1)
    batch, sent = run_async(scripted_collect(plan, tmp_path / "run"))
    assert sent == ["extraction"] and batch.stop_reason == "CALL_LIMIT"
    report = score_experiment(plan, batch)
    assert report["metrics"]["label_accuracy"] == {"passed": 0, "total": 1, "rate": 0}
    assert report["missing_run_ids"] == ["fds-01-clear.r1"]
    assert report["requests_without_observation"] == 1 and not report["complete"]
    assert read_model(tmp_path / "run" / "observations.json", ExperimentBatch) == batch


def test_repeated_failure_and_missing_trials_remain_in_consistency_denominator(run_async, dataset, tmp_path):
    plan = plan_for(dataset, repeated=True, cap=4, repetitions=5)
    unknown = {"comparison": "UNKNOWN", "findings": [{"code": "INSUFFICIENT_DATA", "dimension": "CONDITION",
               "related_items": []}], "confirmation_requests": [{"code": "PROVIDE_MISSING_DATA", "related_items": []}]}

    def responder(index, stage):
        assert stage == "comparison"
        return sdk_response(unknown) if index == 3 else httpx2.Response(500) if index == 4 else None

    batch, sent = run_async(scripted_collect(plan, tmp_path / "run", responder))
    report = score_experiment(plan, batch)
    assert sent == ["comparison"] * 4
    assert report["consistency"]["passed"] == 2 and report["consistency"]["total"] == 5
    assert report["consistency"]["rate"] == .4
    assert report["metrics"]["label_accuracy"]["rate"] == .4
    assert report["metrics"]["extraction_field_checks"] is None
    assert report["metrics"]["outcomes"] == {"MATCH": 2, "UNKNOWN": 1, "FAILED": 1, "MISSING": 1}
    assert report["timing"]["scope"] == "COMPARISON_ONLY"


def test_repeated_provider_failure_stops_at_three_without_retry(run_async, dataset, tmp_path):
    plan = plan_for(dataset, repeated=True, cap=5, repetitions=5)
    batch, sent = run_async(scripted_collect(plan, tmp_path / "run", lambda *_: httpx2.Response(503)))
    assert len(sent) == 3 and batch.stop_reason == "REPEATED_FAILURE"
    report = score_experiment(plan, batch)
    assert report["consistency"]["rate"] == 0 and report["consistency"]["total"] == 5
    assert len(report["missing_run_ids"]) == 2


def test_cancellation_keeps_journal_without_raw_errors(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)

    def cancel(*_):
        raise asyncio.CancelledError()

    with pytest.raises(asyncio.CancelledError):
        run_async(scripted_collect(plan, tmp_path / "run", cancel))
    batch = read_model(tmp_path / "run" / "observations.json", ExperimentBatch)
    assert batch.status == "INTERRUPTED" and batch.stop_reason == "CANCELLED"
    assert len(batch.requests) == 1 and not batch.observations


@pytest.mark.parametrize("mutation", ["duplicate", "prompt", "model", "hash", "outside"])
def test_corrupt_or_mixed_observations_are_not_averaged(run_async, dataset, tmp_path, mutation):
    plan = plan_for(dataset, repeated=True, cap=2, repetitions=2)
    batch, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    data = batch.model_dump(mode="json")
    if mutation == "duplicate":
        data["observations"].append(deepcopy(data["observations"][0]))
    if mutation == "prompt":
        data["observations"][1]["comparison"]["metadata"]["prompt_version"] = "unexpected"
    if mutation == "model":
        data["observations"][1]["comparison"]["metadata"]["model_version"] = "other-response-model"
    if mutation == "hash":
        data["observations"][1]["comparison_input_sha256"] = "0" * 64
    if mutation == "outside":
        data["observations"][1].update(repetition=3, run_id="fds-01-clear.r3")
    with pytest.raises(ValueError):
        score_experiment(plan, ExperimentBatch.model_validate_json(json.dumps(data)))


def test_plan_hash_and_duplicate_input_checks(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)
    batch, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    changed = plan.model_copy(update={"experiment_id": "different-experiment"})
    with pytest.raises(ValueError, match="plan hash"):
        score_experiment(changed, batch)
    with pytest.raises(ValueError, match="distinct input"):
        make_plan(dataset, experiment_id="duplicate-inputs", mode="COMPARISON_REPEAT",
                  case_ids=("fds-01-clear", "fds-02-maximum"), repetitions=5, max_http_requests=10)


def test_output_directory_cannot_overwrite_previous_collection(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)
    batch, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    with pytest.raises(FileExistsError):
        run_async(scripted_collect(plan, tmp_path / "run"))
    assert read_model(tmp_path / "run" / "observations.json", ExperimentBatch) == batch


def test_live_cli_requires_exact_plan_before_loading_credentials(dataset, tmp_path, monkeypatch, capsys):
    from copenhagen_fds.evaluation import collect_gms
    plan = plan_for(dataset, cap=2)
    path = tmp_path / "plan.json"
    path.write_text(plan.model_dump_json(), encoding="utf-8")

    def forbidden(*args, **kwargs):
        pytest.fail("approval rejection must precede credentials or transport")

    monkeypatch.setattr(collect_gms, "load_openai_settings", forbidden)
    result = collect_gms.main(["--plan", str(path), "--approve-plan-sha256", "synthetic-private-value",
                               "--output-dir", str(tmp_path / "output")])
    captured = capsys.readouterr()
    assert result == 2 and "synthetic-private" not in captured.out + captured.err
    assert not (tmp_path / "output").exists()


def test_plan_scoring_cli_remains_offline_and_rejects_overwrite(run_async, dataset, tmp_path):
    from .test_cli import invoke
    plan = plan_for(dataset, repeated=True, cap=2, repetitions=2)
    mock, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    # Artificial imported-format data tests parsing, not provider provenance.
    data = mock.model_dump(mode="json")
    data["source"] = "GMS_IMPORTED"
    for row in data["observations"]:
        row["comparison"]["telemetry"]["gateway"] = "GMS"
        row["comparison"]["metadata"]["sources"] = ["BE", "FDS", "OPENAI"]
    observations = tmp_path / "imported.json"
    observations.write_text(json.dumps(data), encoding="utf-8")
    plan_file = tmp_path / "run" / "plan.json"
    report_file = tmp_path / "report.json"
    result = invoke("score-gms", "--plan", plan_file, "--observations", observations, "--output", report_file)
    assert result.returncode == 0, result.stderr
    report = json.loads(report_file.read_text(encoding="utf-8"))
    assert report["consistency"]["total"] == 2
    assert not report["quality_targets"]["eligible"]  # Not the required 20 x 5 protocol.
    before = plan_file.read_bytes()
    result = invoke("score-gms", "--plan", plan_file, "--observations", observations, "--output", plan_file)
    assert result.returncode == 2 and plan_file.read_bytes() == before


def test_preparation_is_offline_and_never_overwrites(dataset, tmp_path, capsys):
    from copenhagen_fds.evaluation.experiment_cli import main
    output = tmp_path / "plan.json"
    args = ["--experiment-id", "offline-plan", "--case-id", "fds-01-clear", "--max-calls", "2", "--output", str(output)]
    assert main(args) == 0
    original = output.read_bytes()
    assert main(args) == 2 and output.read_bytes() == original
    printed = capsys.readouterr()
    assert "synthetic-private" not in printed.out + printed.err


def test_unmeasured_or_mock_latency_is_not_a_passed_pipeline_target(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)
    batch, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    report = score_experiment(plan, batch)
    assert report["pipeline_latency_target"]["passed"] is None
    changed = batch.model_copy(update={"observations": (batch.observations[0].model_copy(update={"wall_duration_ms": None}),)})
    report = score_experiment(plan, changed)
    assert not report["pipeline_latency_target"]["evaluated"]
    assert report["timing"]["two_stage_pipeline_ms"]["unmeasured_count"] == 1
