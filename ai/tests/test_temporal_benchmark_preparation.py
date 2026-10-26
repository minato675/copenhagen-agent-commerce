"""Immutable preparation and the observable-only execution reader."""

import json
from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.temporal_benchmark.preparation import prepare, read_inputs, verify_inputs
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan, SimulationConfig


@pytest.fixture
def plan():
    settings = PersonalReplaySettings.model_validate_json(
        (Path(__file__).parents[1] / "examples" / "personal-replay-settings.json").read_text(encoding="utf-8"))
    return BenchmarkPlan(settings=settings, simulation=SimulationConfig(users=12, days=12,
        train_days=4, validation_days=4), purchase_limit=8)


def test_preparation_is_deterministic_and_never_executes_model(tmp_path, plan):
    first, second = tmp_path / "one", tmp_path / "two"
    summary = prepare(plan, first)
    prepare(plan, second)
    assert summary["model_scored"] is False
    assert summary["external_api"] == "NOT_RUN"
    assert summary["human_review"] == "NOT_PERFORMED"
    assert {p.name: p.read_bytes() for p in first.iterdir()} == {p.name: p.read_bytes() for p in second.iterdir()}
    assert verify_inputs(first)["status"] == "VERIFIED"
    with pytest.raises(ValueError, match="new directory"):
        prepare(plan, first)


def test_execution_reader_never_opens_answer_file(tmp_path, plan, monkeypatch):
    output = tmp_path / "inputs"
    prepare(plan, output)
    original_bytes, original_text = Path.read_bytes, Path.read_text

    def read_bytes(path, *args, **kwargs):
        assert path.name != "evaluator-labels.json", "execution opened evaluator answers"
        return original_bytes(path, *args, **kwargs)

    def read_text(path, *args, **kwargs):
        assert path.name != "evaluator-labels.json", "execution opened evaluator answers"
        return original_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_bytes", read_bytes)
    monkeypatch.setattr(Path, "read_text", read_text)
    frozen, observations, semantic, selected = read_inputs(output)
    assert frozen == plan
    assert not {"truth", "personas"} & set(observations)
    assert set(semantic) == set(selected)


def test_observable_corruption_is_rejected_before_execution(tmp_path, plan):
    output = tmp_path / "inputs"
    prepare(plan, output)
    path = output / "observations.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["purchases"][0]["amount_minor"] += 1
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="artifact changed"):
        read_inputs(output)


def test_answers_can_be_unavailable_during_prediction(tmp_path, plan):
    output = tmp_path / "inputs"
    prepare(plan, output)
    (output / "evaluator-labels.json").rename(output / "answers-held-by-evaluator.json")
    assert read_inputs(output)[3]
    with pytest.raises(FileNotFoundError):
        verify_inputs(output)


def test_manifest_cannot_redirect_reader_outside_bundle(tmp_path, plan):
    output = tmp_path / "inputs"
    prepare(plan, output)
    path = output / "manifest.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["artifact_sha256"]["../other.json"] = "0" * 64
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError, match="inventory"):
        read_inputs(output)
