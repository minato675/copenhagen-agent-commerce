"""S15P21A207-360: free CLI paths, explicit paid dispatch and safe diagnostics."""

from datetime import datetime, timezone
import json
from pathlib import Path

import pytest

from copenhagen_fds import analysis, openai_settings
from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.evaluation.experiments import ExperimentBatch
from copenhagen_fds.temporal_benchmark import gms_cli
from copenhagen_fds.temporal_benchmark.gms import prepare_gms_plan, read_gms_plans
from copenhagen_fds.temporal_benchmark.preparation import prepare
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan, SimulationConfig


@pytest.fixture(autouse=True)
def initialized_cli_loop(monkeypatch, run_async):
    # Reuse the loop initialized before the repository's network guard. Windows
    # asyncio.run otherwise creates an internal socketpair while it is blocked.
    monkeypatch.setattr(gms_cli.asyncio, "run", run_async)


@pytest.fixture(scope="module")
def prepared_gms_cli(tmp_path_factory):
    root = tmp_path_factory.mktemp("temporal-gms-cli")
    settings = PersonalReplaySettings.model_validate_json(
        (Path(__file__).parents[1] / "examples/personal-replay-settings.json").read_text(encoding="utf-8"))
    source, plans = root / "source", root / "plans"
    prepare(BenchmarkPlan(settings=settings,
        simulation=SimulationConfig(users=24, days=18, train_days=8, validation_days=5), purchase_limit=80), source)
    prepare_gms_plan(source, plans, limit=2, repetitions=2)
    return source, plans, read_gms_plans(plans)


@pytest.fixture
def no_credentials(monkeypatch):
    called = []

    def reject(*args, **kwargs):
        called.append(True)
        raise AssertionError("offline command attempted credential or adapter access")

    monkeypatch.setattr(openai_settings, "load_openai_settings", reject)
    monkeypatch.setattr(analysis, "OpenAIAnalysisAdapter", reject)
    return called


def saved_batch(path, plan, *, status="STOPPED", plan_sha256=None):
    at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    batch = ExperimentBatch(plan_sha256=plan_sha256 or plan.sha256, source="MOCK", status=status,
        started_at=at, completed_at=None if status == "RUNNING" else at,
        stop_reason="CANCELLED" if status == "INTERRUPTED" else "CALL_LIMIT" if status == "STOPPED" else None)
    path.mkdir()
    (path / "observations.json").write_text(batch.model_dump_json(), encoding="utf-8")
    return batch


def test_prepare_is_free_and_reports_frozen_call_budget(prepared_gms_cli, tmp_path, no_credentials, capsys):
    source, _, _ = prepared_gms_cli
    output = tmp_path / "prepared"
    code = gms_cli.main(["prepare", str(source), "--output", str(output), "--limit", "2", "--rounds", "1"])
    report = json.loads(capsys.readouterr().out)
    assert code == 0 and report["status"] == "PREPARED"
    assert report["api_calls"] == 0 and report["max_http_requests"] == 4
    assert report["pipeline_rounds"] == 1 and not no_credentials
    assert len(read_gms_plans(output)) == 1


@pytest.mark.parametrize("round_number", ["0", "-1", "3"])
def test_collect_invalid_round_stops_before_credentials(prepared_gms_cli, tmp_path, no_credentials, capsys, round_number):
    _, plans, _ = prepared_gms_cli
    output = tmp_path / "must-not-exist"
    code = gms_cli.main(["collect", str(plans), "--round", round_number,
        "--approve-plan-sha256", "0" * 64, "--output", str(output)])
    captured = capsys.readouterr()
    assert code == 2 and not output.exists() and not no_credentials
    assert captured.out == "" and "GMS diagnostic failed" in captured.err


@pytest.mark.parametrize("reason", ["wrong_hash", "existing_output"])
def test_collect_dispatch_guards_run_before_credentials(prepared_gms_cli, tmp_path, no_credentials, capsys, reason):
    _, plans, rounds = prepared_gms_cli
    output = tmp_path / "run"
    if reason == "existing_output":
        output.mkdir()
        (output / "keep.txt").write_text("existing", encoding="utf-8")
    approval = "0" * 64 if reason == "wrong_hash" else rounds[0].sha256
    code = gms_cli.main(["collect", str(plans), "--round", "1",
        "--approve-plan-sha256", approval, "--output", str(output)])
    assert code == 2 and not no_credentials and capsys.readouterr().out == ""
    if reason == "existing_output":
        assert (output / "keep.txt").read_text(encoding="utf-8") == "existing"
    else:
        assert not output.exists()


def test_collect_requires_explicit_approval_argument(prepared_gms_cli, tmp_path, no_credentials, capsys):
    _, plans, _ = prepared_gms_cli
    with pytest.raises(SystemExit) as error:
        gms_cli.main(["collect", str(plans), "--round", "1", "--output", str(tmp_path / "run")])
    assert error.value.code == 2 and not no_credentials
    assert "--approve-plan-sha256" in capsys.readouterr().err


def test_environment_failure_never_prints_key_or_exception_detail(prepared_gms_cli, tmp_path, monkeypatch, capsys):
    _, plans, rounds = prepared_gms_cli
    secret = "synthetic-sensitive-value-for-redaction-test"
    monkeypatch.setenv("GMS_KEY", secret)

    def invalid_environment(*args, **kwargs):
        raise ValueError("environment contained GMS_KEY=" + secret)

    def no_adapter(*args, **kwargs):
        pytest.fail("invalid settings must not create a GMS adapter")

    monkeypatch.setattr(openai_settings, "load_openai_settings", invalid_environment)
    monkeypatch.setattr(analysis, "OpenAIAnalysisAdapter", no_adapter)
    output = tmp_path / "run"
    code = gms_cli.main(["collect", str(plans), "--round", "1",
        "--approve-plan-sha256", rounds[0].sha256, "--output", str(output),
        "--env-file", str(tmp_path / "absent.env")])
    captured = capsys.readouterr()
    assert code == 2 and not output.exists()
    assert secret not in captured.out + captured.err
    assert "environment contained" not in captured.out + captured.err
    assert captured.err == "GMS diagnostic failed: inspect plan integrity, settings and saved observations.\n"


@pytest.mark.parametrize("complete,expected_exit", [(True, 0), (False, 1)])
def test_approved_collection_uses_pinned_settings_and_closes_adapter(
    prepared_gms_cli, tmp_path, monkeypatch, capsys, complete, expected_exit,
):
    _, plans, rounds = prepared_gms_cli
    selected, observed, settings_marker = rounds[1], {}, object()

    def settings(environ, *, env_file):
        observed["environment"] = environ
        observed["env_file"] = env_file
        return settings_marker

    class Adapter:
        def __init__(self, supplied):
            assert supplied is settings_marker

        async def __aenter__(self):
            observed["entered"] = True
            return self

        async def __aexit__(self, *args):
            observed["closed"] = True

    async def collect(plan, adapter, output, *, progress):
        assert plan == selected and isinstance(adapter, Adapter)
        observed["output"] = output
        progress(dict(completed=1, planned=2, http_requests=2))
        return None, dict(complete=complete, source="MOCK", planned_purchases=2, http_requests=2)

    monkeypatch.setattr(openai_settings, "load_openai_settings", settings)
    monkeypatch.setattr(analysis, "OpenAIAnalysisAdapter", Adapter)
    monkeypatch.setattr(gms_cli, "collect_temporal_gms", collect)
    output, env_file = tmp_path / "run", tmp_path / "explicit.env"
    code = gms_cli.main(["collect", str(plans), "--round", "2", "--approve-plan-sha256", selected.sha256,
        "--output", str(output), "--env-file", str(env_file)])
    captured = capsys.readouterr()
    assert code == expected_exit and json.loads(captured.out)["complete"] is complete
    assert json.loads(captured.err) == dict(completed=1, planned=2, http_requests=2)
    assert observed["entered"] and observed["closed"] and observed["output"] == output
    assert observed["env_file"] == env_file
    assert observed["environment"]["FDS_MODE"] == "LIVE"
    assert observed["environment"]["FDS_OPENAI_MODEL"] == selected.requested_model
    assert observed["environment"]["FDS_OPENAI_TIMEOUT_SECONDS"] == str(selected.provider_timeout_seconds)
    assert observed["environment"]["FDS_EVALUATION_TIMEOUT_SECONDS"] == str(selected.evaluation_timeout_seconds)


@pytest.mark.parametrize("status", ["STOPPED", "INTERRUPTED", "RUNNING", "COMPLETE"])
def test_rescore_missing_trials_is_free_and_never_exits_success(
    prepared_gms_cli, tmp_path, no_credentials, capsys, status,
):
    _, plans, rounds = prepared_gms_cli
    output = tmp_path / "saved"
    saved_batch(output, rounds[0], status=status)
    before = (output / "observations.json").read_bytes()
    code = gms_cli.main(["rescore", str(plans), "--round", "1", "--run", str(output)])
    report = json.loads(capsys.readouterr().out)
    assert code == 1 and not no_credentials
    assert report["complete"] is False and report["status"] == status
    assert report["metrics"]["outcomes"] == {"MISSING": 2}
    assert report["observed_purchases"] == report["http_requests"] == 0
    assert report["metrics"]["label_accuracy"]["total"] == 2
    assert (output / "observations.json").read_bytes() == before


def test_rescore_rejects_another_round_without_loading_credentials(prepared_gms_cli, tmp_path, no_credentials, capsys):
    _, plans, rounds = prepared_gms_cli
    output = tmp_path / "other-round"
    saved_batch(output, rounds[1])
    code = gms_cli.main(["rescore", str(plans), "--round", "1", "--run", str(output)])
    captured = capsys.readouterr()
    assert code == 2 and not no_credentials and captured.out == ""
    assert "GMS diagnostic failed" in captured.err


def test_rescore_corrupt_json_does_not_echo_contents(prepared_gms_cli, tmp_path, no_credentials, capsys):
    _, plans, _ = prepared_gms_cli
    output = tmp_path / "corrupt"
    output.mkdir()
    content = "synthetic-private-value-in-broken-json"
    (output / "observations.json").write_text("{bad:" + content, encoding="utf-8")
    code = gms_cli.main(["rescore", str(plans), "--round", "1", "--run", str(output)])
    captured = capsys.readouterr()
    assert code == 2 and not no_credentials
    assert content not in captured.out + captured.err


def test_rescore_success_exit_uses_report_completion_without_paid_path(prepared_gms_cli, tmp_path, no_credentials, monkeypatch, capsys):
    _, plans, rounds = prepared_gms_cli
    output = tmp_path / "saved"
    batch = saved_batch(output, rounds[0])

    def summarize(plan, supplied):
        assert plan == rounds[0] and supplied == batch
        return dict(complete=True, status="COMPLETE", source="MOCK")

    monkeypatch.setattr(gms_cli, "summarize_temporal_round", summarize)
    assert gms_cli.main(["rescore", str(plans), "--round", "1", "--run", str(output)]) == 0
    assert json.loads(capsys.readouterr().out)["complete"] is True and not no_credentials
