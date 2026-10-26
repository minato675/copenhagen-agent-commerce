"""Standalone offline commands: no keys, network, raw inputs or implicit live run."""

import json
import os
from pathlib import Path
import subprocess
import sys


def invoke(*args):
    environment = {**os.environ, "FDS_MODE": "LIVE", "GMS_KEY": "synthetic-unused-key",
                   "FDS_OPENAI_TIMEOUT_SECONDS": "invalid-process-setting"}
    return subprocess.run(
        [sys.executable, "-m", "copenhagen_fds.evaluation", *map(str, args)],
        cwd=Path(__file__).resolve().parents[2], env=environment,
        capture_output=True, text=True, encoding="utf-8", timeout=30,
    )


def test_mock_cli_ignores_live_environment_and_writes_only_safe_report(tmp_path):
    output = tmp_path / "report.json"
    result = invoke("mock", "--output", output)
    assert result.returncode == 0, result.stderr
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["source"] == "MOCK" and report["evaluated_case_count"] == 35
    assert report["imported_response_metrics"] is None
    assert "synthetic-private" not in output.read_text(encoding="utf-8") + result.stdout + result.stderr
    assert "synthetic-unused-key" not in result.stdout + result.stderr


def test_score_command_does_not_relabel_mock_batch(batch, tmp_path):
    path = tmp_path / "observations.json"
    path.write_text(batch.model_dump_json(), encoding="utf-8")
    result = invoke("score-gms", "--observations", path)
    assert result.returncode == 2 and "Evaluation failed" in result.stderr
    assert "synthetic" not in result.stdout + result.stderr


def test_invalid_observation_json_does_not_echo_payload_or_traceback(tmp_path):
    path = tmp_path / "observations.json"
    path.write_text('{"source":"GMS_IMPORTED","source":"synthetic-private-secret"}', encoding="utf-8")
    result = invoke("score-gms", "--observations", path)
    assert result.returncode == 2
    assert "synthetic-private" not in result.stdout + result.stderr
    assert "Traceback" not in result.stderr


def test_imported_scoring_returns_nonzero_for_failed_rubric_without_new_calls(dataset, batch, tmp_path):
    # This is an artificial imported-format fixture, not a real GMS observation.
    observation = batch.observations[0].model_dump(mode="json")
    for name in ("extraction", "comparison"):
        stage = observation["run"][name]
        stage["metadata"]["sources"] = ["BE", "FDS", "OPENAI"]
        stage["metadata"]["model_version"] = "synthetic-imported-model"
        stage["telemetry"]["gateway"] = "GMS"
    observation["run"]["extraction"]["payload"]["items"][0]["quantity"] = 3
    path, output = tmp_path / "observations.json", tmp_path / "report.json"
    path.write_text(json.dumps({
        "dataset_version": dataset.version, "dataset_sha256": dataset.sha256,
        "source": "GMS_IMPORTED", "observations": [observation],
    }), encoding="utf-8")
    result = invoke("score-gms", "--observations", path, "--output", output)
    assert result.returncode == 1
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["source"] == "GMS_IMPORTED" and report["mock_regression_metrics"] is None
    assert report["imported_response_metrics"]["case_checks"]["passed"] == 0
    assert "NOT_INDEPENDENTLY_VERIFIED" in report["interpretation"]


def test_report_cannot_overwrite_original_observations(batch, tmp_path):
    path = tmp_path / "observations.json"
    original = batch.model_dump_json()
    path.write_text(original, encoding="utf-8")
    result = invoke("score-gms", "--observations", path, "--output", path)
    assert result.returncode == 2 and path.read_text(encoding="utf-8") == original


def test_live_execution_is_not_a_cli_mode():
    result = invoke("live")
    assert result.returncode == 2 and "invalid choice" in result.stderr
