"""S15P21A207-303: DTO conflicts versus native rejection/rebuild requirements."""

import json
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateResponse
from copenhagen_fds.provider_runtime.access import ResourceScope


@pytest.mark.parametrize("mutation", ["policy_version", "candidate_version", "profile_version", "missing_mode",
    "delta", "missing_removal", "partial_range", "change_gap", "duplicate", "future_review", "current_purchase"])
def test_conflicting_or_incomplete_request_stops_before_replay(update_case, run_async, monkeypatch, mutation):
    case = update_case()
    before = case.request.state.model_dump_json()
    data = case.request.model_dump(mode="json")
    outer, update, replay = data["update"], data["update"]["update"], data["replay"]
    if mutation == "policy_version":
        outer["expected_policy_state_version"] = "stale-policy"
    elif mutation == "candidate_version":
        update["expected_state_version"] = "stale-candidate"
    elif mutation == "profile_version":
        update["expected_profile_reference"]["version"] = "stale-profile"
    elif mutation == "missing_mode":
        del replay["mode"]
    elif mutation == "delta":
        replay["mode"] = "DELTA"
    elif mutation == "missing_removal":
        del replay["omitted_purchase"]
    elif mutation == "partial_range":
        replay["complete_from"] = (case.request.replay.complete_from + timedelta(days=1)).isoformat()
    elif mutation == "change_gap":
        update["changes_complete_from"] = update["as_of"]
    elif mutation == "duplicate":
        replay["observations"].append(replay["observations"][0])
    elif mutation == "future_review":
        replay["observations"][0]["feedback"]["available_at"] = update["valid_until"]
    else:
        update["current_purchase_request_id"] = "purchase-a"
    case.bound.raw = json.dumps(data).encode()
    monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_update.update_profile_policy",
                        lambda *_: pytest.fail("invalid request must not execute replay"))
    response = ProviderError.model_validate_json(run_async(case.execute()))
    assert response.code == "INVALID_CONTRACT"
    assert case.request.state.model_dump_json() == before


@pytest.mark.parametrize("population_scope", [False, True])
def test_extended_retention_returns_native_replay_required_without_partial_state(update_case, run_async, population_scope):
    def configure(data):
        data["update"]["population_config" if population_scope else "personal_config"] = {"long_term_days": 91}

    case = update_case(population_scope=population_scope, configure=configure)
    before = case.request.state.model_dump_json()
    result = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request).result
    assert (result.status, result.issue) == ("REPLAY_REQUIRED", "RETENTION_GAP")
    assert result.state is None and not result.transitions and not result.candidate_events
    assert case.request.state.model_dump_json() == before


def test_regressed_generation_returns_native_rejected_result(update_case, profile_case, run_async):
    state = profile_case.create().state

    def configure(data):
        data["update"]["generated_at"] = state.candidate.snapshot.profile.as_of

    case = update_case(state=state, cutoff=state.candidate.snapshot.profile.as_of, configure=configure)
    result = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).result
    assert (result.status, result.issue) == ("REJECTED", "TIME_REGRESSION")
    assert result.state is None and not result.transitions and not result.candidate_events


def test_current_purchase_already_in_previous_state_requires_rebuild(update_case, run_async):
    def configure(data):
        data["update"]["current_purchase_request_id"] = "purchase-a"

    case = update_case(rows=(), configure=configure)
    result = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).result
    assert (result.status, result.issue) == ("REPLAY_REQUIRED", "CURRENT_PURCHASE_IN_STATE")
    assert result.state is None and not result.candidate_events


def test_removed_member_still_requires_access_to_previous_state(update_case, run_async, monkeypatch):
    scope = ResourceScope(user_ids={"a", "empty"}, profile_ids={"population-cohort", "population-cohort-admitted"},
                          cohort_ids={"cohort"})
    case = update_case(population_scope=True, rows=(), scope=scope)
    monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_update.update_profile_policy",
                        lambda *_: pytest.fail("omitted rows cannot bypass previous-state authorization"))
    assert ProviderError.model_validate_json(run_async(case.execute())).code == "FORBIDDEN"
