"""Apply the existing risk policy to completed evidence with the original time budget."""

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.contracts.engine_results import EngineStageExecution
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult, native_outcome
from copenhagen_fds.contracts.risk_evaluation import RiskEvaluationResult
from copenhagen_fds.engine_intent import build_engine_intent_policy_input, project_engine_intent
from copenhagen_fds.engine_stages import utc_now
from copenhagen_fds.risk_evaluation import evaluate_risk_policy


def validate_finalization_budget(execution: EngineExecutionResult, budget: AnalysisBudget) -> None:
    if budget.total_seconds != execution.budget_seconds or budget.elapsed_seconds < execution.elapsed_seconds:
        raise ValueError("finalization must retain the original consumed budget")


def refresh_native_result(result: NativeEvaluationResult, budget: AnalysisBudget, *, cancelled=False) -> NativeEvaluationResult:
    """Account for final validation/checkpoint time without recalculating any evidence."""
    validate_finalization_budget(result.execution, budget)
    elapsed = budget.elapsed_seconds
    if elapsed < result.elapsed_seconds:
        raise ValueError("finalization clock cannot rewind consumed time")
    termination = ("CANCELLED" if cancelled or result.termination == "CANCELLED" else
                   "BUDGET_EXHAUSTED" if elapsed >= budget.total_seconds else result.termination)
    return result.model_copy(update=dict(completed_at=utc_now(), elapsed_seconds=elapsed, termination=termination,
        outcome=native_outcome(result.execution, result.policy_stage, result.policy_result, termination)))


def finalize_engine_execution(
    execution: EngineExecutionResult, *, budget: AnalysisBudget, cancelled: bool = False,
) -> NativeEvaluationResult:
    """No scoring/GPT reruns. Keep partial/late evidence without claiming a final risk."""
    execution = EngineExecutionResult.model_validate(execution.model_dump())
    validate_finalization_budget(execution, budget)
    policy_input = policy_result = None
    termination = "CANCELLED" if cancelled or execution.termination == "CANCELLED" else execution.termination
    if termination == "CANCELLED":
        skip = "CANCELLED"
    elif budget.remaining_seconds <= 0:
        skip, termination = "BUDGET_EXHAUSTED", "BUDGET_EXHAUSTED"
    elif execution.behavior is None or execution.extraction is None or execution.comparison is None:
        skip = "UPSTREAM_FAILED"
    else:
        skip = None
    if skip is not None:
        stage = EngineStageExecution(stage="POLICY", status="NOT_RUN", skip_reason=skip)
    else:
        started, failure = utc_now(), None
        try:
            policy_input = build_engine_intent_policy_input(project_engine_intent(execution.to_analysis_result()),
                scoring_input=execution.behavior.scoring_input, behavior=execution.behavior.evaluation,
                policy_evaluated_at=started)
            if budget.remaining_seconds <= 0:
                failure = "TIMEOUT"
            else:
                candidate = evaluate_risk_policy(policy_input)
                policy_result = RiskEvaluationResult.model_validate(candidate.model_dump()).validate_for_input(policy_input)
        except ValueError:
            failure = "INVALID_INPUT"
        except Exception:
            failure = "INTERNAL_ERROR"
        stage = EngineStageExecution(stage="POLICY", status="FAILED" if failure else "SUCCESS",
            started_at=started, completed_at=utc_now(), failure_code=failure)
    elapsed = budget.elapsed_seconds
    if termination != "CANCELLED" and elapsed >= budget.total_seconds:
        termination = "BUDGET_EXHAUSTED"
    result = NativeEvaluationResult(execution=execution, policy_stage=stage, policy_input=policy_input,
        policy_result=policy_result, completed_at=utc_now(), elapsed_seconds=elapsed, termination=termination,
        outcome=native_outcome(execution, stage, policy_result, termination))
    return refresh_native_result(result, budget)
