"""One budget for read-only behavior and GPT execution; final risk assembly is separate."""

import asyncio

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.intent_analyzer import IntentAnalyzer
from copenhagen_fds.analysis.intent_extractor import CompletionClient
from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.engine_analysis import validate_engine_analysis_settings
from copenhagen_fds.engine_behavior import evaluate_engine_behavior
from copenhagen_fds.engine_stages import EngineStageRecorder, utc_now


class EngineExecutionCancelled(asyncio.CancelledError):
    """Cancellation still propagates; callers can explicitly retain partial evidence."""
    def __init__(self, result: EngineExecutionResult):
        super().__init__("engine execution cancelled")
        self.result = result


def _snapshot(state: EngineStageRecorder, budget: AnalysisBudget, termination: str) -> EngineExecutionResult:
    def values():
        elapsed = budget.elapsed_seconds
        ending = "BUDGET_EXHAUSTED" if termination != "CANCELLED" and elapsed >= budget.total_seconds else termination
        return dict(completed_at=utc_now(), elapsed_seconds=elapsed, termination=ending)

    ending = values()
    result = state.snapshot(budget_seconds=budget.total_seconds, elapsed_seconds=ending["elapsed_seconds"],
        termination=ending["termination"])
    # Include contract validation in the consumed budget. Only monotonic time,
    # completion and their derived status change; all component evidence stays fixed.
    result = result.model_copy(update=values())
    return result.model_copy(update={"status": result.expected_status()})


async def execute_engine(
    request: EngineEvaluationRequest, adapter: CompletionClient, *, budget: AnalysisBudget | None = None,
) -> EngineExecutionResult:
    """Run behavior -> extraction -> comparison once, preserving partial failures.

    Bad request/settings reject before work. CPU scoring is synchronous: a late
    result is retained with budget exhaustion, and no subsequent API is started.
    No worker threads survive timeout or cancellation. Caller owns the adapter.
    """
    request = EngineEvaluationRequest.model_validate(request.model_dump())
    validate_engine_analysis_settings(request, adapter)
    if utc_now() < request.feature_input.evaluation.evaluated_at:
        raise ValueError("engine cannot start before its fixed behavior cutoff")
    shared = budget if budget is not None else AnalysisBudget.start(request.execution.total_timeout_seconds)
    if shared.total_seconds > request.execution.total_timeout_seconds:
        raise ValueError("shared budget cannot exceed the configured total timeout")
    state = EngineStageRecorder(request)
    analyzer = IntentAnalyzer(adapter)

    async def checkpoint():
        # Deliver queued caller cancellation, including after synchronous work.
        await asyncio.sleep(0)
        if shared.remaining_seconds <= 0:
            state.skip_remaining("BUDGET_EXHAUSTED")
            return False
        return True

    async def analysis_stage(name, call):
        state.start(name)
        try:
            # Bound async work even for a caller-supplied completion client.
            # The real adapter also uses min(provider limit, same remaining budget).
            async with asyncio.timeout(min(request.execution.provider_timeout_seconds, shared.remaining_seconds)):
                result = await call()
            state.finish(result)
        except TimeoutError:
            state.fail("TIMEOUT")
        except ValueError:
            state.fail("INVALID_INPUT")
        except Exception:
            state.fail("INTERNAL_ERROR")

    try:
        if await checkpoint():
            state.start("BEHAVIOR")
            try:
                state.finish(evaluate_engine_behavior(request))
            except ValueError:
                state.fail("INVALID_INPUT")
                state.skip_remaining("UPSTREAM_FAILED")
            except Exception:
                state.fail("INTERNAL_ERROR")
        if await checkpoint() and state.stages["EXTRACTION"].skip_reason == "NOT_REQUESTED":
            await analysis_stage("EXTRACTION", lambda: analyzer.extract(request.analysis,
                trace_id=request.trace_id, budget=shared))
        if await checkpoint() and state.stages["COMPARISON"].skip_reason == "NOT_REQUESTED":
            extraction = state.results["EXTRACTION"]
            if extraction is None:
                state.skip_remaining("UPSTREAM_FAILED")
            else:
                await analysis_stage("COMPARISON", lambda: analyzer.compare_extraction(request.analysis,
                    extraction, trace_id=request.trace_id, budget=shared))
        await checkpoint()
    except asyncio.CancelledError:
        state.cancel()
        raise EngineExecutionCancelled(_snapshot(state, shared, "CANCELLED")) from None
    return _snapshot(state, shared, "COMPLETED")
