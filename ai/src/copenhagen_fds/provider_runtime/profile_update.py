"""Authorized full-replay profile update, independent of FDS evaluation and storage."""

import asyncio

from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateRequest, ProviderProfileUpdateResponse
from copenhagen_fds.engine_stages import utc_now
from copenhagen_fds.profile_policy_updates import update_profile_policy
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.boundary import ProviderRequest
from copenhagen_fds.provider_runtime.execution import ExecutionContext
from copenhagen_fds.provider_runtime.profile_projection import project_profile_update_response


async def update_profile_handler(
    request: ProviderRequest, context: ExecutionContext,
) -> ProviderProfileUpdateResponse:
    """Register for UPDATE_PROFILE after ProviderBoundary authorization.

    Validate against the submitted previous state; only the AI storage layer can
    compare with its actual current version and atomically persist this result.
    Synchronous replay is checked before/after, not preempted inside its CPU work.
    """
    context.checkpoint()
    if not isinstance(request, ProviderProfileUpdateRequest):
        raise ProviderContractError("INVALID_CONTRACT")
    request = ProviderProfileUpdateRequest.model_validate(request.model_dump())
    if not request.call.sent_at <= utc_now() < request.update.update.valid_until:
        raise ProviderContractError("INVALID_CONTRACT")
    await asyncio.sleep(0)
    context.checkpoint()
    result = update_profile_policy(request.state, request.update, request.replay.observations)
    await asyncio.sleep(0)
    context.checkpoint()
    completed_at = utc_now()
    if not request.call.sent_at <= completed_at < request.update.update.valid_until:
        raise ProviderContractError("INVALID_CONTRACT")
    response = project_profile_update_response(request, result, completed_at=completed_at)
    context.checkpoint()
    return response
