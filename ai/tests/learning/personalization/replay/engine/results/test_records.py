from datetime import timedelta

import pytest

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_results import EngineEvaluationResult, EngineRunTiming, EngineStageExecution


@pytest.mark.parametrize("options,status", [({}, "SUCCESS"), ({"profile": True}, "SUCCESS"),
    ({"missing": True}, "INCOMPLETE"), ({"intent": "UNKNOWN"}, "INCOMPLETE"),
    ({"intent": "MISMATCH"}, "SUCCESS"), ({"extraction": "FAILED"}, "FAILED"),
    ({"intent": "FAILED"}, "FAILED"), ({"extraction": "NOT_RUN"}, "INCOMPLETE")])
def test_original_success_shortage_and_failure_evidence_roundtrips(engine_result, options, status):
    result = engine_result(**options)
    assert result.status == status
    restored = EngineEvaluationResult.model_validate_json(result.model_dump_json())
    assert restored == result
    assert restored.validate_for_request(result.request) == result
    if options.get("extraction") == "FAILED":
        assert restored.analysis.comparison.status == "NOT_RUN"
        assert restored.policy_input.intent.failure == restored.analysis.extraction.failure
    if options.get("missing"):
        assert restored.timing.stage("BEHAVIOR").status == "SUCCESS"
        assert restored.policy_result.decision.behavior_score is None


def test_live_analysis_keeps_business_cutoff_and_resolved_model(engine_result):
    result = engine_result()
    assert result.timing.behavior_as_of < result.analysis.comparison.metadata.evaluated_at
    assert result.policy_input.policy_evaluated_at == result.timing.stage("POLICY").started_at
    assert result.policy_result.decision.evaluated_at == result.timing.behavior_as_of
    assert result.analysis.comparison.metadata.model_version == "fixture-resolved-model"
    assert result.request.execution.requested_model == "fixture-model"


def test_completed_behavior_is_retained_when_policy_times_out(engine_result):
    result = engine_result()
    data = result.model_dump()
    data["timing"]["stages"][3].update(status="FAILED", failure_code="TIMEOUT")
    data.update(status="FAILED", policy_result=None)
    failed = EngineEvaluationResult.model_validate(data)
    assert failed.behavior == result.behavior
    assert failed.analysis == result.analysis
    assert failed.policy_result is None


def test_run_skipped_before_any_evaluation_is_incomplete(engine_result):
    data = engine_result().model_dump()
    data.update(status="INCOMPLETE", behavior_input=None, behavior=None, analysis=None, policy_input=None, policy_result=None)
    for stage in data["timing"]["stages"]:
        stage.update(status="NOT_RUN", started_at=None, completed_at=None, skip_reason="CANCELLED")
    result = EngineEvaluationResult.model_validate(data)
    assert result.policy_result is None
    assert EngineEvaluationResult.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("change", ["missing_stage", "duplicate_stage", "reorder", "run_before_cutoff", "outside_run", "comparison_overlap", "policy_before_evidence"])
def test_inconsistent_stage_timeline_is_rejected(engine_result, change):
    data = engine_result().timing.model_dump()
    stages = data["stages"]
    if change == "missing_stage":
        data["stages"] = stages[:-1]
    elif change == "duplicate_stage":
        stages[1]["stage"] = "BEHAVIOR"
    elif change == "reorder":
        data["stages"] = tuple(reversed(stages))
    elif change == "run_before_cutoff":
        data["started_at"] -= timedelta(microseconds=1)
    elif change == "outside_run":
        stages[3]["completed_at"] = data["completed_at"] + timedelta(microseconds=1)
    elif change == "comparison_overlap":
        stages[2]["started_at"] = stages[1]["started_at"]
    else:
        stages[3]["started_at"] = stages[2]["started_at"]
    with pytest.raises(ValueError):
        EngineRunTiming.model_validate(data)


@pytest.mark.parametrize("change", ["success_with_error", "failure_without_code", "skip_with_time", "success_without_time", "raw_error"])
def test_stage_execution_and_failure_metadata_cannot_contradict(engine_result, change):
    data = engine_result().timing.stages[0].model_dump()
    if change == "success_with_error":
        data["failure_code"] = "TIMEOUT"
    elif change == "failure_without_code":
        data["status"] = "FAILED"
    elif change == "skip_with_time":
        data.update(status="NOT_RUN", skip_reason="CANCELLED")
    elif change == "success_without_time":
        data["completed_at"] = None
    else:
        data["error"] = "private-provider-response"
    with pytest.raises(ValueError) as caught:
        EngineStageExecution.model_validate(data)
    assert "private-provider-response" not in str(caught.value)


@pytest.mark.parametrize("change", ["top_status", "missing_behavior", "missing_analysis", "missing_policy", "missing_scoring", "trace", "prompt", "model", "metadata_time", "request_text", "profile_version", "policy_clock"])
def test_result_cannot_lose_or_substitute_its_original_evidence(engine_result, change):
    data = engine_result(profile=True).model_dump()
    if change == "top_status":
        data["status"] = "INCOMPLETE"
    elif change.startswith("missing_"):
        field = {"missing_behavior": "behavior", "missing_analysis": "analysis", "missing_policy": "policy_result", "missing_scoring": "behavior_input"}[change]
        data[field] = None
    elif change in {"trace", "prompt", "metadata_time"}:
        field = {"trace": "trace_id", "prompt": "prompt_version", "metadata_time": "evaluated_at"}[change]
        data["analysis"]["extraction"]["metadata"][field] = (
            data["timing"]["started_at"] if change == "metadata_time" else "changed-version"
        )
    elif change == "model":
        data["analysis"]["extraction"]["telemetry"]["requested_model"] = "another-model"
    elif change == "request_text":
        data["request"]["analysis"]["user_request"]["text"] = "changed text under the same version"
    elif change == "profile_version":
        data["behavior"]["result"]["profiles"][0]["context"]["policy_state_version"] = "changed-state"
    else:
        data["policy_input"]["policy_evaluated_at"] += timedelta(microseconds=1)
    with pytest.raises(ValueError):
        EngineEvaluationResult.model_validate(data)


def test_first_extraction_failure_cannot_be_replaced_by_skipped_comparison(engine_result):
    data = engine_result(extraction="FAILED").model_dump()
    data["policy_input"]["intent"] = data["analysis"]["comparison"]
    data["policy_result"] = None
    data["timing"]["stages"][3].update(status="FAILED", failure_code="INTERNAL_ERROR")
    with pytest.raises(ValueError, match="extraction outcome"):
        EngineEvaluationResult.model_validate(data)


def test_validation_rejects_other_request_and_hides_unnecessary_raw_input(engine_result):
    result = engine_result()
    changed = result.request.model_dump()
    changed["analysis"]["user_request"]["text"] = "private-replacement-text"
    with pytest.raises(ValueError, match="different request"):
        result.validate_for_request(EngineEvaluationRequest.model_validate(changed))
    data = result.model_dump()
    data["request"]["analysis"]["user_request"]["text"] = "private-replacement-text"
    with pytest.raises(ValueError) as caught:
        EngineEvaluationResult.model_validate(data)
    assert "private-replacement-text" not in str(caught.value)
    assert "합성 구매 요청" not in repr(result)


def test_transient_behavior_failure_is_not_recomputed_during_restore(engine_result, monkeypatch):
    import copenhagen_fds.personal_amount as amount

    def fail(*args):
        raise ArithmeticError("private-transient-error")

    with monkeypatch.context() as patch:
        patch.setattr(amount, "_calculation", fail)
        result = engine_result(profile=True)
    assert result.behavior.result.behavior.status == "FAILED"
    restored = EngineEvaluationResult.model_validate_json(result.model_dump_json())
    assert restored == result
    assert "private-transient-error" not in restored.model_dump_json()
    data = result.model_dump()
    data["timing"]["stages"][0]["failure_code"] = "TIMEOUT"
    with pytest.raises(ValueError, match="matching stage code"):
        EngineEvaluationResult.model_validate(data)


def test_policy_only_uses_declared_gateway_when_analysis_was_not_run(engine_result):
    result = engine_result(extraction="NOT_RUN")
    data = result.model_dump()
    data["analysis"] = None
    assert EngineEvaluationResult.model_validate(data).status == "INCOMPLETE"
    intent = data["policy_input"]["intent"]
    intent["telemetry"]["gateway"] = "GMS"
    intent["metadata"]["sources"] = ["OPENAI"]
    for reason in intent["reasons"]:
        reason["source"] = "OPENAI"
    with pytest.raises(ValueError, match="gateway"):
        EngineEvaluationResult.model_validate(data)
