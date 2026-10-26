"""Task 287 contracts use real prior scorers with explicit synthetic analysis evidence."""

from datetime import timedelta

import pytest

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION
from copenhagen_fds.analysis.intent_analyzer import IntentAnalysisRun
from copenhagen_fds.contracts.analysis_results import IntentComparisonResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.engine_results import EngineEvaluationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_evaluation import evaluate_risk_policy


@pytest.fixture
def engine_result(engine_cases):
    at = engine_cases.at
    def result(*, intent="MATCH", extraction="SUCCESS", **kwargs):
        request, row = engine_cases.inputs(**kwargs)
        if extraction == "NOT_RUN":
            data = request.model_dump()
            data["analysis"]["user_request"]["text"] = None
            request = EngineEvaluationRequest.model_validate(data)

        def envelope(status, prompt, seconds):
            return dict(status=status, metadata=dict(target=request.analysis.target, trace_id=request.trace_id,
                sources=["MOCK"], evaluated_at=None if status == "NOT_RUN" else at + timedelta(seconds=seconds),
                model_version="fixture-resolved-model" if status == "SUCCESS" else None,
                prompt_version=prompt if status != "NOT_RUN" else None), telemetry=dict(gateway="MOCK"),
                reasons=[] if status == "SUCCESS" else [dict(code="TIMEOUT" if status == "FAILED" else "NOT_EXECUTED", source="MOCK")],
                failure=dict(kind="TIMEOUT", retryable=True) if status == "FAILED" else None, payload=None)

        first = envelope(extraction, EXTRACTION_PROMPT_VERSION, 0.4)
        first["input_source"] = request.analysis.user_request.source
        if extraction == "SUCCESS":
            first["payload"] = dict(items=[dict(name="Controlled comparison", quantity=1, brand=None, category=None)],
                budget=None, purpose=None, explicit_conditions=None, preferences=None, ambiguities=None)
        comparison_status = "NOT_RUN" if extraction != "SUCCESS" else "FAILED" if intent == "FAILED" else "SUCCESS"
        second = envelope(comparison_status, COMPARISON_PROMPT_VERSION, 0.9)
        if comparison_status == "SUCCESS":
            findings = [] if intent == "MATCH" else [dict(code="INTENT_MISMATCH" if intent == "MISMATCH" else "INTENT_UNCERTAIN",
                                                       dimension="PRODUCT", related_items=[])]
            second["payload"] = dict(comparison=intent, findings=findings, confirmation_requests=[])
            second["reasons"] = [dict(code=f["code"], source="MOCK") for f in findings]
        analysis = IntentAnalysisRun(extraction=first, comparison=second)
        source = analysis.comparison if extraction == "SUCCESS" else analysis.extraction
        projected = IntentComparisonResult.model_validate({**{key:getattr(source, key)
            for key in ("status", "metadata", "reasons", "telemetry", "failure")},
            "payload": source.payload if extraction == "SUCCESS" else None})
        supplied = RiskPolicyInput(request=row.case.input.evaluation.request, scoring_input=row.input,
            behavior=row.evaluation, intent=projected, policy=request.risk_policy,
            evaluated_at=at, policy_evaluated_at=at + timedelta(seconds=1))
        final = evaluate_risk_policy(supplied)

        def stage(name, status, start, end, skip="UPSTREAM_FAILED"):
            return dict(stage=name, status=status, started_at=None if status == "NOT_RUN" else at + timedelta(seconds=start),
                completed_at=None if status == "NOT_RUN" else at + timedelta(seconds=end),
                failure_code=("CALCULATION_FAILED" if name == "BEHAVIOR" else "TIMEOUT") if status == "FAILED" else None,
                skip_reason=skip if status == "NOT_RUN" else None)

        return EngineEvaluationResult(request=request, reference=EngineRequestReference.from_request(request),
            timing=dict(behavior_as_of=at, started_at=at, completed_at=at + timedelta(seconds=2), stages=[
                stage("BEHAVIOR", row.evaluation.result.behavior.status.value, 0, 0.1),
                stage("EXTRACTION", extraction, 0.1, 0.5, "MISSING_INPUT"),
                stage("COMPARISON", comparison_status, 0.5, 1), stage("POLICY", "SUCCESS", 1, 1.5)]),
            status=final.decision.status.value, behavior_input=row.input, behavior=row.evaluation,
            analysis=analysis, policy_input=supplied, policy_result=final)

    return result
