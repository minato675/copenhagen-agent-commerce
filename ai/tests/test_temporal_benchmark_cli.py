"""S15P21A207-361: offline command boundaries and immutable frozen plans."""

import json
from pathlib import Path

import pytest

from copenhagen_fds.temporal_benchmark.__main__ import main


@pytest.fixture
def source(tmp_path, capsys):
    output = tmp_path / "inputs"
    settings = Path(__file__).parents[1] / "examples" / "personal-replay-settings.json"
    assert main(["prepare", "--settings", str(settings), "--users", "12", "--days", "12",
        "--train-days", "4", "--validation-days", "4", "--limit", "2", "--output", str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["external_api"] == "NOT_RUN"
    return output, settings


def test_prepare_verify_and_unexecuted_report_are_offline(source, tmp_path, capsys):
    output, _ = source
    assert main(["verify-inputs", str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["status"] == "VERIFIED"
    destination = tmp_path / "report"
    assert main(["report", str(output), "--output", str(destination)]) == 0
    report = (destination / "report.md").read_text(encoding="utf-8")
    assert "NOT_RUN" in report
    assert not (destination / "observations.json").exists()


def test_plan_overrides_and_overwrites_are_rejected(source, tmp_path):
    output, settings = source
    assert main(["prepare", "--settings", str(settings), "--plan", str(output / "plan.json"),
                 "--limit", "2", "--output", str(tmp_path / "other")]) == 2
    assert not (tmp_path / "other").exists()
    before = (output / "manifest.json").read_bytes()
    assert main(["prepare", "--settings", str(settings), "--plan", str(output / "plan.json"),
                 "--output", str(output)]) == 2
    assert (output / "manifest.json").read_bytes() == before


def test_changed_input_fails_with_sanitized_cli_error(source, capsys):
    output, _ = source
    (output / "selection.json").write_text('{"private":"do-not-log"}', encoding="utf-8")
    assert main(["verify-inputs", str(output)]) == 2
    captured = capsys.readouterr()
    assert "do-not-log" not in captured.out + captured.err
    assert "failed" in captured.err


def test_failed_regression_exit_code_is_not_success(tmp_path, monkeypatch):
    from copenhagen_fds.temporal_benchmark import regressions
    monkeypatch.setattr(regressions, "verify_regressions", lambda output: {"status": "FAILED"})
    assert main(["verify-regressions", str(tmp_path)]) == 1
