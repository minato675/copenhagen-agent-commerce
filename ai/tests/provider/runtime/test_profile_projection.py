"""S15P21A207-302: preserve policy evidence, references and non-persisted semantics."""

import pytest

from copenhagen_fds.provider_runtime.profile_projection import project_profile_create_response


@pytest.mark.parametrize("population_scope,empty", [(False, False), (False, True), (True, False), (True, True)])
def test_profile_projection_preserves_native_state_and_versions(profile_case, population_scope, empty):
    case = profile_case.create(population_scope=population_scope, empty=empty)
    before = (case.request.model_dump_json(), case.state.model_dump_json())
    response = project_profile_create_response(case.request, case.state, completed_at=case.response.completed_at)
    assert response == case.response
    assert type(response).model_validate_json(response.model_dump_json()).validate_for_request(case.request) == response
    assert response.storage_status == "NOT_PERSISTED"
    assert response.state.candidate.snapshot.profile.reference != response.state.published.profile.reference
    assert (case.request.model_dump_json(), case.state.model_dump_json()) == before


@pytest.mark.parametrize("mutation", ["other_owner", "missing_observation", "initial_version"])
def test_profile_projection_rejects_state_from_a_different_request(profile_case, mutation):
    case = profile_case.create()
    data = case.request.model_dump()
    if mutation == "other_owner":
        case = profile_case.create(empty=True)
        data = case.request.model_dump()
        data["profile"]["user_id"] = data["profile"]["reference"]["user_id"] = "other-owner"
    elif mutation == "missing_observation":
        data["replay"]["observations"] = data["replay"]["observations"][:1]
    else:
        data["profile"]["state_version"] = "other-statistical-version"
    request = type(case.request).model_validate(data)
    with pytest.raises(ValueError):
        project_profile_create_response(request, case.state, completed_at=case.response.completed_at)


@pytest.mark.parametrize("instant", ["before_submission", "at_expiry"])
def test_profile_projection_cannot_extend_validity_or_backdate(profile_case, instant):
    case = profile_case.create()
    at = case.request.profile.as_of if instant == "before_submission" else case.request.profile.valid_until
    with pytest.raises(ValueError, match="unexpired at completion"):
        project_profile_create_response(case.request, case.state, completed_at=at)
