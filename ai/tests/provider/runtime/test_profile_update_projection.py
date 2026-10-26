"""S15P21A207-303: bind native update evidence without recalculation or mutation."""

import pytest

from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyUpdateResult
from copenhagen_fds.provider_runtime.profile_projection import project_profile_update_response


@pytest.mark.parametrize("population_scope,remove", [(False, False), (False, True), (True, False), (True, True)])
def test_projection_preserves_native_result_and_previous_state(profile_case, population_scope, remove):
    case = profile_case.update(population_scope=population_scope, remove=remove)
    result = case.response.result
    before = (case.request.model_dump_json(), result.model_dump_json())
    response = project_profile_update_response(case.request, result, completed_at=case.response.completed_at)
    assert response == case.response and response.storage_status == "NOT_PERSISTED"
    assert type(response).model_validate_json(response.model_dump_json()).validate_for_request(case.request) == response
    assert (case.request.model_dump_json(), result.model_dump_json()) == before


@pytest.mark.parametrize("status,issue", [("REJECTED", "TIME_REGRESSION"), ("REPLAY_REQUIRED", "RETENTION_GAP")])
def test_projection_preserves_failure_issue_without_partial_state(profile_case, status, issue):
    case = profile_case.update()
    result = ProfilePolicyUpdateResult(status=status, issue=issue,
        previous_state_version=case.request.state.state_version,
        previous_published_reference=case.request.state.published.profile.reference)
    response = project_profile_update_response(case.request, result, completed_at=case.response.completed_at)
    assert response.result == result and response.result.state is None
    assert not response.result.candidate_events and not response.result.transitions


@pytest.mark.parametrize("mismatch", ["policy_version", "published_reference", "observations"])
def test_projection_rejects_results_not_bound_to_original_request(profile_case, mismatch):
    case = profile_case.update()
    result = case.response.result
    if mismatch == "observations":
        data = case.request.model_dump()
        data["replay"]["observations"] = ()
        case.request = type(case.request).model_validate(data)
    else:
        data = result.model_dump()
        if mismatch == "policy_version":
            data["previous_state_version"] = "other-policy-version"
        else:
            data["previous_published_reference"]["version"] = "other-publication-version"
        result = type(result).model_validate(data)
    with pytest.raises(ValueError):
        project_profile_update_response(case.request, result, completed_at=case.response.completed_at)
