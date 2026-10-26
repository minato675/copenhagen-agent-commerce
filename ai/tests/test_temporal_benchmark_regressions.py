"""Existing offline suites once, plus orchestration guards (S15P21A207-359)."""

import json
from pathlib import Path
import socket

import pytest

from copenhagen_fds.synthetic.storage import digest
from copenhagen_fds.temporal_benchmark import regressions


SETTINGS = Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json"


def test_all_existing_suites_and_controlled_matrix_execute_once_offline(tmp_path, run_async, monkeypatch):
    monkeypatch.setenv("GMS_KEY", "private-never-copy-this-test-key")
    source_before = SETTINGS.read_bytes()
    output = tmp_path / "regressions"
    report = run_async(regressions.run_regressions(SETTINGS, output))
    assert report["status"] == "PASSED", report
    assert {key: report[key] for key in ("n_total", "n_passed", "n_failed", "n_not_run")} == {
        "n_total": 92, "n_passed": 92, "n_failed": 0, "n_not_run": 0}
    assert report["suites"]["native"]["evaluation_cases"] == 17
    assert report["suites"]["native"]["reuse_checks"] == 5
    assert report["suites"]["provider"]["n_total"] == 27
    assert report["suites"]["intent"]["n_total"] == 35
    assert report["suites"]["matrix"]["n_total"] == 8
    assert report["mode"] == "MOCK" and report["external_api"] == "NOT_RUN"
    assert report["interpretation"] == "SCRIPTED_CONTRACT_REGRESSION_NOT_MODEL_QUALITY"
    assert SETTINGS.read_bytes() == source_before
    matrix = json.loads((output / "matrix-run/comparisons.json").read_text(encoding="utf-8"))["rows"]
    assert all(row["passed"] for row in matrix)
    for regime in ("low", "high"):
        selected = [row for row in matrix if row["regime"] == regime]
        assert len({row["request_sha256"] for row in selected}) == 1
        assert len({row["behavior_score"] for row in selected}) == 1
    low_mismatch = next(row for row in matrix if row["case_id"] == "low-mismatch")
    assert low_mismatch["outcome"]["risk_level"] == "LOW"
    assert low_mismatch["outcome"]["recommendation"] == "REVIEW_ORDER"
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["artifact_sha256"]["summary.json"] == digest(output / "summary.json")
    assert manifest["child_artifact_sha256"]
    for relative, expected in manifest["child_artifact_sha256"].items():
        assert digest(output / relative) == expected
    assert "private-never-copy-this-test-key" not in (output / "summary.json").read_text(encoding="utf-8")
    verification = regressions.verify_regressions(output)
    assert verification["status"] == "VERIFIED"
    assert verification["scenario_status"] == "PASSED"
    assert verification["execution_repeated"] is False


@pytest.mark.parametrize("rows,expected", [
    ([dict(case_id="one", passed=True), dict(case_id="one", passed=True)], 2),
    ([dict(case_id="one", passed=1)], 1),
    ([dict(case_id="one", passed=True)], 0),
    ([dict(case_id="", passed=True)], 1),
])
def test_contract_counting_rejects_duplicates_ambiguous_results_and_extra_cases(rows, expected):
    with pytest.raises(ValueError):
        regressions._counts(rows, expected=expected)


def test_counting_keeps_failures_distinct_from_unexecuted_checks():
    assert regressions._counts([dict(case_id="one", passed=True), dict(case_id="two", passed=False)], expected=4) == {
        "n_total": 4, "n_passed": 1, "n_failed": 1, "n_not_run": 2}


def test_existing_output_is_rejected_without_starting_any_suite(tmp_path, run_async, monkeypatch):
    output = tmp_path / "existing"
    output.mkdir()
    sentinel = output / "sentinel.txt"
    sentinel.write_text("keep", encoding="utf-8")
    async def forbidden(*args):
        pytest.fail("existing destination must fail before execution")
    monkeypatch.setattr(regressions, "_native", forbidden)
    with pytest.raises(ValueError, match="new directory"):
        run_async(regressions.run_regressions(SETTINGS, output))
    assert sentinel.read_text(encoding="utf-8") == "keep"
    assert list(output.iterdir()) == [sentinel]


def test_invalid_settings_are_rejected_before_reserving_output(tmp_path, run_async):
    source = tmp_path / "settings.json"
    source.write_text("{}", encoding="utf-8")
    output = tmp_path / "run"
    with pytest.raises(ValueError):
        run_async(regressions.run_regressions(source, output))
    assert not output.exists()


def test_suite_exception_preserves_error_type_without_private_details_and_skips_later_suites(tmp_path, run_async, monkeypatch):
    async def broken(*args):
        raise ValueError("synthetic-private-error-content")
    async def forbidden(*args):
        pytest.fail("later suites must not execute after an infrastructure failure")
    monkeypatch.setattr(regressions, "_native", broken)
    monkeypatch.setattr(regressions, "_provider", forbidden)
    output = tmp_path / "failed-run"
    report = run_async(regressions.run_regressions(SETTINGS, output))
    assert report["status"] == "FAILED"
    assert report["execution_error"] == "SUITE_EXECUTION_ERROR"
    assert report["suites"]["native"]["status"] == "FAILED"
    assert report["suites"]["provider"]["status"] == "NOT_RUN"
    assert report["n_not_run"] == 92 and report["n_passed"] == 0
    assert "synthetic-private-error-content" not in (output / "summary.json").read_text(encoding="utf-8")
    verification = regressions.verify_regressions(output)
    assert verification["scenario_status"] == "FAILED"
    assert verification["verification"] == "RECORDED_ARTIFACTS_ONLY"


@pytest.mark.parametrize("mutation", ["change", "extra", "missing"])
def test_artifact_audit_rejects_changed_added_or_missing_files(tmp_path, run_async, monkeypatch, mutation):
    async def broken(*args):
        raise ValueError("synthetic failure to make a small recorded bundle")
    monkeypatch.setattr(regressions, "_native", broken)
    output = tmp_path / "tampered-run"
    run_async(regressions.run_regressions(SETTINGS, output))
    if mutation == "change":
        with (output / "summary.json").open("a", encoding="utf-8") as handle:
            handle.write(" ")
    elif mutation == "extra":
        (output / "extra.txt").write_text("unexpected", encoding="utf-8")
    else:
        (output / "summary.json").unlink()
    with pytest.raises(ValueError):
        regressions.verify_regressions(output)


def test_entire_orchestrator_has_an_explicit_network_boundary(tmp_path, run_async, monkeypatch):
    async def network_attempt(*args):
        socket.getaddrinfo("example.invalid", 443)
        pytest.fail("network access must be intercepted")
    monkeypatch.setattr(regressions, "_native", network_attempt)
    report = run_async(regressions.run_regressions(SETTINGS, tmp_path / "offline-run"))
    assert report["status"] == "FAILED"
    assert report["execution_error"] == "OFFLINE_BOUNDARY_VIOLATION"
    assert report["suites"]["provider"]["status"] == "NOT_RUN"


def test_code_changes_during_execution_cannot_receive_a_passed_manifest(tmp_path, run_async, monkeypatch):
    # Exercise the source guard with one small stand-in per suite, not a second full execution.
    calls = 0
    def changed_fingerprint():
        nonlocal calls
        calls += 1
        return dict(source_sha256="a" * 64 if calls == 1 else "b" * 64)
    def successful(name):
        async def execute(*args):
            count = regressions.SUITE_EXPECTED_COUNTS[name]
            return dict(status="PASSED", n_total=count, n_passed=count, n_failed=0, n_not_run=0)
        return execute
    monkeypatch.setattr(regressions, "fingerprint", changed_fingerprint)
    for name in regressions.SUITE_EXPECTED_COUNTS:
        monkeypatch.setattr(regressions, "_" + name, successful(name))
    report = run_async(regressions.run_regressions(SETTINGS, tmp_path / "changed-source"))
    assert report["status"] == "FAILED"
    assert report["execution_error"] == "CODE_OR_SETTINGS_CHANGED_DURING_RUN"


def test_matrix_requires_each_controlled_combination_before_claiming_completion():
    with pytest.raises(ValueError, match="all eight"):
        regressions._matrix_checks({})
