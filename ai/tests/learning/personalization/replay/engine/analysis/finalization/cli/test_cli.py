"""S15P21A207-293: execute stages, isolate modes and preserve caller artifacts."""

from datetime import datetime, timedelta, timezone
import json
import socket

import pytest

from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.evaluation.offline import OfflineViolation
from copenhagen_fds.native_cli.__main__ import main
from copenhagen_fds.native_cli.runtime import run_request
from copenhagen_fds.native_cli.storage import evaluate_file, read_artifacts


@pytest.mark.parametrize("signal,missing,status,calls", [
    ("MATCH",False,"SUCCESS",2),("MISMATCH",False,"SUCCESS",2),("UNKNOWN",False,"INCOMPLETE",2),
    ("NOT_RUN",False,"INCOMPLETE",0),("EXTRACTION_FAILED",False,"FAILED",1),
    ("COMPARISON_FAILED",False,"FAILED",2),("MATCH",True,"INCOMPLETE",2),
    ("COMPARISON_FAILED",True,"FAILED",2),
])
def test_real_pipeline_with_scripted_responses_preserves_outcomes(cli_cases, run_async, signal, missing, status, calls):
    case = cli_cases(signal=signal, missing=missing)
    result, summary = run_async(run_request(case.request,script=case.script))
    assert result.outcome.status == status and len(summary["completion_invocations"]) == calls
    assert summary["mode"] == "MOCK" and summary["external_api"] == "NOT_RUN"
    assert summary["wall_duration_ms"] is None and summary["learning_update"] == "NOT_RUN"
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()) == result


def test_default_cli_ignores_live_environment_and_does_not_read_secret_file(cli_cases, monkeypatch, run_async, capsys):
    import copenhagen_fds.native_cli.__main__ as cli
    import copenhagen_fds.native_cli.storage as storage
    case = cli_cases()
    before = case.source.read_bytes(),case.script_path.read_bytes()
    monkeypatch.setenv("FDS_MODE","LIVE")
    monkeypatch.setattr(cli.asyncio,"run",run_async)
    monkeypatch.setattr(storage,"load_openai_settings",lambda *a,**k: pytest.fail("default read live settings"))
    assert main(["evaluate",str(case.source),"--mock-script",str(case.script_path),"--output",str(case.output)]) == 0
    summary = json.loads(capsys.readouterr().out)
    assert summary["mode"] == "MOCK" and summary["profile_unchanged"]
    _, artifacts = read_artifacts(case.output)
    assert artifacts["request.json"] == artifacts["source-request.json"]
    assert before == (case.source.read_bytes(),case.script_path.read_bytes())


@pytest.mark.parametrize("change", ["existing", "source", "script", "invalid-json", "missing-script", "wrong-gateway", "old-clock", "env-in-mock", "script-in-gms"])
def test_bad_options_and_output_are_rejected_before_execution(cli_cases, run_async, monkeypatch, change):
    import copenhagen_fds.native_cli.storage as storage
    case = cli_cases()
    options = dict(script_path=case.script_path)
    if change == "existing":
        case.output.mkdir(); (case.output / "keep.txt").write_text("keep")
    elif change == "source": case.output = case.source
    elif change == "script": case.output = case.script_path
    elif change == "invalid-json": case.source.write_text('{"private-secret":')
    elif change == "missing-script": options["script_path"] = None
    elif change == "wrong-gateway":
        data = json.loads(case.source.read_text(encoding="utf-8"));data["execution"]["gateway"] = "GMS"
        case.source.write_text(json.dumps(data))
    elif change == "old-clock":
        case.script_path.write_text(case.script.model_copy(update=dict(clock_start=case.script.clock_start-timedelta(days=1))).model_dump_json())
    elif change == "env-in-mock": options["env_file"] = case.source
    else: options["mode"] = "gms"
    monkeypatch.setattr(storage,"run_request",lambda *a,**k: pytest.fail("invalid setup started execution"))
    with pytest.raises((ValueError, OSError)):
        run_async(evaluate_file(case.source,case.output,**options))
    if change == "existing": assert (case.output / "keep.txt").read_text() == "keep"


def test_cli_error_does_not_print_input_or_exception(cli_cases, monkeypatch, run_async, capsys):
    import copenhagen_fds.native_cli.__main__ as cli
    case = cli_cases()
    case.source.write_text('{"private-secret":')
    monkeypatch.setattr(cli.asyncio,"run",run_async)
    assert main(["evaluate",str(case.source),"--mock-script",str(case.script_path),"--output",str(case.output)]) == 2
    captured = capsys.readouterr()
    assert not captured.out and "private-secret" not in captured.err and "Traceback" not in captured.err


def test_offline_guard_and_clock_restore_after_failure(cli_cases, run_async, monkeypatch):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.native_cli.runtime as runtime
    case = cli_cases()
    original = engine.utc_now
    async def forbidden(*args,**kwargs): socket.getaddrinfo("invalid.test",443)
    monkeypatch.setattr(runtime,"evaluate_fraud",forbidden)
    with pytest.raises(OfflineViolation): run_async(run_request(case.request,script=case.script))
    assert engine.utc_now is original


def test_explicit_gms_uses_real_clock_and_retains_original_without_mock_fallback(cli_cases, run_async, monkeypatch):
    import copenhagen_fds.native_cli.runtime as runtime
    import copenhagen_fds.native_cli.storage as storage
    from copenhagen_fds.openai_settings import OpenAISettings
    case = cli_cases()
    # A stub models only the owned adapter boundary; no actual key or HTTP request.
    settings = OpenAISettings(mode="LIVE",gms_key="synthetic-offline-credential",openai_model="live-model",
        openai_timeout_seconds=5.0,evaluation_timeout_seconds=20.0)
    monkeypatch.setattr(storage,"load_openai_settings",lambda *a,**k: settings)
    class Adapter:
        def __init__(self, supplied): self.settings = supplied
        async def __aenter__(self): return self
        async def __aexit__(self,*args): pass
        async def complete(self,prompt,*,budget):
            from copenhagen_fds.analysis.transport_models import StructuredCompletion
            return StructuredCompletion(stage=prompt.stage,status="FAILED",failure=dict(kind="API_ERROR",api_error="SERVER"),
                telemetry=dict(gateway="GMS",requested_model="live-model",duration_ms=1.25),
                prompt_version=prompt.version,completed_at=datetime.now(timezone.utc))
    monkeypatch.setattr(runtime,"OpenAIAnalysisAdapter",Adapter)
    summary = run_async(evaluate_file(case.source,case.output,mode="gms"))
    _, artifacts = read_artifacts(case.output)
    assert summary["mode"] == "GMS" and summary["clock"] == "REAL" and summary["wall_duration_ms"] > 0
    assert summary["outcome"]["status"] == "FAILED" and summary["completion_invocations"] == ["extraction"]
    assert artifacts["source-request.json"]["execution"]["gateway"] == "MOCK"
    assert artifacts["request.json"]["execution"]["gateway"] == "GMS" and "mock-script.json" not in artifacts
    from copenhagen_fds.native_cli.experiments import verify_run
    monkeypatch.setattr(runtime,"OpenAIAnalysisAdapter",lambda *a,**k: pytest.fail("verification attempted live call"))
    verified = run_async(verify_run(case.output))
    assert verified["reproduction"] == "NOT_RUN_GMS" and verified["external_api"] == "NOT_RUN"


def test_fixed_native_result_and_artifacts_are_byte_identical(cli_cases, run_async):
    case = cli_cases(profile=True)
    first = run_async(evaluate_file(case.source,case.output,script_path=case.script_path))
    other = case.output.with_name("second")
    second = run_async(evaluate_file(case.source,other,script_path=case.script_path))
    assert first == second
    for file in case.output.iterdir(): assert file.read_bytes() == (other / file.name).read_bytes()


@pytest.mark.parametrize("signal,failures", [("MATCH",1),("EXTRACTION_FAILED",2)])
def test_scripted_behavior_fault_preserves_single_and_compound_failures(cli_cases, run_async, signal, failures):
    import copenhagen_fds.engine as engine
    case = cli_cases(signal=signal)
    original = engine.evaluate_engine_behavior
    script = case.script.model_copy(update=dict(behavior_fault="INTERNAL_ERROR"))
    result, summary = run_async(run_request(case.request,script=script))
    assert result.outcome.status == "FAILED"
    assert sum(stage.status == "FAILED" for stage in result.execution.stages) == failures
    assert engine.evaluate_engine_behavior is original and summary["external_api"] == "NOT_RUN"
