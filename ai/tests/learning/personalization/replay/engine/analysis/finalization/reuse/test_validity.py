"""S15P21A207-292: exact snapshots plus explicit exclusive validity, without renewed evidence."""

from datetime import timedelta, timezone

import pytest

from copenhagen_fds.contracts.engine_reuse import EngineReuseAssessment, EngineReuseContext
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult, native_outcome
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.engine_reuse import check_engine_result_reuse


def context_for(case):
    result = case.result
    return EngineReuseContext(checked_at=result.completed_at, validity=dict(
        result_sha256=personalization_hash(result), result_valid_until=result.completed_at+timedelta(minutes=10),
        risk_policy_sha256=personalization_hash(case.request.risk_policy), policy_valid_from=result.execution.started_at,
        policy_valid_until=result.completed_at+timedelta(minutes=10)),
        expected_extraction_model_version=result.execution.extraction.metadata.model_version,
        expected_comparison_model_version=result.execution.comparison.metadata.model_version)


def check(case, context):
    return check_engine_result_reuse(case.result,case.request,context=context)


@pytest.mark.parametrize("signal,required", [("MATCH",True),("MISMATCH",True),("UNKNOWN",False),("NOT_RUN",False)])
def test_exact_successful_evidence_can_be_reused_without_granting_approval(reuse_cases, signal, required):
    case = reuse_cases.make(signal=signal,required=required)
    context = context_for(case)
    result = check(case,context)
    assert result.status == "VALID" and result.reusable and not result.issues
    assert result.valid_until > result.checked_at
    assert "risk_level" not in result.model_dump() and "recommendation" not in result.model_dump()
    assert EngineReuseAssessment.model_validate_json(result.model_dump_json()) == result
    if signal != "NOT_RUN":
        assert result.valid_until == case.result.execution.extraction.metadata.evaluated_at+timedelta(seconds=300)


@pytest.mark.parametrize("component", ["RESULT","RISK_POLICY","EXTRACTION","COMPARISON","COLD_POLICY",
                                       "PERSONAL_PROFILE","POPULATION_PROFILE","POPULATION_BASELINE"])
@pytest.mark.parametrize("offset", [-1,0,1])
def test_each_expiry_is_exclusive_and_its_reason_survives_other_expiries(reuse_cases, component, offset):
    case = reuse_cases.make(profile=component=="PERSONAL_PROFILE",population=component.startswith("POPULATION"))
    context = context_for(case).model_dump()
    if component in {"RESULT","RISK_POLICY"}:
        deadline = case.result.completed_at+timedelta(seconds=20)
        context["validity"]["result_valid_until" if component=="RESULT" else "policy_valid_until"] = deadline
    elif component in {"EXTRACTION","COMPARISON"}:
        deadline = getattr(case.result.execution,component.lower()).metadata.evaluated_at+timedelta(seconds=300)
    elif component=="COLD_POLICY": deadline = case.request.initial_policy.valid_until
    elif component=="PERSONAL_PROFILE": deadline = case.request.personal.state.published.profile.valid_until
    elif component=="POPULATION_PROFILE": deadline = case.request.population.state.published.profile.valid_until
    else: deadline = case.result.execution.behavior.scoring_input.population_baseline.baseline.profile.valid_until
    context["checked_at"] = deadline+timedelta(microseconds=offset)
    result = check(case,context)
    expired = [i for i in result.issues if i.component==component and i.code=="EXPIRED"]
    assert bool(expired) == (offset>=0)
    if expired:
        assert expired[0].boundary == deadline and not result.reusable
    if component in {"RESULT","RISK_POLICY","EXTRACTION"}:
        assert result.reusable == (offset<0)


@pytest.mark.parametrize("missing,component,code", [
    ("validity","RESULT","MISSING_VALIDITY"),
    ("result_sha256","RESULT","MISSING_BINDING"),
    ("risk_policy_sha256","RISK_POLICY","MISSING_BINDING"),
    ("result_valid_until","RESULT","MISSING_EXPIRY"),
    ("policy_valid_from","RISK_POLICY","MISSING_START"),
    ("policy_valid_until","RISK_POLICY","MISSING_EXPIRY"),
    ("expected_extraction_model_version","EXTRACTION","MISSING_MODEL_VERSION"),
    ("expected_comparison_model_version","COMPARISON","MISSING_MODEL_VERSION"),
])
def test_absent_validity_or_version_is_not_invented(reuse_cases, missing, component, code):
    case = reuse_cases.make()
    context = context_for(case).model_dump()
    if missing in context: context[missing] = None
    else: context["validity"][missing] = None
    result = check(case,context)
    assert result.status == "REEVALUATION_REQUIRED" and not result.reusable and result.valid_until is None
    assert any((i.component,i.code)==(component,code) for i in result.issues)


@pytest.mark.parametrize("field", ["expected_extraction_model_version","expected_comparison_model_version"])
def test_same_requested_alias_does_not_hide_changed_actual_model(reuse_cases, field):
    case = reuse_cases.make()
    context = context_for(case).model_dump(); context[field] = "new-resolved-model"
    result = check(case,context)
    assert result.connection.status == "MATCHED" and result.status == "REEVALUATION_REQUIRED"
    assert any(i.code=="MODEL_VERSION_CHANGED" for i in result.issues)


def test_missing_intent_version_is_not_a_stable_identity(reuse_cases):
    case = reuse_cases.make(intent_version=None)
    result = check(case,context_for(case))
    assert result.connection.status == "MATCHED" and result.status == "REEVALUATION_REQUIRED"
    assert any(i.code=="MISSING_INTENT_VERSION" for i in result.issues)


@pytest.mark.parametrize("field", ["result_sha256","risk_policy_sha256"])
def test_validity_from_another_record_or_policy_is_rejected(reuse_cases, field):
    case = reuse_cases.make()
    context = context_for(case).model_dump();context["validity"][field] = "a"*64
    result = check(case,context)
    assert result.status == "INVALID_LINK" and any(i.code=="WRONG_BINDING" for i in result.issues)


@pytest.mark.parametrize("change", ["future-policy", "reversed-policy", "empty-result", "before-completion", "naive", "epoch", "invalid-model"])
def test_invalid_dates_and_context_never_become_a_reuse_window(reuse_cases, change):
    case = reuse_cases.make()
    context = context_for(case).model_dump()
    if change=="future-policy": context["validity"]["policy_valid_from"] = case.result.completed_at+timedelta(seconds=1)
    elif change=="reversed-policy": context["validity"]["policy_valid_until"] = context["validity"]["policy_valid_from"]-timedelta(seconds=1)
    elif change=="empty-result": context["validity"]["result_valid_until"] = case.result.completed_at
    elif change=="before-completion": context["checked_at"] = case.result.completed_at-timedelta(microseconds=1)
    elif change=="naive": context["checked_at"] = case.result.completed_at.replace(tzinfo=None)
    elif change=="epoch": context["checked_at"] = 1234
    else: context["expected_comparison_model_version"] = " "
    result = check(case,context)
    assert result.status == "INVALID_LINK" and not result.reusable and result.valid_until is None


@pytest.mark.parametrize("signal", ["UNKNOWN","NOT_RUN","EXTRACTION_FAILED","COMPARISON_FAILED"])
def test_failed_or_incomplete_result_is_never_reusable(reuse_cases, signal):
    case = reuse_cases.make(signal=signal)
    result = check(case,context_for(case))
    assert result.status == "REEVALUATION_REQUIRED" and any(i.code=="RESULT_NOT_SUCCESSFUL" for i in result.issues)


@pytest.mark.parametrize("termination", ["CANCELLED","BUDGET_EXHAUSTED"])
def test_late_retained_success_is_not_a_current_decision(reuse_cases, termination):
    case = reuse_cases.make()
    result = case.result
    changes=dict(termination=termination,outcome=native_outcome(result.execution,result.policy_stage,result.policy_result,termination))
    if termination=="BUDGET_EXHAUSTED": changes["elapsed_seconds"] = result.execution.budget_seconds
    case.result = NativeEvaluationResult.model_validate({**result.model_dump(),**changes})
    assert case.result.policy_result.decision.status == "SUCCESS"
    checked = check(case,context_for(case))
    assert not checked.reusable and any(i.code=="RESULT_INTERRUPTED" for i in checked.issues)


def test_ttl_does_not_move_behavior_cutoff_or_rename_a_trace(reuse_cases):
    case = reuse_cases.make()
    data = case.request.model_dump()
    data["feature_input"]["evaluation"]["evaluated_at"] += timedelta(seconds=1)
    context = context_for(case).model_dump();context["checked_at"] += timedelta(seconds=2)
    result = check_engine_result_reuse(case.result,data,context=context)
    assert result.status == "REEVALUATION_REQUIRED" and "BEHAVIOR_TIME_CHANGED" in result.connection.reasons


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
@pytest.mark.parametrize("offset", [-1, 0, 1])
def test_explicit_gpt_deadline_shortens_policy_max_age(reuse_cases, stage, offset):
    from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
    from copenhagen_fds.engine_finalization import finalize_engine_execution
    case = reuse_cases.make()
    deadline = case.result.completed_at + timedelta(seconds=20)
    data = case.result.execution.model_dump()
    data[stage]["metadata"]["valid_until"] = deadline
    case.result = finalize_engine_execution(EngineExecutionResult.model_validate(data), budget=case.budget)
    context = context_for(case).model_dump()
    context["checked_at"] = deadline + timedelta(microseconds=offset)
    result = check(case, context)
    assert result.reusable == (offset < 0)
    if offset < 0:
        assert result.valid_until == deadline
    else:
        assert any(i.component == stage.upper() and i.code == "EXPIRED" for i in result.issues)


@pytest.mark.parametrize("scope", ["personal", "population"])
def test_new_published_version_requires_reevaluation_within_ttl(reuse_cases, replay_cases, personalized, scope):
    from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
    from copenhagen_fds.contracts.personal_replay import PersonalReplayCase
    from copenhagen_fds.personal_replay import _publication
    case = reuse_cases.make(profile=scope == "personal", population=scope == "population")
    old = getattr(case.request, scope)
    if scope == "personal":
        replay_case = PersonalReplayCase(case_id="new-profile", mode="CONTROLLED_PROBE",
            input=case.request.feature_input, profile_as_of=old.state.published.profile.as_of)
        settings = replay_cases.settings.model_copy(update=dict(
            profile_valid_for_seconds=replay_cases.settings.profile_valid_for_seconds + 1))
        current = _publication(replay_cases.data, replay_case, settings)
        assert current.expected_reference.version != old.expected_reference.version
    else:
        current = personalized.publication(scope="POPULATION", pid="new-population-observation")
    assert current.expected_policy_state_version != old.expected_policy_state_version
    data = case.request.model_dump()
    data[scope] = current
    if scope == "population":
        data["population_baseline_request"]["expected_source_reference"] = current.expected_reference
    request = EngineEvaluationRequest.model_validate(data)
    result = check_engine_result_reuse(case.result, request, context=context_for(case))
    assert result.status == "REEVALUATION_REQUIRED"
    assert scope.upper() + "_PROFILE_CHANGED" in result.connection.reasons


def test_equivalent_timezone_json_and_read_only_behavior(reuse_cases, monkeypatch):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.engine_finalization as finalization
    import copenhagen_fds.profile_policy_updates as updates
    case = reuse_cases.make(profile=True)
    context = context_for(case)
    before = case.request.model_dump_json(),case.result.model_dump_json(),context.model_dump_json()
    def forbidden(*args, **kwargs): pytest.fail("reuse inspection attempted execution or update")
    for owner,name in [(engine,"evaluate_engine_behavior"),(engine.IntentAnalyzer,"extract"),
        (engine.IntentAnalyzer,"compare_extraction"),(finalization,"evaluate_risk_policy"),(updates,"initialize_profile_policy")]:
        monkeypatch.setattr(owner,name,forbidden)
    result = check(case,context)
    restored = EngineReuseContext.model_validate_json(context.model_dump_json())
    assert check_engine_result_reuse(case.result.model_dump(),case.request.model_dump(),context=restored)==result
    converted = context.model_copy(update=dict(checked_at=context.checked_at.astimezone(timezone.utc)))
    assert check(case,converted)==result
    assert (case.request.model_dump_json(),case.result.model_dump_json(),context.model_dump_json())==before
    assert EngineReuseAssessment.model_validate_json(result.model_dump_json())==result
    for changes in [dict(reusable=False),dict(status="INVALID_LINK"),dict(valid_until=context.checked_at)]:
        with pytest.raises(ValueError): EngineReuseAssessment.model_validate({**result.model_dump(),**changes})
