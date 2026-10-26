"""Trusted EVALUATE handler using the native entry point and request-owned context."""

from dataclasses import dataclass, field

from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, ProviderEvaluationResponse
from copenhagen_fds.engine_stages import utc_now
from copenhagen_fds.native_evaluation import evaluate_fraud
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.boundary import ProviderRequest
from copenhagen_fds.provider_runtime.evaluation_projection import project_evaluation_response
from copenhagen_fds.provider_runtime.execution import ExecutionContext


@dataclass(frozen=True)
class EvaluationExecution:
    """Local-only pair; only response may cross the provider response boundary."""
    response: ProviderEvaluationResponse
    record: NativeEvaluationResult = field(repr=False)


async def evaluate_provider_request(
    request: ProviderRequest, context: ExecutionContext,
) -> EvaluationExecution:
    """Called after ProviderBoundary authorization; no loaders, writers or retries.

    NativeEvaluationCancelled keeps its partial record for trusted local callers.
    The runtime propagates cancellation and never serializes that private record.
    """
    context.checkpoint()
    if not isinstance(request, ProviderEvaluationRequest):
        raise ProviderContractError("INVALID_CONTRACT")
    request = ProviderEvaluationRequest.model_validate(request.model_dump())
    # The response contract requires every executed stage to follow submission.
    # Reject an impossible interval before making a paid model call.
    if request.call.sent_at > utc_now():
        raise ProviderContractError("INVALID_CONTRACT")
    context.checkpoint()
    record = await evaluate_fraud(request.evaluation, context, budget=context.budget)
    context.checkpoint()
    response = project_evaluation_response(request, record)
    context.checkpoint()
    return EvaluationExecution(response=response, record=record)


async def evaluate_handler(request: ProviderRequest, context: ExecutionContext) -> ProviderEvaluationResponse:
    """Register as handlers['EVALUATE']; the full local record is never returned."""
    return (await evaluate_provider_request(request, context)).response
