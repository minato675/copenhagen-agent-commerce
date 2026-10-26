import json
from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest
from copenhagen_fds.personal_replay import dataset_cases
from copenhagen_fds.personal_replay_cli import main
from copenhagen_fds.personal_replay_export import code_fingerprint, export_personal_replay, verify_personal_replay
from copenhagen_fds.synthetic.generator import GeneratorConfig
from copenhagen_fds.synthetic.learning import read_learning_observations
from copenhagen_fds.synthetic.storage import canonical, write_dataset


def test_exports_are_byte_identical_and_scores_reverify(replay_cases, tmp_path):
    dataset = replay_cases.write()
    request = replay_cases.request(users=("a",))
    first = export_personal_replay(dataset, tmp_path / "one", request)
    second = export_personal_replay(dataset, tmp_path / "two", request)
    assert first == second and first["model_scored"] is True
    for file in (tmp_path / "one").iterdir():
        assert file.read_bytes() == (tmp_path / "two" / file.name).read_bytes()
    assert verify_personal_replay(tmp_path / "one")["status"] == "VERIFIED"
    summary = json.loads((tmp_path / "one/comparisons.json").read_text(encoding="utf-8"))
    assert [r["features"][0]["combined"] for r in summary["rows"]] == [0, 0, 50, 100, 0]


def test_code_version_is_independent_of_git_checkout_line_endings(monkeypatch):
    expected = code_fingerprint()
    original = Path.read_text

    def crlf(path, *args, **kwargs):
        text = original(path, *args, **kwargs)
        return text.replace("\n", "\r\n") if path.suffix == ".py" else text

    monkeypatch.setattr(Path, "read_text", crlf)
    assert code_fingerprint() == expected


@pytest.mark.parametrize("location", ["existing", "dataset", "inside", "ancestor"])
def test_output_isolation_preserves_inputs_and_existing_files(replay_cases, tmp_path, location):
    dataset = replay_cases.write()
    sentinel = tmp_path / "existing"
    sentinel.mkdir()
    (sentinel / "keep.txt").write_text("keep", encoding="utf-8")
    output = {"existing": sentinel, "dataset": dataset, "inside": dataset / "new", "ancestor": tmp_path}[location]
    before = {p.name: p.read_bytes() for p in dataset.iterdir()}
    with pytest.raises(ValueError):
        export_personal_replay(dataset, output, replay_cases.request(users=("a",)))
    assert before == {p.name: p.read_bytes() for p in dataset.iterdir()}
    assert (sentinel / "keep.txt").read_text() == "keep"


@pytest.mark.parametrize("field", ["request.json", "evaluations.jsonl", "comparisons.json", "code", "cases", "stages", "status", "scope"])
def test_tampered_artifacts_or_report_are_rejected(replay_cases, tmp_path, field):
    output = tmp_path / "output"
    export_personal_replay(replay_cases.write(), output, replay_cases.request(users=("a",)))
    if field.endswith((".json", ".jsonl")):
        with (output / field).open("a", encoding="utf-8") as handle:
            handle.write(" ")
    else:
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        if field == "code":
            report["code"]["source_sha256"] = "b" * 64
        elif field == "cases":
            report["cases"] += 1
        elif field == "stages":
            report["stages"] = {}
        elif field == "scope":
            report["truth_read"] = True
        else:
            report["status"] = "FAILED"
        (output / "report.json").write_text(canonical(report), encoding="utf-8")
    with pytest.raises(ValueError):
        verify_personal_replay(output)


def test_cli_compare_verify_show_and_no_overwrite(replay_cases, tmp_path, capsys):
    dataset = replay_cases.write()
    settings = tmp_path / "settings.json"
    settings.write_text(replay_cases.settings.model_dump_json(), encoding="utf-8")
    output = tmp_path / "out"
    command = ["compare", str(dataset), "--settings", str(settings), "--cutoffs", *replay_cases.cutoffs,
               "--expired-at", replay_cases.expired, "--amount-minor", "30000", "--users", "a", "--output", str(output)]
    assert main(command) == 0
    assert json.loads(capsys.readouterr().out)["status"] == "SCORED"
    assert main(["verify", str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["status"] == "VERIFIED"
    assert main(["show", str(output)]) == 0
    shown = capsys.readouterr().out
    assert "TRANSITION" in shown and "PERSONALIZED" in shown and "50.0000" in shown and "null" in shown
    assert main(command) == 2


@pytest.mark.parametrize("change", ["naive_time", "before_expiry", "unknown_user", "negative_amount", "bad_settings", "duplicate_json", "nan_json"])
def test_cli_invalid_input_does_not_write_output_or_expose_payload(replay_cases, tmp_path, capsys, change):
    dataset = replay_cases.write()
    settings = tmp_path / "settings.json"
    settings.write_text(replay_cases.settings.model_dump_json(), encoding="utf-8")
    command = ["compare", str(dataset), "--settings", str(settings), "--cutoffs", *replay_cases.cutoffs,
               "--expired-at", replay_cases.expired, "--users", "a", "--output", str(tmp_path / "out")]
    if change == "naive_time":
        command[command.index("--cutoffs") + 1] = "2026-01-02T13:00:00"
    elif change == "before_expiry":
        command[command.index("--expired-at") + 1] = "2026-01-05T00:00:00+09:00"
    elif change == "unknown_user":
        command[command.index("--users") + 1] = "PRIVATE_INVALID_INPUT"
    elif change == "negative_amount":
        command += ["--amount-minor", "-1"]
    else:
        settings.write_text({"bad_settings": '{"private":"PRIVATE_INVALID_INPUT"}',
            "duplicate_json": '{"private":1,"private":2}', "nan_json": '{"private":NaN}'}[change], encoding="utf-8")
    assert main(command) == 2
    captured = capsys.readouterr()
    assert "PRIVATE_INVALID_INPUT" not in captured.out + captured.err
    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("suite", ["stream", "challenge"])
def test_frozen_generator_regression_and_evaluator_files_never_opened(replay_cases, tmp_path, monkeypatch, suite):
    dataset = tmp_path / suite
    write_dataset(dataset, GeneratorConfig(users=12, days=12, train_days=4, validation_days=4), suite=suite)
    original_text, original_bytes = Path.read_text, Path.read_bytes

    def guarded_text(path, *args, **kwargs):
        assert path.name not in {"truth.jsonl", "truth.csv", "personas.jsonl"}
        return original_text(path, *args, **kwargs)

    def guarded_bytes(path, *args, **kwargs):
        assert path.name not in {"truth.jsonl", "truth.csv", "personas.jsonl"}
        return original_bytes(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", guarded_text)
    monkeypatch.setattr(Path, "read_bytes", guarded_bytes)
    data, _ = read_learning_observations(dataset)
    request = PersonalReplayRequest(settings=replay_cases.settings, cases=dataset_cases(data,
        profile_as_of="2026-01-09T00:00:00+09:00", start="2026-01-09T00:00:00+09:00",
        end="2026-01-13T00:00:00+09:00", limit=2))
    report = export_personal_replay(dataset, tmp_path / "out", request)
    assert report["cases"] >= 2 and report["status"] == "SCORED"
    assert not report["truth_read"] and not report["personas_read"]
    assert verify_personal_replay(tmp_path / "out")["status"] == "VERIFIED"
