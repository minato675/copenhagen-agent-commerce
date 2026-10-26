"""Use the caller-owned GPT adapter without changing input, settings or budget."""

from datetime import datetime, timezone

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.intent_analyzer import IntentAnalyzer
from copenhagen_fds.analysis.intent_extractor import CompletionClient
from copenhagen_fds.contracts.engine_analysis import EngineAnalysisResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.settings import EvaluationMode


def validate_engine_analysis_settings(request: EngineEvaluationRequest, adapter: CompletionClient) -> None:
    """Preflight the caller-owned adapter before any engine stage starts."""
    settings = OpenAISettings.model_validate(adapter.settings.model_dump())
    config = request.execution
    gateway = "MOCK" if settings.mode == EvaluationMode.MOCK else "GMS"
    if (gateway, settings.openai_model, settings.openai_timeout_seconds, settings.evaluation_timeout_seconds) != (
        config.gateway, config.requested_model, config.provider_timeout_seconds, config.total_timeout_seconds,
    ):
        raise ValueError("analysis adapter must match the pinned engine execution settings")


async def evaluate_engine_analysis(
    request: EngineEvaluationRequest, adapter: CompletionClient, *, budget: AnalysisBudget | None = None,
) -> EngineAnalysisResult:
    """Preserve the existing analyzer's failures, clarifications and cancellation.

    Adapter lifecycle and loading secrets remain with the caller. A supplied
    shared budget is forwarded unchanged; whole-engine scheduling is separate.
    """
    request = EngineEvaluationRequest.model_validate(request.model_dump())
    validate_engine_analysis_settings(request, adapter)
    config = request.execution
    if budget is not None and budget.total_seconds > config.total_timeout_seconds:
        raise ValueError("analysis budget cannot exceed the engine total timeout")
    started_at = datetime.now(timezone.utc)
    if started_at < request.feature_input.evaluation.evaluated_at:
        raise ValueError("analysis cannot start before the fixed behavior cutoff")
    analysis = await IntentAnalyzer(adapter).analyze(request.analysis, trace_id=request.trace_id, budget=budget)
    return EngineAnalysisResult(request=request, reference=EngineRequestReference.from_request(request),
        started_at=started_at, completed_at=datetime.now(timezone.utc), analysis=analysis)
