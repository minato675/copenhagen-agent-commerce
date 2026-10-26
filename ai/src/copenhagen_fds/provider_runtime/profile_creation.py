"""Authorized initial profile computation; never invoked implicitly by evaluation."""

import asyncio

from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest, ProviderProfileCreateResponse
from copenhagen_fds.engine_stages import utc_now
from copenhagen_fds.profile_policy_updates import initialize_profile_policy
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.boundary import ProviderRequest
from copenhagen_fds.provider_runtime.execution import ExecutionContext
from copenhagen_fds.provider_runtime.profile_candidate import build_profile_candidate
from copenhagen_fds.provider_runtime.profile_projection import project_profile_create_response


async def create_profile_handler(
    request: ProviderRequest, context: ExecutionContext,
) -> ProviderProfileCreateResponse:
    """Register for both CREATE_* operations after ProviderBoundary authorization.

    Synchronous statistics/admission are bounded by input limits, with cooperative
    cancellation/deadline checks between phases, not preemption inside a phase.
    No model resources, loaders, writers, retries or profile version allocation.
    """
    context.checkpoint()
    if not isinstance(request, ProviderProfileCreateRequest):
        raise ProviderContractError("INVALID_CONTRACT")
    request = ProviderProfileCreateRequest.model_validate(request.model_dump())
    if not request.call.sent_at <= utc_now() < request.profile.valid_until:
        raise ProviderContractError("INVALID_CONTRACT")
    context.checkpoint()
    candidate = build_profile_candidate(request)
    await asyncio.sleep(0)
    context.checkpoint()
    state = initialize_profile_policy(candidate, request.replay.observations, request.policy)
    await asyncio.sleep(0)
    context.checkpoint()
    completed_at = utc_now()
    if not request.call.sent_at <= completed_at < request.profile.valid_until:
        raise ProviderContractError("INVALID_CONTRACT")
    response = project_profile_create_response(request, state, completed_at=completed_at)
    context.checkpoint()
    return response
