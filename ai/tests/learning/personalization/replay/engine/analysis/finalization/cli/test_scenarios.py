"""S15P21A207-293: expected data enters only post-run grading; updates remain independent."""

from pathlib import Path
import hashlib
import json

import pytest

from copenhagen_fds.native_cli.experiments import apply_independent_update, case_ids, read_suite, run_suite, verify_run, verify_suite
from copenhagen_fds.native_cli.scenarios import prepare_demo
from copenhagen_fds.native_cli.storage import evaluate_file
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.synthetic.storage import canonical


@pytest.fixture
def demo(tmp_path):
    root=Path(__file__).resolve()
    ai=next(p for p in root.parents if p.name=="ai")
    source=tmp_path / "source"
    prepare_demo(ai / "examples/personal-replay-settings.json",source)
    return source


def test_preparation_does_not_evaluate_or_update_and_labels_are_separate(demo):
    suite=read_json(demo / "suite.json")
    assert len(suite["cases"])==17
    from copenhagen_fds.risk_settings import default_risk_policy
    for case in suite["cases"]:
        request=read_json(demo / (case+".request.json"))
        assert "expected" not in request and request["risk_policy"]==default_risk_policy().model_dump(mode="json")
    source=read_json(demo / "source-settings.json")
    settings=read_json(demo / "demo-settings.json")
    assert settings["scoring"]==source["scoring"]
    assert settings["personalization"]["features"][0]["readiness"]["full_samples"]==4
    assert not list(demo.glob("*.result.json"))
    update=read_json(demo / "update-input.json")
    assert update["request"]["update"]["as_of"] > read_json(demo / "personalized.request.json")["feature_input"]["evaluation"]["evaluated_at"]


def test_all_scenarios_reproduce_and_expectations_are_read_after_execution(demo, tmp_path, run_async, monkeypatch, capsys):
    import copenhagen_fds.native_cli.experiments as experiments
    import copenhagen_fds.native_cli.__main__ as cli
    original_read,original_eval=experiments.read_json,experiments.evaluate_file
    completed=[]
    async def counted(*args,**kwargs):
        result=await original_eval(*args,**kwargs)
        completed.append(result)
        return result
    def guarded(path):
        if path.name=="expected.json": assert len(completed)>=17
        return original_read(path)
    monkeypatch.setattr(experiments,"evaluate_file",counted)
    monkeypatch.setattr(experiments,"read_json",guarded)
    monkeypatch.setattr(experiments,"update_profile_policy",lambda *a,**k: pytest.fail("suite performed an update"))
    before={p.name:p.read_bytes() for p in demo.iterdir()}
    first,second=tmp_path/"first",tmp_path/"second"
    assert run_async(run_suite(demo,first))["status"]=="PASSED"
    assert run_async(run_suite(demo,second))["status"]=="PASSED"
    for p in first.rglob("*"):
        if p.is_file(): assert p.read_bytes()==(second/p.relative_to(first)).read_bytes()
    verified=run_async(verify_suite(first))
    assert verified["status"]=="VERIFIED" and verified["scenario_status"]=="PASSED" and verified["cases"]==17
    assert before=={p.name:p.read_bytes() for p in demo.iterdir()}
    assert cli.main(["show",str(first)])==0
    shown=capsys.readouterr().out
    assert "TRANSITION" in shown and "PERSONALIZED" in shown and "HIGH" in shown
    _,artifacts=read_suite(first)
    assert all(row["passed"] for row in artifacts["reuse.json"]["rows"])


def test_wrong_expectation_fails_grading_without_changing_result(cli_cases, tmp_path, run_async):
    from copenhagen_fds.native_cli.experiments import compare_expected
    case=cli_cases()
    result=run_async(evaluate_file(case.source,case.output,script_path=case.script_path))
    before=canonical(result)
    correct=compare_expected({"case":result},{"case":dict(status="SUCCESS")})
    wrong=compare_expected({"case":result},{"case":dict(status="FAILED")})
    assert correct["status"]=="PASSED" and wrong["status"]=="FAILED"
    assert canonical(result)==before


@pytest.mark.parametrize("changed", ["result", "summary", "runtime", "summary-with-rehashed-manifest"])
def test_verification_rejects_corruption_or_different_runtime(cli_cases, run_async, monkeypatch, changed):
    import copenhagen_fds.native_cli.storage as storage
    case=cli_cases()
    run_async(evaluate_file(case.source,case.output,script_path=case.script_path))
    if changed=="runtime":
        original=storage.fingerprint()
        monkeypatch.setattr(storage,"fingerprint",lambda:{**original,"python":"different"})
    else:
        path=case.output / ("result.json" if changed=="result" else "summary.json")
        value=read_json(path)
        if changed=="result": value["outcome"]["risk_level"]="HIGH"
        else: value["behavior_score"]=99.0
        path.write_text(canonical(value),encoding="utf-8")
        if changed=="summary-with-rehashed-manifest":
            manifest=read_json(case.output / "manifest.json")
            manifest["artifact_sha256"][path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
            (case.output / "manifest.json").write_text(canonical(manifest),encoding="utf-8")
    with pytest.raises(ValueError): run_async(verify_run(case.output))


@pytest.mark.parametrize("ids", [["../escape"],["absolute/path"],["duplicate","duplicate"],[],["A"],"case"])
def test_suite_case_names_cannot_escape_output_or_overlap(ids):
    with pytest.raises(ValueError): case_ids(ids)


def test_explicit_later_update_preserves_prior_state_and_admission_policy(demo, tmp_path, monkeypatch):
    import copenhagen_fds.native_cli.runtime as runtime
    monkeypatch.setattr(runtime,"evaluate_fraud",lambda *a,**k: pytest.fail("update evaluated FDS"))
    path=demo / "update-input.json"
    before=path.read_bytes()
    output=tmp_path / "update"
    result=apply_independent_update(path,output)
    assert result["status"]=="UPDATED" and result["previous_state_unchanged"] and not result["evaluation_executed"]
    assert result["previous_state_version"]!=result["next_state_version"] and path.read_bytes()==before
    assert result["new_observations"][0]["raw_status"]=="INCLUDE"
    assert (result["new_observations"][0]["status"],result["new_observations"][0]["reason"])==("DEFER","ABRUPT_CHANGE")
    with pytest.raises(ValueError): apply_independent_update(path,output)


def test_independent_update_rejects_stale_version_without_partial_state(demo, tmp_path):
    path=demo / "update-input.json"
    data=read_json(path);data["request"]["expected_policy_state_version"]="stale"
    path.write_text(canonical(data),encoding="utf-8")
    result=apply_independent_update(path,tmp_path/"stale-update")
    assert result["status"]=="REJECTED" and result["next_state_version"] is None and not result["new_observations"]
