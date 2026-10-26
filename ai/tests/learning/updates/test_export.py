import json

import pytest

from copenhagen_fds.contracts.profile_updates import ProfileUpdateState
from copenhagen_fds.profile_update_export import export_initial_update_state, export_profile_update, main


def inputs(tmp_path, state, request):
    state_path, request_path = tmp_path / "input-state.json", tmp_path / "input-request.json"
    state_path.write_text(state.model_dump_json(), encoding="utf-8")
    request_path.write_text(request.model_dump_json(), encoding="utf-8")
    return state_path, request_path


@pytest.mark.parametrize("population_scope", [False, True])
def test_initialize_json_and_jsonl_produce_the_same_state(updates, tmp_path, population_scope):
    base = updates.state(updates.sample("p"), population_scope=population_scope)
    snapshot = tmp_path / "snapshot.json"
    snapshot.write_text(base.snapshot.model_dump_json(indent=2), encoding="utf-8")
    report = export_initial_update_state(snapshot, tmp_path / "pretty")
    snapshot.write_text(base.snapshot.model_dump_json() + "\n", encoding="utf-8")
    other = export_initial_update_state(snapshot, tmp_path / "line")
    assert report == other
    assert ProfileUpdateState.model_validate_json((tmp_path / "line/state.json").read_text()) == base


def test_multiple_profiles_require_explicit_identity(updates, tmp_path):
    personal, population = updates.state(), updates.state(population_scope=True)
    path = tmp_path / "profiles.jsonl"
    path.write_text(personal.snapshot.model_dump_json() + "\n" + population.snapshot.model_dump_json() + "\n", encoding="utf-8")
    with pytest.raises(ValueError):
        export_initial_update_state(path, tmp_path / "missing")
    assert not (tmp_path / "missing").exists()
    report = export_initial_update_state(path, tmp_path / "selected", profile_id=personal.snapshot.profile.reference.profile_id)
    assert report["state_version"] == personal.state_version


def test_replay_cli_is_repeatable_without_overwriting_inputs(updates, tmp_path, capsys):
    base = updates.state()
    state_path, request_path = inputs(tmp_path, base, updates.request(base))
    samples_path = tmp_path / "samples.jsonl"
    samples_path.write_text(updates.sample("p").model_dump_json() + "\n", encoding="utf-8")
    before = state_path.read_bytes()
    command = ["replay", str(state_path), str(request_path), str(samples_path), "--output"]
    assert main(command + [str(tmp_path / "one")]) == 0
    assert main(command + [str(tmp_path / "two")]) == 0
    for path in (tmp_path / "one").iterdir():
        assert path.read_bytes() == (tmp_path / "two" / path.name).read_bytes()
    report = json.loads((tmp_path / "one/report.json").read_text())
    assert report["full_replay_comparison"]["matched"]
    assert report["events"] == {"APPLIED": 1}
    assert report["model_scored"] is False
    assert state_path.read_bytes() == before
    assert main(command + [str(tmp_path / "one")]) == 2
    assert "Profile update export failed" in capsys.readouterr().err


@pytest.mark.parametrize("kind,issue", [("version", "VERSION_MISMATCH"), ("retention", "RETENTION_GAP")])
def test_rejected_or_replay_required_exports_no_replacement_state(updates, tmp_path, kind, issue):
    base = updates.state()
    changes = {"expected_state_version": "stale"} if kind == "version" else {"personal_config": {"long_term_days": 91}}
    state_path, request_path = inputs(tmp_path, base, updates.request(base, **changes))
    output = tmp_path / "out"
    assert main(["apply", str(state_path), str(request_path), "--output", str(output)]) == 3
    assert not (output / "state.json").exists()
    assert json.loads((output / "report.json").read_text())["issue"] == issue


def test_explicit_apply_exports_before_after_audit_and_new_state(updates, tmp_path):
    sample = updates.sample("p", status="DEFER")
    base = updates.state(sample)
    request = updates.request(base, updates.event(updates.sample("p"), before=sample, reason="FEEDBACK"))
    state_path, request_path = inputs(tmp_path, base, request)
    report = export_profile_update(state_path, request_path, tmp_path / "out")
    result = json.loads((tmp_path / "out/result.json").read_text())
    assert report["status"] == "UPDATED" and report["event_learning_status"] == {"INCLUDE": 1}
    event = result["events"][0]
    assert event["reason"] == "FEEDBACK"
    assert event["before_content_sha256"] != event["after_content_sha256"]


def test_invalid_json_returns_generic_error_without_exposing_payload(tmp_path, capsys):
    path = tmp_path / "invalid.json"
    path.write_text('{"private": "DO_NOT_PRINT_THIS_VALUE"}', encoding="utf-8")
    assert main(["init", str(path), "--output", str(tmp_path / "out")]) == 2
    captured = capsys.readouterr()
    assert "DO_NOT_PRINT_THIS_VALUE" not in captured.out + captured.err
    assert not (tmp_path / "out").exists()
