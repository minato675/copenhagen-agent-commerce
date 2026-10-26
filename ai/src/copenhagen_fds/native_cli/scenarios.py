"""Small declared synthetic scenarios built through the existing replay/profile code."""

from copy import deepcopy
from datetime import datetime, timedelta

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personal_replay import PersonalReplayCase, PersonalReplaySettings
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyUpdateRequest
from copenhagen_fds.native_cli.runtime import MockScript, fingerprint
from copenhagen_fds.native_cli.storage import check_new_output, write_artifacts
from copenhagen_fds.personal_replay import _probe_input, _publication
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.profile_policy_replay import policy_observations_from_replay
from copenhagen_fds.synthetic.learning import OBSERVABLE_TABLES, synthetic_learning_inputs


def observable_demo():
    """Five earlier, explicitly reviewed purchases; no evaluation labels/personas."""
    start = datetime.fromisoformat("2026-01-01T12:00:00+09:00")
    data = {name: [] for name in OBSERVABLE_TABLES}
    data["users"] = [dict(user_id="demo-user", currency="KRW", registered_at="2025-01-01T00:00:00+09:00",
        collection_started_at="2025-12-01T00:00:00+09:00")]
    for day in range(5):
        at, pid = start+timedelta(days=day), f"demo-prior-{day}"
        data["purchases"].append(dict(user_id="demo-user",purchase_request_id=pid,currency="KRW",amount_minor=10000,
            occurred_at=at.isoformat(),available_at=at.isoformat(),order_snapshot_id=pid,order_version="v1",snapshot_hash="a"*64,
            merchant_orders=[dict(order_id=pid,merchant_id="demo-merchant",amount_minor=10000)]))
        data["events"].append(dict(event_id=pid,user_id="demo-user",purchase_request_id=pid,currency="KRW",
            order_id=pid,kind="PURCHASE_REQUESTED",attempt_id=None,occurred_at=at.isoformat(),available_at=at.isoformat()))
        reviewed = at+timedelta(minutes=1)
        data["feedback"].append(dict(feedback_id=pid,user_id="demo-user",purchase_request_id=pid,
            classification="NORMAL_CONFIRMED",source="SYNTHETIC_REVIEW",occurred_at=reviewed.isoformat(),available_at=reviewed.isoformat()))
    return data


def demo_settings(original):
    data = original.model_dump()
    data["profile"]["min_samples"] = 2
    data["profile_valid_for_seconds"] = 7*86400
    data["personalization"]["version"] = "native-demo-readiness-v1"
    for feature in data["personalization"]["features"]:
        feature["readiness"].update(minimum_samples=2,full_samples=4,minimum_observed_seconds=0.0,
            full_observed_seconds=86400.0,fresh_for_seconds=604800.0,stale_after_seconds=864000.0,
            minimum_available_fraction=0.0,full_available_fraction=1.0)
    return PersonalReplaySettings.model_validate(data)


def demo_request(case_id, data, settings, *, profile=None, amount=10000, missing=False, baseline="valid", text=True, burst=False):
    at = datetime.fromisoformat("2026-01-07T14:00:00+09:00")
    feature = _probe_input("demo-user","KRW",amount,at-timedelta(microseconds=1),at)
    published = None
    if profile is not None:
        cutoff = "2026-01-03T13:00:00+09:00" if profile=="transition" else "2026-01-05T13:00:00+09:00"
        replay = PersonalReplayCase(case_id=case_id,mode="CONTROLLED_PROBE",input=feature,profile_as_of=cutoff)
        selected = settings.model_copy(update=dict(profile_valid_for_seconds=86400)) if profile=="expired" else settings
        published = _publication(data,replay,selected)
    feature_data = feature.model_dump()
    feature_data["evaluation"]["request"]["intent"].update(version="demo-intent-v1",source="user_direct")
    if burst:
        feature_data["history"]["events"]=[]
        prior=at-timedelta(minutes=1)
        for index in range(50):
            for kind in ("PURCHASE_REQUESTED","PAYMENT_ATTEMPTED"):
                feature_data["history"]["events"].append(dict(event_id=f"burst-{index}-{kind}",kind=kind,
                    user_id="demo-user",purchase_request_id=f"burst-{index}",currency="KRW",
                    attempt_id=f"attempt-{index}" if kind=="PAYMENT_ATTEMPTED" else None,
                    occurred_at=prior,available_at=prior))
    if missing:
        feature_data["history"]["purchases"] = feature_data["history"]["attempts"] = dict(status="NOT_COLLECTED")
    original = feature_data["evaluation"]["request"]
    policy = settings.cold_policy
    if baseline=="missing": policy=None
    elif baseline=="expired":
        policy=policy.model_copy(update=dict(generated_at=at-timedelta(days=3),available_at=at-timedelta(days=3),
            valid_from=at-timedelta(days=3),valid_until=at-timedelta(seconds=1)))
    return EngineEvaluationRequest(trace_id="native-demo-"+case_id,feature_input=feature_data,
        analysis=dict(user_request=dict(purchase_request_id=original["context"]["purchase_request_id"],
            source="user_direct",version="demo-intent-v1",text="Controlled comparison 상품 1개 구매" if text else None),
            order=original["order"],context=original["context"]),
        personal=published,initial_policy=policy,personalization=settings.personalization,scoring=settings.scoring,
        execution=dict(gateway="MOCK",requested_model="gpt-5.4-mini",extraction_prompt_version=EXTRACTION_PROMPT_VERSION,
            comparison_prompt_version=COMPARISON_PROMPT_VERSION,provider_timeout_seconds=10.0,total_timeout_seconds=60.0))


def demo_script(request, *, signal="MATCH", failure=None, behavior_fault="NONE"):
    item = request.analysis.order.orders[0]
    refs = [dict(order_id=item.order_id,item_id=item.items[0].item_id)]
    extraction = dict(items=[dict(name="Controlled comparison",quantity=1,brand=None,category=None)],
        budget=None,purpose=None,explicit_conditions=None,preferences=None,ambiguities=None)
    comparison = dict(comparison=signal,findings=[] if signal=="MATCH" else [dict(
        code="INTENT_MISMATCH" if signal=="MISMATCH" else "INTENT_UNCERTAIN",dimension="PRODUCT",related_items=refs)],
        confirmation_requests=[] if signal=="MATCH" else [dict(code="CLARIFY_INTENT",related_items=refs)])
    if failure=="extraction": extraction=dict(kind="API_ERROR",api_error="SERVER",retryable=True)
    if failure=="comparison": comparison=dict(kind="API_ERROR",api_error="SERVER",retryable=True)
    if failure=="timeout": comparison=dict(kind="TIMEOUT",retryable=False)
    return MockScript(clock_start=request.feature_input.evaluation.evaluated_at+timedelta(seconds=1),
        extraction=extraction,comparison=comparison,behavior_fault=behavior_fault)


def update_example(request, original_data):
    """Create independent later review inputs; never read the FDS outcome as a label."""
    data = deepcopy(original_data)
    feature = request.feature_input
    order = request.analysis.order
    pid, at = order.purchase_request_id, feature.current_occurred_at
    user, currency = request.analysis.target.user_id, order.total.currency
    data["purchases"].append(dict(user_id=user,purchase_request_id=pid,currency=currency,amount_minor=order.total.amount_minor,
        occurred_at=at.isoformat(),available_at=at.isoformat(),order_snapshot_id=order.reference.snapshot_id,
        order_version=order.reference.version,snapshot_hash=order.reference.snapshot_hash,
        merchant_orders=[dict(order_id=o.order_id,merchant_id=o.merchant_id,amount_minor=o.total.amount_minor) for o in order.orders]))
    data["events"].append(dict(event_id=pid,user_id=user,purchase_request_id=pid,currency=currency,
        order_id=order.orders[0].order_id,kind="PURCHASE_REQUESTED",attempt_id=None,
        occurred_at=at.isoformat(),available_at=at.isoformat()))
    reviewed = feature.evaluation.evaluated_at+timedelta(minutes=2)
    data["feedback"].append(dict(feedback_id="later-review",user_id=user,purchase_request_id=pid,
        classification="NORMAL_CONFIRMED",source="SYNTHETIC_REVIEW",occurred_at=reviewed.isoformat(),available_at=reviewed.isoformat()))
    cutoff=reviewed+timedelta(minutes=1)
    observations=policy_observations_from_replay(synthetic_learning_inputs(data,as_of=cutoff),
        origin="SYNTHETIC",source_reference="personal-replay-observations-v1")
    state=request.personal.state
    update=ProfilePolicyUpdateRequest(expected_policy_state_version=state.state_version,update=dict(
        expected_state_version=state.candidate.state_version,expected_profile_reference=state.candidate.snapshot.profile.reference,
        as_of=cutoff,generated_at=cutoff,valid_until=cutoff+timedelta(days=3),
        changes_complete_from=state.published.profile.as_of,changes_complete_until=cutoff))
    return dict(state=state.model_dump(mode="json"),request=update.model_dump(mode="json"),
        observations=[r.model_dump(mode="json") for r in observations])


def prepare_demo(settings_path, output):
    check_new_output(output,settings_path)
    original=PersonalReplaySettings.model_validate(read_json(settings_path))
    settings,data=demo_settings(original),observable_demo()
    # Expected values are declared from the intended contract before any engine execution.
    specs=[
        ("cold-normal",{}, {},dict(status="SUCCESS",risk="LOW",amount_stage="COLD")),
        ("cold-amount",dict(amount=1000000),{},dict(status="SUCCESS",risk="MEDIUM",amount_stage="COLD")),
        ("cold-combined",dict(amount=1000000,burst=True),{},dict(status="SUCCESS",risk="HIGH",amount_stage="COLD")),
        ("transition",dict(profile="transition",amount=30000),{},dict(status="SUCCESS",amount_stage="TRANSITION")),
        ("personalized",dict(profile="full"),{},dict(status="SUCCESS",amount_stage="PERSONALIZED")),
        ("expired-profile",dict(profile="expired"),{},dict(status="SUCCESS",amount_stage="COLD")),
        ("gpt-mismatch",{},dict(signal="MISMATCH"),dict(status="SUCCESS",signal="MISMATCH")),
        ("gpt-unknown",{},dict(signal="UNKNOWN"),dict(status="INCOMPLETE",signal="UNKNOWN")),
        ("missing-text",dict(text=False),{},dict(status="INCOMPLETE",calls=0)),
        ("missing-history",dict(missing=True),{},dict(status="INCOMPLETE")),
        ("missing-baseline",dict(baseline="missing"),{},dict(status="INCOMPLETE")),
        ("expired-baseline",dict(baseline="expired"),{},dict(status="INCOMPLETE")),
        ("extraction-error",{},dict(failure="extraction"),dict(status="FAILED",calls=1)),
        ("comparison-error",{},dict(failure="comparison"),dict(status="FAILED",calls=2)),
        ("comparison-timeout",{},dict(failure="timeout"),dict(status="FAILED",calls=2)),
        ("behavior-error",{},dict(behavior_fault="INTERNAL_ERROR"),dict(status="FAILED",failed_stages=1)),
        ("compound-error",{},dict(behavior_fault="INTERNAL_ERROR",failure="extraction"),dict(status="FAILED",failed_stages=2)),
    ]
    artifacts={"observations.json":data,"source-settings.json":original.model_dump(mode="json"),
        "demo-settings.json":settings.model_dump(mode="json")}
    cases,expected=[],{}
    for case_id,options,script_options,expectation in specs:
        request=demo_request(case_id,data,settings,**options)
        script=demo_script(request,**script_options)
        artifacts[case_id+".request.json"]=request.model_dump(mode="json")
        artifacts[case_id+".script.json"]=script.model_dump(mode="json")
        cases.append(case_id);expected[case_id]=expectation
        if case_id=="personalized": artifacts["update-input.json"]=update_example(request,data)
    artifacts["suite.json"]=dict(schema_version="native-demo-suite-v1",cases=cases)
    artifacts["expected.json"]=expected
    output.mkdir(parents=True,exist_ok=False)
    write_artifacts(output,artifacts,dict(schema_version="native-demo-input-v1",code=fingerprint(),origin="SYNTHETIC",
        note="Controlled no-prior-24h probes. Demo readiness only; risk policy remains the project default. No fraud-quality claim."))
    return dict(status="PREPARED",cases=len(cases),evaluated=False,learning_updated=False)
