from datetime import timedelta

import pytest

from copenhagen_fds.contracts.engine_behavior import EngineBehaviorResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_results import EngineEvaluationResult
from copenhagen_fds.engine_behavior import evaluate_engine_behavior
from copenhagen_fds.personal_evaluation import evaluate_personalized_features


def test_three_stages_and_expiry_equal_existing_scores_weights_and_evidence(behavior_cases):
    stages, amounts = [], []
    for request, row in behavior_cases.comparison():
        result = evaluate_engine_behavior(request)
        assert result.scoring_input == row.input
        assert result.evaluation == row.evaluation
        assert result.request == request
        feature = result.evaluation.result.features[0]
        stages.append(feature.stage)
        amounts.append(feature.combined.score.value)
        assert feature.cold_weight + feature.personal_weight == 1.0
        assert EngineBehaviorResult.model_validate_json(result.model_dump_json()) == result
        assert result.validate_for_request(request) == result
    assert stages == ["COLD", "COLD", "TRANSITION", "PERSONALIZED", "COLD"]
    assert amounts == [0, 0, 50, 100, 0]
    assert result.evaluation.result.profiles[0].status == "EXPIRED"


@pytest.mark.parametrize("options", [{}, {"expired": True}, {"unavailable": True}])
def test_population_stage_preserves_source_selection_and_existing_evaluation(behavior_cases, options):
    request = behavior_cases.population(**options)
    result = evaluate_engine_behavior(request)
    assert result.evaluation == evaluate_personalized_features(result.scoring_input, request.scoring)
    assert result.evaluation.result.profiles[1].context.profile == request.population.state.published.profile
    assert result.scoring_input.population_baseline is not None
    if options.get("expired"):
        assert result.evaluation.result.behavior.coverage == "UNAVAILABLE"
        assert all(f.combined.score is None for f in result.evaluation.result.features)


def test_missing_history_and_missing_reference_are_not_zero_scores(engine_cases):
    result = evaluate_engine_behavior(engine_cases.inputs(missing=True)[0])
    counts = [f for f in result.evaluation.result.features if "count" in f.combined.feature_id]
    assert all(f.combined.status == "NOT_COLLECTED" and f.combined.score is None for f in counts)
    assert result.evaluation.result.behavior.status == "SUCCESS"
    assert result.evaluation.result.behavior.coverage == "PARTIAL"
    request = engine_cases.inputs()[0].model_copy(update={"initial_policy": None})
    unavailable = evaluate_engine_behavior(request)
    assert unavailable.evaluation.result.profiles[0].status == "NOT_SUPPLIED"
    assert unavailable.evaluation.result.behavior.coverage == "UNAVAILABLE"
    assert all(f.combined.status == "INSUFFICIENT_DATA" and f.combined.score is None for f in unavailable.evaluation.result.features)


@pytest.mark.parametrize("side", ["personal", "cold", "both"])
def test_partial_arithmetic_failure_survives_fallback_and_json_restore(engine_cases, monkeypatch, side):
    import copenhagen_fds.personal_amount as personal
    import copenhagen_fds.cold_scoring as cold

    request = engine_cases.inputs(profile=True)[0]
    original = cold.score_feature
    def fail(*args):
        raise ArithmeticError("private-calculation-error")
    def fail_amount(raw, reference):
        return fail() if raw.feature_id == "amount_minor" else original(raw, reference)

    with monkeypatch.context() as patch:
        if side in {"personal", "both"}:
            patch.setattr(personal, "_calculation", fail)
        if side in {"cold", "both"}:
            patch.setattr(cold, "score_feature", fail_amount)
        result = evaluate_engine_behavior(request)
        assert result.evaluation == evaluate_personalized_features(result.scoring_input, request.scoring)
    assert result.evaluation.result.behavior.status == "FAILED"
    feature, *remaining = result.evaluation.result.features
    assert (feature.personal.status == "FAILED") == (side in {"personal", "both"})
    assert (feature.cold.status == "FAILED") == (side in {"cold", "both"})
    assert all(f.combined.status != "FAILED" for f in remaining)
    if side == "personal":
        assert feature.stage == "COLD" and feature.combined.score is not None
    elif side == "both":
        assert feature.combined.score is None
    restored = EngineBehaviorResult.model_validate_json(result.model_dump_json())
    assert restored.validate_for_request(request) == result
    assert "private-calculation-error" not in result.model_dump_json()


def test_scorer_runs_once_and_is_never_called_during_restoration(engine_cases, monkeypatch):
    import copenhagen_fds.engine_behavior as engine

    request = engine_cases.inputs(profile=True)[0]
    calls = []
    def counted(supplied, policies):
        calls.append((supplied, policies))
        return evaluate_personalized_features(supplied, policies)
    monkeypatch.setattr(engine, "evaluate_personalized_features", counted)
    result = engine.evaluate_engine_behavior(request)
    result.validate_for_request(request)
    EngineBehaviorResult.model_validate_json(result.model_dump_json())
    assert calls == [(result.scoring_input, request.scoring)]


@pytest.mark.parametrize("change", ["unsupported_scoring", "runtime_error"])
def test_invalid_configuration_and_unexpected_errors_are_not_fabricated_results(engine_cases, monkeypatch, change):
    import copenhagen_fds.engine_behavior as engine

    request = engine_cases.inputs()[0]
    calls = []
    def fail(*args):
        calls.append(True)
        raise RuntimeError("unexpected execution failure")
    monkeypatch.setattr(engine, "evaluate_personalized_features", fail)
    if change == "unsupported_scoring":
        request = request.model_copy(update={"scoring": request.scoring.model_copy(update={"scale_version": "unsupported"})})
        with pytest.raises(ValueError):
            engine.evaluate_engine_behavior(request)
        assert calls == []
    else:
        with pytest.raises(RuntimeError, match="unexpected execution failure"):
            engine.evaluate_engine_behavior(request)
        assert calls == [True]


def test_repeated_execution_needs_no_loader_io_gms_or_learning_and_keeps_state(engine_cases, monkeypatch):
    import builtins
    import socket
    from pathlib import Path

    import copenhagen_fds.personal_replay as replay
    import copenhagen_fds.personal_profiles as personal
    import copenhagen_fds.population_profiles as population
    import copenhagen_fds.profile_updates as updates
    import copenhagen_fds.profile_policy_updates as policy
    from copenhagen_fds.analysis.intent_analyzer import IntentAnalyzer
    from copenhagen_fds.analysis.openai_adapter import OpenAIAnalysisAdapter

    request = engine_cases.inputs(profile=True)[0]
    request = request.model_copy(update={"execution": request.execution.model_copy(update={"gateway": "GMS"})})
    before = request.model_dump_json()
    def forbidden(*args, **kwargs):
        raise AssertionError("unexpected external I/O, loader or learning call")
    with monkeypatch.context() as patch:
        patch.delenv("GMS_KEY", raising=False)
        for owner, name in [(builtins, "open"), (Path, "read_text"), (socket.socket, "connect"),
                (replay, "run_personal_replay"), (personal, "build_personal_profile"), (population, "build_population_profile"),
                (updates, "update_profile"), (policy, "update_profile_policy"), (policy, "initialize_profile_policy"),
                (IntentAnalyzer, "analyze"), (OpenAIAnalysisAdapter, "complete")]:
            patch.setattr(owner, name, forbidden)
        first = evaluate_engine_behavior(request)
        second = evaluate_engine_behavior(request)
    assert first == second
    assert request.model_dump_json() == before
    assert first.scoring_input.personal == request.personal
    assert first.evaluation.result.behavior.metadata.sources == ("FDS",)


@pytest.mark.parametrize("change", ["request_text", "profile_state", "policy_content", "feature_score"])
def test_behavior_record_rejects_detached_content_and_scores(engine_cases, change):
    result = evaluate_engine_behavior(engine_cases.inputs(profile=True)[0])
    data = result.model_dump()
    if change == "request_text":
        data["request"]["analysis"]["user_request"]["text"] = "private-replacement-request"
    elif change == "profile_state":
        data["scoring_input"]["personal"]["expected_policy_state_version"] = "another-state"
    elif change == "policy_content":
        data["request"]["initial_policy"]["parameters"][0]["center"] += 1.0
    else:
        data["evaluation"]["result"]["features"][0]["combined"]["score"]["value"] = 12.0
    with pytest.raises(ValueError) as caught:
        EngineBehaviorResult.model_validate(data)
    assert "private-replacement-request" not in str(caught.value)
    assert "합성 구매 요청" not in repr(result)


def test_behavior_output_connects_to_existing_engine_record_without_running_gpt(engine_cases):
    request = engine_cases.inputs(profile=True)[0]
    result = evaluate_engine_behavior(request)
    at = request.feature_input.evaluation.evaluated_at
    record = EngineEvaluationResult(request=request, reference=result.reference, status="INCOMPLETE",
        timing=dict(behavior_as_of=at, started_at=at, completed_at=at+timedelta(seconds=1), stages=[
            dict(stage="BEHAVIOR", status="SUCCESS", started_at=at, completed_at=at+timedelta(seconds=1)),
            *[dict(stage=s, status="NOT_RUN", skip_reason="NOT_REQUESTED") for s in ("EXTRACTION", "COMPARISON", "POLICY")]]),
        behavior_input=result.scoring_input, behavior=result.evaluation, analysis=None, policy_input=None, policy_result=None)
    assert EngineEvaluationResult.model_validate_json(record.model_dump_json()).behavior == result.evaluation
