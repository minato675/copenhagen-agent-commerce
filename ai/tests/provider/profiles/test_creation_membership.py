"""S15P21A207-302: canonical member order retains strict cohort identity checks."""

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash


def test_sorted_native_population_response_binds_unsorted_members(profile_case):
    case = profile_case.create(population_scope=True)
    data = case.request.model_dump()
    data["profile"]["cohort"]["member_user_ids"] = tuple(reversed(data["profile"]["cohort"]["member_user_ids"]))
    request = type(case.request).model_validate(data)
    response = type(case.response).model_validate({**case.response.model_dump(), "request_sha256": personalization_hash(request)})
    assert response.validate_for_request(request).state == case.state
    assert request.profile.cohort.member_user_ids != response.state.published.cohort.member_user_ids


@pytest.mark.parametrize("field,value", [("membership_version", "other-version"),
    ("definition", "Different cohort definition"), ("member_user_ids", ("a", "b", "other-empty-member"))])
def test_creation_still_rejects_changed_membership_evidence(profile_case, field, value):
    case = profile_case.create(population_scope=True)
    data = case.request.model_dump()
    data["profile"]["cohort"][field] = value
    request = type(case.request).model_validate(data)
    response = type(case.response).model_validate({**case.response.model_dump(), "request_sha256": personalization_hash(request)})
    with pytest.raises(ValueError, match="preserve cohort membership"):
        response.validate_for_request(request)
