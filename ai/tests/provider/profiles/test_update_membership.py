"""S15P21A207-303: member order is equivalent, changed cohort evidence is not."""

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.profile_policy_updates import update_profile_policy


def test_update_allows_reordered_members_and_preserves_native_result(profile_case):
    case = profile_case.update(population_scope=True)
    data = case.request.model_dump()
    cohort = case.request.state.candidate.snapshot.cohort.model_dump()
    cohort["member_user_ids"] = tuple(reversed(cohort["member_user_ids"]))
    data["update"]["update"]["cohort"] = cohort
    request = type(case.request).model_validate(data)
    result = update_profile_policy(request.state, request.update, request.replay.observations)
    response = type(case.response).model_validate({**case.response.model_dump(),
        "request_sha256": personalization_hash(request), "result": result})
    assert response.validate_for_request(request).result == case.response.result


@pytest.mark.parametrize("field", ["membership_version", "definition"])
def test_update_rejects_changed_cohort_in_request_and_response(profile_case, field):
    case = profile_case.update(population_scope=True)
    data = case.request.model_dump()
    cohort = case.request.state.candidate.snapshot.cohort.model_dump()
    cohort[field] = "changed-cohort-evidence"
    data["update"]["update"]["cohort"] = cohort
    with pytest.raises(ValueError, match="separate initial rebuild"):
        type(case.request).model_validate(data)
    data = case.response.model_dump()
    for snapshot in (data["result"]["state"]["candidate"]["snapshot"], data["result"]["state"]["published"]):
        snapshot["cohort"][field] = "changed-cohort-evidence"
    response = type(case.response).model_validate(data)
    with pytest.raises(ValueError, match="preserve cohort membership"):
        response.validate_for_request(case.request)
