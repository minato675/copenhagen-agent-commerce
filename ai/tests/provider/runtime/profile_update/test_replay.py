"""S15P21A207-303: delayed reviews, corrections, omission and policy admission replay."""

from datetime import timedelta

import pytest

from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateResponse
from copenhagen_fds.profile_policy_updates import initialize_profile_policy
from copenhagen_fds.profile_updates import profile_content


@pytest.mark.parametrize("population_scope", [False, True])
def test_delayed_normal_then_fraud_withdrawal_matches_fresh_profile(update_case, policy, run_async, population_scope):
    original = policy.row("delayed", status="DEFER")
    state = initialize_profile_policy(policy.snapshot((original,), population_scope=population_scope), (original,), policy.config())
    for step, classification, action, included in ((1, "INCLUDE", "ADMITTED", 1), (2, "EXCLUDE", "WITHDRAWN", 0)):
        before = state.model_dump_json()
        cutoff = policy.cutoff + timedelta(hours=step)
        row = policy.row("delayed", status=classification, at=original.sample.purchase_occurred_at,
                         cutoff=cutoff, review_at=cutoff, review_id=f"review-{step}")
        case = update_case(state=state, rows=(row,), cutoff=cutoff)
        response = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
        result = response.result
        assert result.status == "UPDATED" and result.transitions[0].action == action
        assert result.state.published.long_term.included_count == included
        fresh = initialize_profile_policy(policy.snapshot((row,), population_scope=population_scope, cutoff=cutoff), (row,), policy.config())
        assert profile_content(result.state.published) == profile_content(fresh.published)
        assert result.state.evaluation == fresh.evaluation and state.model_dump_json() == before
        state = result.state


@pytest.mark.parametrize("population_scope", [False, True])
def test_correction_and_full_empty_replay_preserve_versions_and_remove_source(update_case, profile_case, policy, run_async, population_scope):
    state = profile_case.create(population_scope=population_scope).state
    cutoff = state.candidate.snapshot.profile.as_of
    corrected = policy.row("purchase-a", amount=200, review_id="corrected-review")
    rows = (corrected, state.observations[1])
    case = update_case(state=state, rows=rows, cutoff=cutoff)
    result = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).result
    transition = next(t for t in result.transitions if t.purchase_request_id == "purchase-a")
    assert transition.action == "REPLACED" and transition.next_status == "INCLUDE"
    assert result.state.state_version != state.state_version
    assert result.state.published.profile.reference.version != state.published.profile.reference.version
    before = result.state.model_dump_json()
    removed = update_case(state=result.state, rows=(), cutoff=cutoff)
    deleted = ProviderProfileUpdateResponse.model_validate_json(run_async(removed.execute())).result
    assert deleted.status == "UPDATED" and deleted.state.published.long_term.status == "EMPTY"
    assert all(t.action == "WITHDRAWN" and t.reason == "OBSERVATION_REMOVED" for t in deleted.transitions)
    assert {t.purchase_request_id for t in deleted.transitions} == {"purchase-a", "purchase-b"}
    assert result.state.model_dump_json() == before
