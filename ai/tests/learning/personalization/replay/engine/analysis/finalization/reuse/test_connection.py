"""S15P21A207-292: old evidence cannot be relabelled as a changed evaluation."""

from datetime import timedelta

import pytest

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_reuse_connection import EngineResultConnection
from copenhagen_fds.engine_reuse_connection import compare_engine_result


def test_identical_connection_is_not_a_reuse_or_payment_decision(reuse_cases):
    case = reuse_cases.make()
    result = compare_engine_result(case.result, case.request)
    assert result.status == "MATCHED" and not result.reasons
    assert result.original_request_sha256 == result.current_request_sha256
    assert "recommendation" not in result.model_dump() and "reusable" not in result.model_dump()
    assert EngineResultConnection.model_validate_json(result.model_dump_json()) == result
    assert compare_engine_result(case.result.model_dump(), case.request.model_dump()) == result


@pytest.mark.parametrize("field,reason", [("user_id", "USER_MISMATCH"), ("purchase_request_id", "PURCHASE_MISMATCH"),
    ("evaluation_id", "EVALUATION_ID_CHANGED")])
def test_target_identity_changes_are_explicit(reuse_cases, field, reason):
    case = reuse_cases.make()
    data = case.request.model_dump()
    original = data["feature_input"]["evaluation"]["request"]
    original["context"][field] = data["analysis"]["context"][field] = "other"
    if field == "purchase_request_id":
        original["intent"][field] = original["order"][field] = "other"
        data["analysis"]["user_request"][field] = data["analysis"]["order"][field] = "other"
    result = compare_engine_result(case.result, EngineEvaluationRequest.model_validate(data))
    assert reason in result.reasons
    assert result.status == ("REEVALUATION_REQUIRED" if field == "evaluation_id" else "INVALID_LINK")


@pytest.mark.parametrize("change,reason,status", [
    ("order-version", "ORDER_CHANGED", "REEVALUATION_REQUIRED"),
    ("order-body", "ORDER_CONTENT_CONFLICT", "INVALID_LINK"),
    ("intent-version", "INTENT_CHANGED", "REEVALUATION_REQUIRED"),
    ("intent-text", "INTENT_CONTENT_CONFLICT", "INVALID_LINK"),
    ("intent-fields", "INTENT_CONTENT_CONFLICT", "INVALID_LINK"),
    ("intent-source", "INTENT_CONTENT_CONFLICT", "INVALID_LINK"),
    ("trace", "TRACE_CHANGED", "REEVALUATION_REQUIRED"),
    ("time", "BEHAVIOR_TIME_CHANGED", "REEVALUATION_REQUIRED"),
    ("history", "FEATURE_INPUT_CHANGED", "REEVALUATION_REQUIRED"),
    ("cold", "COLD_POLICY_CHANGED", "REEVALUATION_REQUIRED"),
    ("config", "PERSONALIZATION_CHANGED", "REEVALUATION_REQUIRED"),
    ("score", "SCORING_CHANGED", "REEVALUATION_REQUIRED"),
    ("risk", "RISK_POLICY_CHANGED", "REEVALUATION_REQUIRED"),
    ("model", "MODEL_CHANGED", "REEVALUATION_REQUIRED"),
    ("execution", "EXECUTION_CONFIG_CHANGED", "REEVALUATION_REQUIRED"),
])
def test_content_and_settings_change_requires_explicit_disposition(reuse_cases, change, reason, status):
    case = reuse_cases.make()
    data = case.request.model_dump()
    original = data["feature_input"]["evaluation"]["request"]
    if change == "order-version":
        original["order"]["reference"]["version"] = data["analysis"]["order"]["reference"]["version"] = "v2"
    elif change == "order-body":
        for order in (original["order"],data["analysis"]["order"]):
            order["orders"][0]["items"][0]["name"] = "changed-private-item"
    elif change == "intent-version":
        original["intent"]["version"] = data["analysis"]["user_request"]["version"] = "v2"
    elif change == "intent-text":
        data["analysis"]["user_request"]["text"] = "changed-private-text"
    elif change == "intent-fields":
        original["intent"]["purpose"] = "changed-private-purpose"
    elif change == "intent-source":
        original["intent"]["source"] = data["analysis"]["user_request"]["source"] = "user_direct"
    elif change == "trace": data["trace_id"] = "other"
    elif change == "time": data["feature_input"]["evaluation"]["evaluated_at"] += timedelta(microseconds=1)
    elif change == "history": data["feature_input"]["history"]["purchases"]["complete_from"] += timedelta(seconds=1)
    elif change == "cold": data["initial_policy"]["parameters"][0]["scale"] += 1
    elif change == "config": data["personalization"]["version"] = "v2"
    elif change == "score": data["scoring"]["amount"]["spread_multiplier"] += 1
    elif change == "risk": data["risk_policy"]["thresholds"]["medium_from"] += 1
    elif change == "model": data["execution"]["requested_model"] = "changed-model"
    else: data["execution"]["total_timeout_seconds"] += 1
    result = compare_engine_result(case.result, EngineEvaluationRequest.model_validate(data))
    assert result.status == status and reason in result.reasons
    assert "changed-private" not in result.model_dump_json()


@pytest.mark.parametrize("scope", ["personal", "population", "baseline"])
def test_published_profiles_and_population_derivation_are_pinned(reuse_cases, scope):
    case = reuse_cases.make(profile=scope == "personal", population=scope != "personal")
    data = case.request.model_dump()
    if scope == "baseline":
        data["population_baseline_request"]["available_at"] += timedelta(microseconds=1)
        reason = "POPULATION_BASELINE_CHANGED"
    else:
        data[scope]["available_at"] += timedelta(microseconds=1)
        if scope == "population": data["population_baseline_request"]["available_at"] = data[scope]["available_at"]
        reason = "PERSONAL_PROFILE_CHANGED" if scope == "personal" else "POPULATION_PROFILE_CHANGED"
    result = compare_engine_result(case.result, EngineEvaluationRequest.model_validate(data))
    assert result.status == "REEVALUATION_REQUIRED" and reason in result.reasons


@pytest.mark.parametrize("part", ["result", "current", "both", "prompt", "schema", "forged-object"])
def test_invalid_or_unsupported_inputs_return_fixed_reasons(reuse_cases, part):
    case = reuse_cases.make()
    record, current = case.result.model_dump(), case.request.model_dump()
    if part in {"result", "both"}: record["outcome"]["risk_level"] = "HIGH"
    if part in {"current", "both"}: current["analysis"]["user_request"]["purchase_request_id"] = "private-invalid"
    if part == "prompt": current["execution"]["extraction_prompt_version"] = "unsupported"
    if part == "schema": current["schema_version"] = "unsupported"
    if part == "forged-object": current = case.request.model_copy(update=dict(trace_id=None))
    result = compare_engine_result(record,current)
    assert result.status == "INVALID_LINK"
    assert ("INVALID_RESULT" in result.reasons) == (part in {"result", "both"})
    assert ("INVALID_CURRENT_REQUEST" in result.reasons) == (part != "result")
    assert "private-invalid" not in result.model_dump_json()


def test_multiple_mismatches_preserve_reasons_and_invalid_link_priority(reuse_cases):
    case = reuse_cases.make()
    data = case.request.model_dump()
    data["feature_input"]["evaluation"]["request"]["context"]["user_id"] = data["analysis"]["context"]["user_id"] = "other"
    data["risk_policy"]["version"] = "v2"
    result = compare_engine_result(case.result,data)
    assert result.status == "INVALID_LINK" and {"USER_MISMATCH","RISK_POLICY_CHANGED"} <= set(result.reasons)


def test_read_only_comparison_never_reruns_evaluation_or_updates(reuse_cases, monkeypatch):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.engine_finalization as finalization
    import copenhagen_fds.profile_policy_updates as updates
    case = reuse_cases.make(profile=True)
    before = case.result.model_dump_json(), case.request.model_dump_json()
    def forbidden(*args, **kwargs): pytest.fail("comparison attempted evaluation or mutation")
    for owner,name in [(engine,"evaluate_engine_behavior"),(engine.IntentAnalyzer,"extract"),
        (engine.IntentAnalyzer,"compare_extraction"),(finalization,"evaluate_risk_policy"),(updates,"initialize_profile_policy")]:
        monkeypatch.setattr(owner,name,forbidden)
    result = compare_engine_result(case.result,case.request)
    assert result.status == "MATCHED"
    assert (case.result.model_dump_json(),case.request.model_dump_json()) == before


def test_result_json_cannot_change_disposition_or_erase_reasons(reuse_cases):
    case = reuse_cases.make()
    current = case.request.model_copy(update=dict(trace_id="other"))
    result = compare_engine_result(case.result,current)
    for change in [dict(status="MATCHED"),dict(reasons=[]),dict(current_request_sha256=None)]:
        with pytest.raises(ValueError): EngineResultConnection.model_validate({**result.model_dump(),**change})


def test_serialized_configuration_difference_is_not_hidden_by_python_equality(reuse_cases):
    case = reuse_cases.make()
    data = case.request.model_dump()
    data["personalization"]["features"][0]["readiness"]["minimum_observed_seconds"] = -0.0
    current = EngineEvaluationRequest.model_validate(data)
    assert current == case.request
    result = compare_engine_result(case.result,current)
    assert result.status == "REEVALUATION_REQUIRED" and result.reasons == ("REQUEST_CONTENT_CHANGED",)
