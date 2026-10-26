"""S15P21A207-289: policy signals preserve both stages and the first failure."""

from datetime import datetime, timedelta, timezone
import json

import pytest

from copenhagen_fds.analysis.openai_adapter import OpenAIAnalysisAdapter
from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.engine_analysis import EngineAnalysisResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_intent import EngineIntentEvidence
from copenhagen_fds.contracts.engine_results import EngineEvaluationResult
from copenhagen_fds.engine_analysis import evaluate_engine_analysis
from copenhagen_fds.engine_behavior import evaluate_engine_behavior
from copenhagen_fds.engine_intent import build_engine_intent_policy_input, project_engine_intent
from copenhagen_fds.risk_evaluation import evaluate_risk_policy
from copenhagen_fds.risk_intent import interpret_risk_intent
from copenhagen_fds.risk_intent_policy import evaluate_risk_intent_policy


def linked(cases, run_async, signal="MATCH", failure_stage="extraction", failure=None):
    request = cases.request
    if signal == "NOT_RUN":
        data = request.model_dump()
        data["analysis"]["user_request"]["text"] = None
        request = EngineEvaluationRequest.model_validate(data)
    started = datetime.now(timezone.utc)
    behavior = evaluate_engine_behavior(request)
    adapter = cases.adapter(signal if signal in {"MATCH", "MISMATCH", "UNKNOWN"} else "MATCH")
    if signal == "FAILED":
        adapter.outcomes[failure_stage] = failure or AnalysisFailure(kind="API_ERROR", http_status=429,
            api_error="RATE_LIMIT", retryable=True)
    record = run_async(evaluate_engine_analysis(request, adapter))
    evidence = project_engine_intent(record)
    supplied = build_engine_intent_policy_input(evidence, scoring_input=behavior.scoring_input,
        behavior=behavior.evaluation, policy_evaluated_at=record.completed_at + timedelta(seconds=1))
    return started, adapter, behavior, evidence, supplied


@pytest.mark.parametrize("signal,expected", [("MATCH", "SUCCESS"), ("MISMATCH", "SUCCESS"),
    ("UNKNOWN", "INCOMPLETE"), ("FAILED", "FAILED"), ("NOT_RUN", "INCOMPLETE")])
def test_five_signals_feed_existing_policy_and_full_engine_record(analysis_cases, run_async, signal, expected):
    started, adapter, behavior, evidence, supplied = linked(analysis_cases, run_async, signal)
    before = supplied.model_dump_json()
    record = evidence.analysis
    intent_policy = evaluate_risk_intent_policy(supplied)
    assert intent_policy.intent.signal == evidence.signal == signal
    assert intent_policy.status == expected
    assert interpret_risk_intent(supplied).source == evidence.intent
    assert intent_policy.intent.reasons == evidence.reasons
    assert supplied.request == record.request.feature_input.evaluation.request
    assert supplied.request.intent != record.analysis.extraction.payload
    assert supplied.scoring_input == behavior.scoring_input and supplied.behavior == behavior.evaluation
    assert len(adapter.calls) == (0 if signal == "NOT_RUN" else 1 if signal == "FAILED" else 2)
    assert EngineIntentEvidence.model_validate_json(evidence.model_dump_json()).validate_for_input(supplied) == evidence
    final = evaluate_risk_policy(supplied)

    stages = [dict(stage="BEHAVIOR", status=behavior.evaluation.result.behavior.status.value,
        started_at=started, completed_at=record.started_at)]
    previous = record.started_at
    for stage, source in (("EXTRACTION", record.analysis.extraction), ("COMPARISON", record.analysis.comparison)):
        if source.status == "NOT_RUN":
            stages.append(dict(stage=stage, status="NOT_RUN", skip_reason="MISSING_INPUT" if stage == "EXTRACTION" else "UPSTREAM_FAILED"))
        else:
            stages.append(dict(stage=stage, status=source.status.value, started_at=previous,
                completed_at=source.metadata.evaluated_at, failure_code=None if source.failure is None else source.failure.kind.value))
            previous = source.metadata.evaluated_at
    stages.append(dict(stage="POLICY", status="SUCCESS", started_at=supplied.policy_evaluated_at,
        completed_at=supplied.policy_evaluated_at + timedelta(seconds=1)))
    full = EngineEvaluationResult(request=record.request, reference=record.reference, status=final.decision.status.value,
        timing=dict(behavior_as_of=record.reference.behavior_as_of, started_at=started,
            completed_at=stages[-1]["completed_at"], stages=stages), behavior_input=supplied.scoring_input,
        behavior=supplied.behavior, analysis=record.analysis, policy_input=supplied, policy_result=final)
    assert full.analysis == record.analysis and full.policy_input.intent == evidence.intent
    assert supplied.model_dump_json() == before
    if signal == "FAILED":
        assert full.status == "FAILED" and evidence.failure_stage == "EXTRACTION"
        assert full.timing.stage("EXTRACTION").failure_code == "API_ERROR"
    else:
        assert evidence.failure_stage is None


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
@pytest.mark.parametrize("failure", [
    AnalysisFailure(kind="API_ERROR", http_status=401, api_error="AUTHENTICATION", retryable=False),
    AnalysisFailure(kind="TIMEOUT", retryable=True),
    AnalysisFailure(kind="REFUSAL", retryable=False),
    AnalysisFailure(kind="SCHEMA_ERROR", diagnostics=[dict(phase="PAYLOAD_SCHEMA", code="EXTRA_FIELD", path=["*"])])])
def test_first_failure_kind_metadata_diagnostics_and_retry_advice_survive(analysis_cases, run_async, stage, failure):
    _, _, _, evidence, supplied = linked(analysis_cases, run_async, "FAILED", stage, failure)
    source = getattr(evidence.analysis.analysis, stage)
    assert evidence.source_stage == evidence.failure_stage == stage.upper()
    assert evidence.intent.failure == source.failure == failure
    for name in ("status", "metadata", "telemetry", "reasons"):
        assert getattr(evidence.intent, name) == getattr(source, name)
    policy = evaluate_risk_intent_policy(supplied)
    assert policy.status == "FAILED" and "RETRY_EVALUATION" in policy.review_actions
    assert policy.intent.source.failure == failure


def test_clarifications_findings_and_original_provenance_survive_without_rule_promotion(analysis_cases, run_async):
    request = analysis_cases.request
    before = request.model_dump_json()
    adapter = analysis_cases.adapter("UNKNOWN")
    adapter.outcomes["extraction"] = {**analysis_cases.extracted, "items": None, "purpose": "synthetic-purpose"}
    record = run_async(evaluate_engine_analysis(request, adapter))
    evidence = project_engine_intent(record)
    assert record.analysis.extraction.payload.ambiguities is None
    assert record.analysis.extraction.clarifications
    compared = json.loads(adapter.calls[1][0].user_message)
    assert compared["clarifications"] == list(record.analysis.extraction.clarifications)
    assert evidence.intent.payload.confirmation_requests == record.analysis.comparison.payload.confirmation_requests
    assert evidence.intent.payload.findings[0].related_items[0].model_dump() == analysis_cases.refs[0]
    behavior = evaluate_engine_behavior(request)
    supplied = build_engine_intent_policy_input(evidence, scoring_input=behavior.scoring_input,
        behavior=behavior.evaluation, policy_evaluated_at=record.completed_at)
    assert supplied.request.intent == request.feature_input.evaluation.request.intent
    assert supplied.request.intent.source == "agent_summary"
    assert request.model_dump_json() == before
    assert "synthetic-private-intent" not in repr(evidence)


@pytest.mark.parametrize("change", ["source_stage", "failure_stage", "masked_failure", "signal", "reason", "text"])
def test_projection_cannot_hide_failure_or_switch_original_evidence(analysis_cases, run_async, change):
    _, _, _, evidence, _ = linked(analysis_cases, run_async, "FAILED")
    data = evidence.model_dump()
    if change == "source_stage":
        data["source_stage"] = "COMPARISON"
    elif change == "failure_stage":
        data["failure_stage"] = None
    elif change == "masked_failure":
        data["intent"] = data["analysis"]["analysis"]["comparison"]
    elif change == "signal":
        data["signal"] = "NOT_RUN"
    elif change == "reason":
        data["reasons"] = []
    else:
        data["analysis"]["request"]["analysis"]["user_request"]["text"] = "synthetic-private-replacement"
    with pytest.raises(ValueError) as caught:
        EngineIntentEvidence.model_validate(data)
    assert "synthetic-private-replacement" not in str(caught.value)


@pytest.mark.parametrize("change", ["policy", "trace", "order", "scoring", "intent", "decision_before_end"])
def test_policy_binding_rejects_detached_input_settings_and_time(analysis_cases, run_async, change):
    _, _, _, evidence, supplied = linked(analysis_cases, run_async)
    data = supplied.model_dump()
    if change == "policy":
        data["policy"]["version"] = "other"
    elif change == "trace":
        data["intent"]["metadata"]["trace_id"] = "other"
    elif change == "order":
        data["request"]["order"]["reference"]["version"] = "other"
    elif change == "scoring":
        data["scoring_input"]["config"]["version"] = "other"
    elif change == "intent":
        data["intent"]["telemetry"]["response_id"] = "other-response"
    else:
        data["policy_evaluated_at"] = evidence.analysis.started_at - timedelta(seconds=1)
    with pytest.raises(ValueError):
        evidence.validate_for_input(type(supplied).model_validate(data))


def test_stale_extraction_cannot_be_revived_by_fresh_comparison(analysis_cases, run_async):
    _, _, behavior, evidence, supplied = linked(analysis_cases, run_async)
    data = evidence.analysis.model_dump()
    at = evidence.analysis.started_at
    data["analysis"]["extraction"]["metadata"]["evaluated_at"] = at
    data["analysis"]["comparison"]["metadata"]["evaluated_at"] = at + timedelta(seconds=1)
    data["completed_at"] = at + timedelta(seconds=2)
    revised = project_engine_intent(EngineAnalysisResult.model_validate(data))
    expiry = at + timedelta(seconds=analysis_cases.request.risk_policy.intent_max_age_seconds)
    accepted = build_engine_intent_policy_input(revised, scoring_input=behavior.scoring_input,
        behavior=behavior.evaluation, policy_evaluated_at=expiry - timedelta(microseconds=1))
    assert accepted.intent == revised.intent
    with pytest.raises(ValueError, match="age limit"):
        build_engine_intent_policy_input(revised, scoring_input=behavior.scoring_input,
            behavior=behavior.evaluation, policy_evaluated_at=expiry)
    data["analysis"]["extraction"]["metadata"]["valid_until"] = at + timedelta(seconds=5)
    revised = project_engine_intent(EngineAnalysisResult.model_validate(data))
    with pytest.raises(ValueError, match="expired"):
        build_engine_intent_policy_input(revised, scoring_input=behavior.scoring_input,
            behavior=behavior.evaluation, policy_evaluated_at=at + timedelta(seconds=5))


def test_projection_and_json_restore_run_no_gpt_scoring_or_environment_loader(analysis_cases, run_async, monkeypatch):
    import copenhagen_fds.personal_evaluation as scoring
    import copenhagen_fds.openai_settings as settings
    from copenhagen_fds.analysis.intent_analyzer import IntentAnalyzer

    _, _, behavior, evidence, supplied = linked(analysis_cases, run_async, "UNKNOWN")
    before = evidence.model_dump_json(), supplied.model_dump_json()
    def forbidden(*args, **kwargs):
        raise AssertionError("projection must not run models or load settings")
    monkeypatch.setattr(scoring, "evaluate_personalized_features", forbidden)
    monkeypatch.setattr(IntentAnalyzer, "analyze", forbidden)
    monkeypatch.setattr(OpenAIAnalysisAdapter, "complete", forbidden)
    monkeypatch.setattr(settings, "load_openai_settings", forbidden)
    restored = EngineIntentEvidence.model_validate_json(evidence.model_dump_json())
    again = build_engine_intent_policy_input(project_engine_intent(restored.analysis),
        scoring_input=behavior.scoring_input, behavior=behavior.evaluation, policy_evaluated_at=supplied.policy_evaluated_at)
    assert restored.validate_for_input(again) == evidence and again == supplied
    assert (evidence.model_dump_json(), supplied.model_dump_json()) == before
    assert not {"score", "probability", "recommendation"}.intersection(type(evidence).model_fields)
