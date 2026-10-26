"""Native FDS entry point; independent of the existing evaluation dataset/CLI package."""

import asyncio

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.intent_extractor import CompletionClient
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.engine import EngineExecutionCancelled, execute_engine
from copenhagen_fds.engine_finalization import finalize_engine_execution, refresh_native_result


class NativeEvaluationCancelled(asyncio.CancelledError):
    def __init__(self, result: NativeEvaluationResult):
        super().__init__("native evaluation cancelled")
        self.result = result


async def evaluate_fraud(
    request: EngineEvaluationRequest, adapter: CompletionClient, *, budget: AnalysisBudget | None = None,
) -> NativeEvaluationResult:
    """Execute behavior/GPT and finalize once with one caller-owned or newly started budget."""
    request = EngineEvaluationRequest.model_validate(request.model_dump())
    shared = budget if budget is not None else AnalysisBudget.start(request.execution.total_timeout_seconds)
    try:
        execution = await execute_engine(request, adapter, budget=shared)
    except EngineExecutionCancelled as error:
        result = finalize_engine_execution(error.result, budget=shared)
        raise NativeEvaluationCancelled(result) from None
    result = None
    try:
        await asyncio.sleep(0)
        result = finalize_engine_execution(execution, budget=shared)
        await asyncio.sleep(0)
    except asyncio.CancelledError:
        result = (finalize_engine_execution(execution, budget=shared, cancelled=True) if result is None else
                  refresh_native_result(result, shared, cancelled=True))
        raise NativeEvaluationCancelled(result) from None
    return refresh_native_result(result, shared)
