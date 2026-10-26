import json
from pathlib import Path

import pytest

from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState
from copenhagen_fds.profile_policy_export import export_policy_initial, export_policy_observations, main, read_policy_observations
from copenhagen_fds.synthetic.generator import GeneratorConfig
from copenhagen_fds.synthetic.storage import write_dataset


def write_rows(path, rows):
    path.write_text("".join(row.model_dump_json() + "\n" for row in rows), encoding="utf-8")


@pytest.mark.parametrize("population_scope", [False, True])
def test_initial_export_is_deterministic_and_separates_policy_holds(policy, tmp_path, population_scope):
    rows = (*policy.baseline(), policy.row("large", amount=10000))
    snapshot, observations = tmp_path / "snapshot.json", tmp_path / "observations.jsonl"
    snapshot.write_text(policy.snapshot(rows, population_scope=population_scope).model_dump_json(indent=2), encoding="utf-8")
    write_rows(observations, rows)
    one = export_policy_initial(snapshot, observations, tmp_path / "one", config=policy.config())
    two = export_policy_initial(snapshot, observations, tmp_path / "two", config=policy.config())
    assert one == two
    assert one["policy_held_confirmed"] == 1 and one["published_long_included"] == 3
    for file in (tmp_path / "one").iterdir():
        assert file.read_bytes() == (tmp_path / "two" / file.name).read_bytes()
    with pytest.raises(FileExistsError):
        export_policy_initial(snapshot, observations, tmp_path / "one", config=policy.config())


def test_update_cli_preserves_source_and_exports_changed_admission(policy, tmp_path):
    rows = (policy.row("p", status="DEFER"),)
    snapshot, observations = tmp_path / "snapshot.json", tmp_path / "observations.jsonl"
    snapshot.write_text(policy.snapshot(rows).model_dump_json(), encoding="utf-8")
    write_rows(observations, rows)
    export_policy_initial(snapshot, observations, tmp_path / "initial", config=policy.config())
    state_path = tmp_path / "initial/state.json"
    before = state_path.read_bytes()
    state = ProfilePolicyState.model_validate_json(before)
    profile = state.candidate.snapshot.profile
    request = {"expected_policy_state_version": state.state_version, "update": {
        "expected_state_version": state.candidate.state_version,
        "expected_profile_reference": profile.reference.model_dump(mode="json"),
        "as_of": profile.as_of.isoformat(), "generated_at": profile.generated_at.isoformat(), "valid_until": profile.valid_until.isoformat(),
        "changes_complete_from": profile.as_of.isoformat(), "changes_complete_until": profile.as_of.isoformat(),
    }}
    request_path = tmp_path / "request.json"
    request_path.write_text(json.dumps(request), encoding="utf-8")
    write_rows(observations, (policy.row("p"),))
    command = ["update", str(state_path), str(request_path), str(observations), "--output"]
    assert main(command + [str(tmp_path / "updated")]) == 0
    assert json.loads((tmp_path / "updated/changes.json").read_text())["policy_transitions"][0]["action"] == "ADMITTED"
    assert state_path.read_bytes() == before
    request["expected_policy_state_version"] = "stale"
    request_path.write_text(json.dumps(request), encoding="utf-8")
    assert main(command + [str(tmp_path / "rejected")]) == 3
    assert not (tmp_path / "rejected/state.json").exists()
    assert json.loads((tmp_path / "rejected/report.json").read_text())["issue"] == "POLICY_VERSION_MISMATCH"


def test_observable_export_never_opens_truth_or_personas(tmp_path, monkeypatch):
    dataset = tmp_path / "dataset"
    write_dataset(dataset, GeneratorConfig(users=12, days=12, train_days=4, validation_days=4), suite="stream")
    original = Path.read_text

    def guarded_read(path, *args, **kwargs):
        assert path.name not in {"truth.jsonl", "truth.csv", "personas.jsonl"}
        return original(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", guarded_read)
    report = export_policy_observations(dataset, tmp_path / "evidence", as_of="2026-01-13T00:00:00+09:00")
    rows = read_policy_observations(tmp_path / "evidence/observations.jsonl")
    assert len(rows) == report["observations"] and report["model_scored"] is False
    assert all(row.feedback is None or row.feedback.feedback_id == row.sample.eligibility.feedback_id for row in rows)


def test_cli_error_does_not_print_invalid_payload(tmp_path, capsys):
    bad = tmp_path / "bad.json"
    bad.write_text('{"private": "NOT_FOR_LOG_OUTPUT"}', encoding="utf-8")
    assert main(["init", str(bad), str(bad), "--output", str(tmp_path / "out")]) == 2
    output = capsys.readouterr()
    assert "NOT_FOR_LOG_OUTPUT" not in output.out + output.err
    assert not (tmp_path / "out").exists()
