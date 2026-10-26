"""Bind computed candidate/admitted policy state to its creation or update request."""

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState, ProfilePolicyUpdateResult
from copenhagen_fds.contracts.provider_profiles import (
    ProviderProfileCreateRequest, ProviderProfileCreateResponse, ProviderProfileUpdateRequest, ProviderProfileUpdateResponse,
)
from copenhagen_fds.contracts.results import Timestamp


def project_profile_create_response(
    request: ProviderProfileCreateRequest, state: ProfilePolicyState, *, completed_at: Timestamp,
) -> ProviderProfileCreateResponse:
    """Local trusted projection, without recalculation, expiry extension or persistence.

    Contains retained learning evidence for the authorized AI service/storage layer;
    unlike the evaluation summary, this is not a user-facing payload.
    """
    request = ProviderProfileCreateRequest.model_validate(request.model_dump())
    state = ProfilePolicyState.model_validate(state.model_dump())
    return ProviderProfileCreateResponse(operation=request.operation, call=request.call,
        request_sha256=personalization_hash(request), completed_at=completed_at,
        state=state).validate_for_request(request)


def project_profile_update_response(
    request: ProviderProfileUpdateRequest, result: ProfilePolicyUpdateResult, *, completed_at: Timestamp,
) -> ProviderProfileUpdateResponse:
    """Preserve native status, issues and transitions; no retry, CAS or state write.

    A rejected/replay-required calculation keeps its issue and has no partial state.
    This local trusted utility does not accept caller-supplied results at an endpoint.
    """
    request = ProviderProfileUpdateRequest.model_validate(request.model_dump())
    result = ProfilePolicyUpdateResult.model_validate(result.model_dump())
    return ProviderProfileUpdateResponse(operation="UPDATE_PROFILE", call=request.call,
        request_sha256=personalization_hash(request), completed_at=completed_at,
        result=result).validate_for_request(request)
