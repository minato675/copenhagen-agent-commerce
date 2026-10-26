"""Cold JSON entry point exposes score coverage and sanitized error outcomes."""

from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys

import pytest

from copenhagen_fds.cold_cli import main
from copenhagen_fds.contracts.scoring import ColdScoringResult


AI_DIR = Path(__file__).resolve().parents[1]
EXAMPLE = AI_DIR / "examples/cold_evaluation_input.json"


@pytest.fixture
def bundle():
    return json.loads(EXAMPLE.read_text(encoding="utf-8"))


def write_bundle(path, bundle):
    path.write_text(json.dumps(bundle), encoding="utf-8")
    return str(path)


def test_documented_module_runs_in_fresh_process_and_replays_same_json():
    command = [sys.executable, "-m", "copenhagen_fds.cold_cli", str(EXAMPLE)]
    first = subprocess.run(command, cwd=AI_DIR, capture_output=True, text=True, timeout=15)
    second = subprocess.run(command, cwd=AI_DIR, capture_output=True, text=True, timeout=15)
    assert first.returncode == second.returncode == 0
    assert first.stderr == second.stderr == ""
    assert first.stdout == second.stdout
    result = ColdScoringResult.model_validate_json(first.stdout)
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "COMPLETE"
    assert [f.score.value for f in result.behavior.features] == [0, 0, 0, 0, 25, 25, 25, 100, 0]


@pytest.mark.parametrize("scenario,coverage,expected", [
    ("new_user", "COMPLETE", 0), ("missing_history", "PARTIAL", 1),
    ("missing_time", "PARTIAL", 1), ("no_reference", "UNAVAILABLE", 1),
    ("expired", "UNAVAILABLE", 1), ("malformed_candidate", "UNAVAILABLE", 1),
    ("unknown_candidate_version", "UNAVAILABLE", 1),
])
def test_cli_preserves_structured_data_shortage_and_reference_errors(bundle, tmp_path, capsys, scenario, coverage, expected):
    source = bundle["input"]["feature_input"]
    if scenario == "new_user":
        source["history"]["events"] = []
        bundle["population"] = None
    elif scenario == "missing_history":
        source["history"] = None
    elif scenario == "missing_time":
        source["current_occurred_at"] = source["current_available_at"] = None
    elif scenario == "no_reference":
        bundle["population"] = bundle["policy"] = None
    elif scenario == "expired":
        bundle["population"]["profile"]["valid_until"] = source["evaluation"]["evaluated_at"]
    elif scenario == "malformed_candidate":
        bundle["population"] = "{private-invalid-candidate"
    else:
        bundle["population"]["format_version"] = "private-unknown-version"
    assert main([write_bundle(tmp_path / "input.json", bundle)]) == expected
    captured = capsys.readouterr()
    result = ColdScoringResult.model_validate_json(captured.out)
    assert result.behavior.coverage == coverage and captured.err == ""
    assert result.personal_profile_status == "NOT_SUPPLIED"
    assert "private-" not in captured.out
    if expected == 1:
        assert any(f.score is None for f in result.behavior.features)
    if scenario == "expired":
        assert result.selection.features[0].issues[0].code == "EXPIRED"


@pytest.mark.parametrize("problem", [
    "malformed_json", "invalid_utf8", "not_found", "missing_envelope", "extra_envelope", "list_envelope",
    "missing_input", "invalid_amount", "unknown_input_field", "duplicate_key", "nested_duplicate_key",
    "nan", "infinity", "negative_infinity", "conflicting_event", "missing_attempt_identity",
])
def test_input_errors_are_sanitized_and_never_print_a_success_payload(bundle, tmp_path, capsys, problem):
    path = tmp_path / "private-input-marker.json"
    if problem == "malformed_json":
        path.write_text("{private-input-marker", encoding="utf-8")
    elif problem == "invalid_utf8":
        path.write_bytes(b"\xffprivate-input-marker")
    elif problem == "not_found":
        pass
    elif problem in {"duplicate_key", "nested_duplicate_key"}:
        encoded = json.dumps(bundle)
        replacement = '"trace_id": "private-input-marker", "trace_id":' if problem == "nested_duplicate_key" else '"input": null, "input":'
        needle = '"trace_id":' if problem == "nested_duplicate_key" else '"input":'
        path.write_text(encoded.replace(needle, replacement, 1), encoding="utf-8")
    elif problem in {"nan", "infinity", "negative_infinity"}:
        token = {"nan": "NaN", "infinity": "Infinity", "negative_infinity": "-Infinity"}[problem]
        path.write_text(json.dumps(bundle).replace('"scale": 20000.0', f'"scale": {token}', 1), encoding="utf-8")
    else:
        source = bundle["input"]["feature_input"]
        if problem == "missing_envelope":
            del bundle["policy"]
        elif problem == "extra_envelope":
            bundle["private-input-marker"] = True
        elif problem == "list_envelope":
            bundle = [bundle]
        elif problem == "missing_input":
            bundle["input"] = None
        elif problem == "invalid_amount":
            source["evaluation"]["request"]["order"]["total"]["amount_minor"] = "private-input-marker"
        elif problem == "unknown_input_field":
            bundle["input"]["private-input-marker"] = "private-input-marker"
        elif problem == "conflicting_event":
            conflicting = deepcopy(source["history"]["events"][0])
            conflicting["currency"] = "USD"
            source["history"]["events"].append(conflicting)
        else:
            del source["current_attempt_id"]
        write_bundle(path, bundle)
    with pytest.raises(SystemExit) as stopped:
        main([str(path)])
    assert stopped.value.code == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == "Cold input could not be read or failed contract validation.\n"


def test_utf8_bom_is_accepted(bundle, tmp_path, capsys):
    path = tmp_path / "input.json"
    path.write_text(json.dumps(bundle), encoding="utf-8-sig")
    assert main([str(path)]) == 0
    assert ColdScoringResult.model_validate_json(capsys.readouterr().out).behavior.coverage == "COMPLETE"


def test_feature_calculation_failure_has_exit_three_and_preserves_other_scores(monkeypatch, capsys):
    from copenhagen_fds.scoring import score_feature

    def fail_amount(raw, reference):
        if raw.feature_id == "amount_minor":
            raise ArithmeticError("private-input-marker")
        return score_feature(raw, reference)

    monkeypatch.setattr("copenhagen_fds.cold_scoring.score_feature", fail_amount)
    assert main([str(EXAMPLE)]) == 3
    captured = capsys.readouterr()
    result = ColdScoringResult.model_validate_json(captured.out)
    assert captured.err == "" and "private-input-marker" not in captured.out
    assert result.behavior.status == "FAILED" and result.behavior.coverage == "PARTIAL"
    assert result.behavior.features[0].score is None
    assert result.behavior.features[-2].score.value == 100


def test_pre_result_arithmetic_error_has_exit_three_and_no_fabricated_result(monkeypatch, capsys):
    def fail_before_result(*args):
        raise ArithmeticError("private-input-marker")
    monkeypatch.setattr("copenhagen_fds.cold.transform_features", fail_before_result)
    with pytest.raises(SystemExit) as stopped:
        main([str(EXAMPLE)])
    assert stopped.value.code == 3
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == "Cold evaluation failed before a result could be produced.\n"
