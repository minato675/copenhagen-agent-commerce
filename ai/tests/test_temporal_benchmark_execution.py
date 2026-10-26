"""Actual native evaluation, observable-only journal and deterministic replay."""

from copy import deepcopy
import json
from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.temporal_benchmark.execution import execute, read_predictions, verify_run
from copenhagen_fds.temporal_benchmark.preparation import prepare, read_inputs
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan, SimulationConfig
from copenhagen_fds.temporal_benchmark.runner import feature_at_arrival, replay


@pytest.fixture
def prepared(tmp_path):
    settings = PersonalReplaySettings.model_validate_json(
        (Path(__file__).parents[1] / "examples" / "personal-replay-settings.json").read_text(encoding="utf-8"))
    plan = BenchmarkPlan(settings=settings, simulation=SimulationConfig(users=12, days=12,
        train_days=4, validation_days=4), purchase_limit=2)
    source = tmp_path / "source"
    prepare(plan, source)
    return source


def test_actual_native_pair_reproduces_without_opening_answers(prepared, tmp_path, monkeypatch, run_async):
    source, output = prepared, tmp_path / "run"
    original_bytes, original_text = Path.read_bytes, Path.read_text

    def read_bytes(path, *args, **kwargs):
        assert path.name != "evaluator-labels.json"
        return original_bytes(path, *args, **kwargs)

    def read_text(path, *args, **kwargs):
        assert path.name != "evaluator-labels.json"
        return original_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_bytes", read_bytes)
    monkeypatch.setattr(Path, "read_text", read_text)
    summary = run_async(execute(source, output))
    assert summary["native_evaluations"] == 4
    assert summary["external_api"] == "NOT_RUN"
    _, rows = read_predictions(source, output)
    assert len(rows) == 2
    for row in rows:
        assert set(row["arms"]) == {"cold", "personalized"}
        cold, personal = row["arms"]["cold"], row["arms"]["personalized"]
        assert cold["profile_references"] == {"personal": None, "population": None}
        assert personal["profile_references"]["personal"] is not None
        assert personal["profile_references"]["population"] is not None
        assert cold["clock"] == personal["clock"] == "SYNTHETIC_FIXED"
        assert personal["wall_duration_ms"] is None
    assert rows[1]["profile_audit"]["population"]["previous_state_version"] == rows[0]["profile_audit"]["population"]["state_version"]
    assert run_async(verify_run(source, output))["status"] == "REPRODUCED"


def test_future_conflict_is_not_validated_at_earlier_evaluation(prepared):
    _, observations, _, selected = read_inputs(prepared)
    before = feature_at_arrival(observations, selected[0])
    corrupted = deepcopy(observations)
    future = deepcopy(next(row for row in corrupted["purchases"] if row["purchase_request_id"] == selected[0]))
    future.update(available_at="2030-01-01T00:00:00+09:00", amount_minor=future["amount_minor"] + 1)
    corrupted["purchases"].append(future)
    assert feature_at_arrival(corrupted, selected[0]) == before


def test_modified_prediction_journal_cannot_be_reported(prepared, tmp_path, run_async):
    output = tmp_path / "run"
    run_async(execute(prepared, output))
    path = output / "predictions.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    rows[0]["arms"]["cold"]["behavior_score"] = 99.0
    path.write_text("\n".join(json.dumps(row) for row in rows), encoding="utf-8")
    with pytest.raises(ValueError, match="journal changed"):
        read_predictions(prepared, output)


def test_reversed_selection_cannot_advance_timeline_backwards(prepared, run_async):
    plan, observations, semantic, selected = read_inputs(prepared)

    async def run():
        return [row async for row in replay(plan, observations, semantic, tuple(reversed(selected)))]

    with pytest.raises(ValueError, match="chronology"):
        run_async(run())
