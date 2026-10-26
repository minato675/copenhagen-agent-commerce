"""Post-execution comparisons, replay verification, and explicitly separate updates."""

from datetime import timedelta
import math
import re

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.profile_policy import PolicyObservation
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState, ProfilePolicyUpdateRequest
from copenhagen_fds.contracts.values import Array, InputModel
from copenhagen_fds.engine_reuse import check_engine_result_reuse
from copenhagen_fds.native_cli.runtime import MockScript, fingerprint, run_request, summarize
from copenhagen_fds.native_cli.storage import check_new_output, evaluate_file, read_artifacts, write_artifacts
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.profile_policy_updates import update_profile_policy
from copenhagen_fds.synthetic.storage import digest


def case_ids(value):
    if not isinstance(value,list) or not 1 <= len(value) <= 32 or any(
        not isinstance(v,str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}",v) for v in value) or len(set(value)) != len(value):
        raise ValueError("expected distinct safe case identifiers")
    return value


def compare_expected(summaries, expected):
    if set(summaries) != set(expected): raise ValueError("expectations must cover exactly the executed cases")
    rows=[]
    for case_id,summary in summaries.items():
        amount=next((f for f in summary["features"] if f["feature_id"]=="amount_minor"),None)
        comparison=summary["analysis"]["comparison"]
        observed=dict(status=summary["outcome"]["status"],risk=summary["outcome"]["risk_level"],
            amount_stage=amount["stage"] if amount else None,signal=comparison["signal"] if comparison else None,
            calls=len(summary["completion_invocations"]),
            failed_stages=sum(s["status"]=="FAILED" for s in summary["stages"] if s["stage"]!="POLICY"))
        criteria=expected[case_id]
        if not isinstance(criteria,dict) or not criteria or not set(criteria)<=set(observed):
            raise ValueError("unsupported or empty expectation")
        checks={key:observed[key]==value for key,value in criteria.items()}
        rows.append(dict(case_id=case_id,passed=all(checks.values()),checks=checks,expected=criteria,observed=observed,summary=summary))
    return dict(status="PASSED" if all(r["passed"] for r in rows) else "FAILED",rows=rows,
        note="Synthetic integration checks, not measured fraud detection quality.")


def reuse_demonstration(result, script):
    request=result.execution.request
    deadline=script.clock_start+timedelta(seconds=30)
    context=dict(checked_at=result.completed_at+timedelta(seconds=1),validity=dict(
        result_sha256=personalization_hash(result),result_valid_until=deadline,
        risk_policy_sha256=personalization_hash(request.risk_policy),policy_valid_from=script.clock_start-timedelta(days=1),
        policy_valid_until=script.clock_start+timedelta(seconds=60)),
        expected_extraction_model_version="native-script-model-v1",expected_comparison_model_version="native-script-model-v1")
    variants={"same":(request,context,"VALID")}
    changed=request.model_dump()
    for order in (changed["analysis"]["order"],changed["feature_input"]["evaluation"]["request"]["order"]):
        order["reference"]["version"]="demo-changed-order-v2"
    variants["order-changed"]=(changed,context,"REEVALUATION_REQUIRED")
    changed=request.model_dump()
    changed["analysis"]["context"]["user_id"]=changed["feature_input"]["evaluation"]["request"]["context"]["user_id"]="other-user"
    variants["wrong-user"]=(changed,context,"INVALID_LINK")
    changed=request.model_dump();changed["feature_input"]["evaluation"]["evaluated_at"]+=timedelta(seconds=1)
    variants["behavior-time-changed"]=(changed,context,"REEVALUATION_REQUIRED")
    variants["expired"]=(request,{**context,"checked_at":deadline},"REEVALUATION_REQUIRED")
    rows=[]
    for name,(current,inspection,expected) in variants.items():
        assessment=check_engine_result_reuse(result,current,context=inspection)
        rows.append(dict(case_id=name,expected=expected,passed=assessment.status==expected,assessment=assessment.model_dump(mode="json")))
    return dict(status="PASSED" if all(r["passed"] for r in rows) else "FAILED",rows=rows,
        note="Simulation deadlines fixed before execution from the script clock; never issued for actual payment authorization.")


async def run_suite(source, output):
    check_new_output(output,source)
    inventory=read_json(source / "manifest.json")
    suite=read_json(source / "suite.json")
    ids=case_ids(suite["cases"])
    if inventory["schema_version"]!="native-demo-input-v1" or suite["schema_version"]!="native-demo-suite-v1" or inventory["code"]!=fingerprint():
        raise ValueError("scenario source requires its recorded code and runtime")
    # Validate only execution inputs before scoring; expected.json is read afterwards.
    for name in ["suite.json",*(case+suffix for case in ids for suffix in (".request.json",".script.json"))]:
        if digest(source / name)!=inventory["artifact_sha256"][name]: raise ValueError("scenario input changed")
    output.mkdir(parents=True,exist_ok=False)
    summaries={}
    for case in ids:
        summaries[case]=await evaluate_file(source / (case+".request.json"),output / case,
            script_path=source / (case+".script.json"))
    # Expectations cannot enter request preparation, model weights or provider prompts.
    if digest(source / "expected.json")!=inventory["artifact_sha256"]["expected.json"]:
        raise ValueError("scenario expectations changed")
    expected=read_json(source / "expected.json")
    comparisons=compare_expected(summaries,expected)
    _, first=read_artifacts(output / ids[0])
    reuse=reuse_demonstration(NativeEvaluationResult.model_validate(first["result.json"]),MockScript.model_validate(first["mock-script.json"]))
    status="PASSED" if comparisons["status"]==reuse["status"]=="PASSED" else "FAILED"
    write_artifacts(output,{"comparisons.json":comparisons,"expected.json":expected,"reuse.json":reuse},
        dict(schema_version="native-cli-suite-v1",code=fingerprint(),case_ids=ids,status=status,
            source_manifest_sha256=digest(source / "manifest.json"),learning_update="NOT_RUN",external_api="NOT_RUN"))
    return dict(status=status,cases=len(ids),reuse_checks=len(reuse["rows"]),external_api="NOT_RUN",learning_update="NOT_RUN")


async def verify_run(output):
    manifest,artifacts=read_artifacts(output)
    request=EngineEvaluationRequest.model_validate(artifacts["request.json"])
    original=EngineEvaluationRequest.model_validate(artifacts["source-request.json"])
    result=NativeEvaluationResult.model_validate(artifacts["result.json"])
    summary=artifacts["summary.json"]
    if result.execution.request!=request or manifest["mode"]!=request.execution.gateway:
        raise ValueError("saved result and execution mode must match the request")
    if manifest["mode"]=="MOCK":
        if original!=request: raise ValueError("mock cannot rewrite the request")
        script=MockScript.model_validate(artifacts["mock-script.json"])
        repeated,observed=await run_request(request,script=script)
        if repeated!=result or observed!=summary: raise ValueError("fixed mock result did not reproduce")
    else:
        source=original.model_dump();actual=request.model_dump()
        for field in ("gateway","requested_model","provider_timeout_seconds","total_timeout_seconds"):
            source["execution"][field]=actual["execution"][field]
        if source!=actual: raise ValueError("GMS run changed non-connection input")
        for key,value in summarize(result).items():
            if summary[key]!=value: raise ValueError("GMS summary changed recorded evidence")
        if summary["request_sha256"]!=personalization_hash(request) or summary["result_sha256"]!=personalization_hash(result):
            raise ValueError("GMS source hashes changed")
        if (summary["mode"],summary["policy_sha256"],summary["profile_unchanged"],summary["learning_update"],summary["service_integrated"]) != (
            "GMS",personalization_hash(request.risk_policy),True,"NOT_RUN",False):
            raise ValueError("GMS provenance or side-effect claims changed")
        calls=summary["completion_invocations"]
        if calls not in ([],["extraction"],["extraction","comparison"]) or summary["external_api"]!=("ATTEMPTED" if calls else "NOT_RUN"):
            raise ValueError("GMS invocation report is inconsistent")
        if summary["clock"]!="REAL" or not isinstance(summary["wall_duration_ms"],(float,int)) or not math.isfinite(summary["wall_duration_ms"]) or summary["wall_duration_ms"]<0:
            raise ValueError("GMS must preserve actual measurement classification")
    return dict(status="VERIFIED",mode=manifest["mode"],reproduction="REEXECUTED_MOCK" if manifest["mode"]=="MOCK" else "NOT_RUN_GMS",
        external_api="NOT_RUN",result_sha256=personalization_hash(result))


def read_suite(output):
    manifest=read_json(output / "manifest.json")
    names={"comparisons.json","expected.json","reuse.json"}
    if manifest["schema_version"]!="native-cli-suite-v1" or set(manifest["artifact_sha256"])!=names:
        raise ValueError("invalid suite inventory")
    case_ids(manifest["case_ids"])
    for name in names:
        if digest(output / name)!=manifest["artifact_sha256"][name]: raise ValueError("suite artifact changed")
    return manifest,{name:read_json(output / name) for name in names}


async def verify_suite(output):
    manifest,artifacts=read_suite(output)
    if manifest["code"]!=fingerprint() or manifest["learning_update"]!="NOT_RUN" or manifest["external_api"]!="NOT_RUN":
        raise ValueError("suite code/runtime or execution boundary changed")
    summaries={}
    for case in manifest["case_ids"]:
        await verify_run(output / case)
        _,run=read_artifacts(output / case)
        if run["summary.json"]["mode"]!="MOCK": raise ValueError("suite requires mock runs")
        summaries[case]=run["summary.json"]
    comparisons=compare_expected(summaries,artifacts["expected.json"])
    _,first=read_artifacts(output / manifest["case_ids"][0])
    reuse=reuse_demonstration(NativeEvaluationResult.model_validate(first["result.json"]),MockScript.model_validate(first["mock-script.json"]))
    if comparisons!=artifacts["comparisons.json"] or reuse!=artifacts["reuse.json"]:
        raise ValueError("suite report must match executed evidence")
    status="PASSED" if comparisons["status"]==reuse["status"]=="PASSED" else "FAILED"
    if manifest["status"]!=status: raise ValueError("suite status changed")
    return dict(status="VERIFIED",scenario_status=status,cases=len(summaries),external_api="NOT_RUN",reproduction="REEXECUTED_MOCK")


class IndependentUpdate(InputModel):
    state: ProfilePolicyState
    request: ProfilePolicyUpdateRequest
    observations: Array[PolicyObservation]


def apply_independent_update(input_path,output):
    check_new_output(output,input_path)
    supplied=IndependentUpdate.model_validate(read_json(input_path))
    before=supplied.model_dump_json()
    result=update_profile_policy(supplied.state,supplied.request,supplied.observations)
    if supplied.model_dump_json()!=before: raise ValueError("update modified the prior state")
    previous={d.target.purchase_request_id for d in supplied.state.evaluation.decisions}
    decisions=[] if result.state is None else [dict(purchase_request_id=d.target.purchase_request_id,
        raw_status=d.raw_status,status=d.status,reason=d.reason) for d in result.state.evaluation.decisions
        if d.target.purchase_request_id not in previous]
    summary=dict(status=result.status,issue=result.issue,previous_state_version=result.previous_state_version,
        next_state_version=result.state.state_version if result.state else None,new_observations=decisions,
        previous_state_unchanged=True,evaluation_executed=False,external_api="NOT_RUN",persisted_to_service=False)
    output.mkdir(parents=True,exist_ok=False)
    write_artifacts(output,{"input.json":supplied.model_dump(mode="json"),"update-result.json":result.model_dump(mode="json"),"summary.json":summary},
        dict(schema_version="native-cli-independent-update-v1",code=fingerprint()))
    return summary
