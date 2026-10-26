"""S15P21A207-299: scope, retention, full replay and non-persisted profile results."""

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest, ProviderProfileUpdateRequest


@pytest.mark.parametrize("population_scope,empty", [(False, False), (False, True), (True, False), (True, True)])
def test_personal_and_population_creation_roundtrip(profile_case, population_scope, empty):
    case = profile_case.create(population_scope=population_scope, empty=empty)
    request = type(case.request).model_validate_json(case.request.model_dump_json())
    response = type(case.response).model_validate_json(case.response.model_dump_json())
    assert response.validate_for_request(request) == case.response
    assert response.storage_status == "NOT_PERSISTED" and response.status == "CREATED"
    if empty:
        assert response.state.published.long_term.status == "EMPTY"
        assert response.state.published.long_term.included_count == 0


@pytest.mark.parametrize("population_scope", [False, True])
def test_update_roundtrip_and_versions(profile_case, population_scope):
    case = profile_case.update(population_scope=population_scope)
    request = type(case.request).model_validate_json(case.request.model_dump_json())
    response = type(case.response).model_validate_json(case.response.model_dump_json())
    assert response.validate_for_request(request) == case.response
    assert response.result.status == "UPDATED"
    assert response.result.previous_state_version != response.result.state.state_version
    assert response.storage_status == "NOT_PERSISTED"


def test_empty_complete_replay_removes_previous_observations(profile_case):
    case = profile_case.update(remove=True)
    assert case.request.state.observations  # Previous immutable snapshot is untouched.
    assert not case.response.result.state.observations
    assert {t.purchase_request_id for t in case.response.result.transitions} == {"purchase-a", "purchase-b"}
    assert all(t.reason == "OBSERVATION_REMOVED" for t in case.response.result.transitions)


@pytest.mark.parametrize("mutation", ["delta", "missing_mode", "missing_removal", "partial_range", "other_user",
    "duplicate", "future_feedback", "other_source", "wrong_scope", "config_hash", "missing_observations"])
def test_creation_rejects_ambiguous_or_wrong_observations(profile_case, mutation):
    data = profile_case.create().request.model_dump(mode="json")
    replay = data["replay"]
    if mutation == "delta":
        replay["mode"] = "DELTA"
    elif mutation == "missing_mode":
        del replay["mode"]
    elif mutation == "missing_removal":
        del replay["omitted_purchase"]
    elif mutation == "partial_range":
        replay["complete_from"] = "2026-04-29T00:00:00+09:00"
    elif mutation == "other_user":
        data["profile"]["user_id"] = data["profile"]["reference"]["user_id"] = "other-user"
    elif mutation == "duplicate":
        replay["observations"].append(replay["observations"][0])
    elif mutation == "future_feedback":
        replay["observations"][0]["feedback"]["available_at"] = "2030-01-01T00:00:00+09:00"
    elif mutation == "other_source":
        data["profile"]["source_reference"] = "wrong-source"
    elif mutation == "wrong_scope":
        data["operation"] = "CREATE_POPULATION_PROFILE"
    elif mutation == "config_hash":
        data["policy"]["max_admissions_per_user_day"] += 1
    else:
        del replay["observations"]
    with pytest.raises(ValidationError):
        ProviderProfileCreateRequest.model_validate(data)


@pytest.mark.parametrize("mutation", ["policy_version", "candidate_version", "profile_reference", "time_regression",
                                      "gap", "current_purchase", "mixed_events"])
def test_update_rejects_broken_links(profile_case, mutation):
    data = profile_case.update().request.model_dump(mode="json")
    outer, update = data["update"], data["update"]["update"]
    if mutation == "policy_version":
        outer["expected_policy_state_version"] = "stale"
    elif mutation == "candidate_version":
        update["expected_state_version"] = "stale"
    elif mutation == "profile_reference":
        update["expected_profile_reference"]["version"] = "stale"
    elif mutation == "time_regression":
        update["as_of"] = "2020-01-01T00:00:00+09:00"
    elif mutation == "gap":
        update["changes_complete_from"] = update["as_of"]
    elif mutation == "current_purchase":
        update["current_purchase_request_id"] = "purchase-a"
    else:
        row = data["replay"]["observations"][0]["sample"]
        update["events"] = [dict(event_id="delta", target=row["eligibility"]["target"], revision=1,
            previous_content_sha256=None, available_at=update["as_of"], operation="UPSERT", reason="NEW_PURCHASE", sample=row)]
    with pytest.raises(ValidationError):
        ProviderProfileUpdateRequest.model_validate(data)


@pytest.mark.parametrize("create", [True, False])
def test_response_cannot_bind_a_different_request(profile_case, create):
    case = profile_case.create() if create else profile_case.update()
    data = case.response.model_dump()
    data["request_sha256"] = "f" * 64
    with pytest.raises(ValueError):
        type(case.response).model_validate(data).validate_for_request(case.request)


def test_rejected_update_has_reason_but_no_partial_state(profile_case):
    case = profile_case.update()
    data = case.response.model_dump()
    data["result"].update(status="REJECTED", issue="VERSION_MISMATCH", state=None, candidate_events=(), transitions=())
    response = type(case.response).model_validate(data).validate_for_request(case.request)
    assert response.result.state is None and response.result.issue == "VERSION_MISMATCH"
    data["result"]["state"] = case.response.result.state
    with pytest.raises(ValidationError):
        type(case.response).model_validate(data)
