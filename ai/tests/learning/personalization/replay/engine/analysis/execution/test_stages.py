"""S15P21A207-290: actual stage intervals, partial evidence and prior analyzer parity."""

from datetime import timedelta

import pytest

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.intent_analyzer import IntentAnalyzer
from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.engine_behavior import evaluate_engine_behavior
from copenhagen_fds.engine_stages import EngineStageRecorder


def recorded(cases, run_async, *, signal="MATCH", failure=None, partial=False, behavior_failure=False):
    state = EngineStageRecorder(cases.request)
    state.start("BEHAVIOR")
    if behavior_failure:
        state.fail("INTERNAL_ERROR")
    else:
        state.finish(evaluate_engine_behavior(cases.request))
    adapter = cases.adapter(signal)
    if failure is not None:
        adapter.outcomes["extraction"] = failure
    analyzer, budget = IntentAnalyzer(adapter), AnalysisBudget.start(10)
    state.start("EXTRACTION")
    first = run_async(analyzer.extract(cases.request.analysis, trace_id=cases.request.trace_id, budget=budget))
    state.finish(first)
    if partial:
        state.skip_remaining("BUDGET_EXHAUSTED")
    else:
        state.start("COMPARISON")
        state.finish(run_async(analyzer.compare_extraction(cases.request.analysis, first,
            trace_id=cases.request.trace_id, budget=budget)))
    return state.snapshot(budget_seconds=10.0, elapsed_seconds=10.0 if partial else 0.0,
        termination="BUDGET_EXHAUSTED" if partial else "COMPLETED"), adapter


@pytest.mark.parametrize("signal", ["MATCH", "MISMATCH", "UNKNOWN"])
def test_separate_stages_preserve_existing_results_and_actual_intervals(analysis_cases, run_async, signal):
    result, adapter = recorded(analysis_cases, run_async, signal=signal)
    assert [p.stage for p, _ in adapter.calls] == ["extraction", "comparison"]
    assert all(b is adapter.calls[0][1] for _, b in adapter.calls)
    assert result.behavior == evaluate_engine_behavior(analysis_cases.request)
    assert result.to_analysis_result().analysis.comparison.payload.comparison == signal
    previous = result.started_at
    for stage in result.stages:
        assert previous <= stage.started_at <= stage.completed_at <= result.completed_at
        previous = stage.completed_at
    assert result.reference.behavior_as_of == analysis_cases.request.feature_input.evaluation.evaluated_at
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()).validate_for_request(result.request) == result


def test_partial_successful_extraction_survives_without_fake_comparison(analysis_cases, run_async):
    result, adapter = recorded(analysis_cases, run_async, partial=True)
    assert result.status == "FAILED" and result.termination == "BUDGET_EXHAUSTED"
    assert len(adapter.calls) == 1 and result.extraction.status == "SUCCESS" and result.comparison is None
    assert result.stage("COMPARISON").skip_reason == "BUDGET_EXHAUSTED"
    assert result.stage("COMPARISON").started_at is None
    assert result.behavior is not None
    with pytest.raises(ValueError, match="interrupted"):
        result.to_analysis_result()
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result


def test_independent_failures_and_original_details_are_not_collapsed(analysis_cases, run_async):
    failure = AnalysisFailure(kind="API_ERROR", http_status=429, api_error="RATE_LIMIT", retryable=True)
    result, adapter = recorded(analysis_cases, run_async, failure=failure, behavior_failure=True)
    assert result.status == "FAILED" and len(adapter.calls) == 1
    assert result.stage("BEHAVIOR").failure_code == "INTERNAL_ERROR"
    assert result.stage("EXTRACTION").failure_code == "API_ERROR" and result.extraction.failure == failure
    assert result.comparison.status == "NOT_RUN"
    assert result.to_analysis_result().analysis.extraction.failure == failure


@pytest.mark.parametrize("change", ["target", "text", "status", "trace", "stage_status", "stage_order", "stage_time",
    "failure_kind", "elapsed", "budget", "missing_evidence"])
def test_execution_contract_rejects_broken_context_time_and_outcomes(analysis_cases, run_async, change):
    result, _ = recorded(analysis_cases, run_async,
        failure=AnalysisFailure(kind="TIMEOUT") if change in {"status", "failure_kind"} else None)
    data = result.model_dump()
    if change == "target":
        data["reference"]["target"]["user_id"] = "other"
    elif change == "text":
        data["request"]["analysis"]["user_request"]["text"] = "private-altered"
    elif change == "status":
        data["status"] = "SUCCESS"
    elif change == "trace":
        data["extraction"]["metadata"]["trace_id"] = "other"
    elif change == "stage_status":
        data["stages"][1].update(status="FAILED", failure_code="INTERNAL_ERROR")
    elif change == "stage_order":
        data["stages"] = tuple(reversed(data["stages"]))
    elif change == "stage_time":
        data["stages"][2]["started_at"] = result.started_at - timedelta(seconds=1)
    elif change == "failure_kind":
        data["stages"][1]["failure_code"] = "API_ERROR"
    elif change == "elapsed":
        data["elapsed_seconds"] = 10.0
    elif change == "budget":
        data["budget_seconds"] = 20.0
    else:
        data["extraction"] = None
    with pytest.raises(ValueError) as caught:
        EngineExecutionResult.model_validate(data)
    assert "private-altered" not in str(caught.value)


def test_cancelled_stage_has_real_interval_without_api_failure_classification(analysis_cases):
    state = EngineStageRecorder(analysis_cases.request)
    state.start("BEHAVIOR")
    state.finish(evaluate_engine_behavior(analysis_cases.request))
    state.start("EXTRACTION")
    state.cancel()
    result = state.snapshot(budget_seconds=10.0, elapsed_seconds=0.0, termination="CANCELLED")
    assert result.status == "CANCELLED" and result.behavior is not None
    assert result.stage("EXTRACTION").status == "CANCELLED"
    assert result.stage("EXTRACTION").failure_code is None and result.stage("EXTRACTION").started_at is not None
    assert result.stage("COMPARISON").skip_reason == "CANCELLED"
    assert EngineExecutionResult.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("reason", ["CANCELLED", "BUDGET_EXHAUSTED", "NOT_REQUESTED"])
def test_interruption_cannot_claim_later_execution_or_an_unresolved_stage(analysis_cases, run_async, reason):
    result, _ = recorded(analysis_cases, run_async)
    data = result.model_dump()
    data["behavior"] = None
    data["stages"] = (dict(stage="BEHAVIOR", status="NOT_RUN", skip_reason=reason), *data["stages"][1:])
    data.update(termination=reason if reason != "NOT_REQUESTED" else "COMPLETED",
        elapsed_seconds=10.0 if reason == "BUDGET_EXHAUSTED" else 0.0,
        status="CANCELLED" if reason == "CANCELLED" else "FAILED" if reason == "BUDGET_EXHAUSTED" else "INCOMPLETE")
    with pytest.raises(ValueError):
        EngineExecutionResult.model_validate(data)
