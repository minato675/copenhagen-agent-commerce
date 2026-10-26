"""S15P21A207-303: calculation retries are distinct from durable deduplication."""

import pytest

from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateResponse


@pytest.mark.parametrize("population_scope", [False, True])
def test_retry_is_deterministic_but_only_replay_against_new_state_is_unchanged(update_case, run_async, population_scope):
    case = update_case(population_scope=population_scope, rows=())
    first = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).result
    retry = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).result
    assert first == retry and first.status == "UPDATED"  # No durable deduplication is claimed.
    next_case = update_case(state=first.state, rows=(), cutoff=case.request.update.update.as_of)
    unchanged = ProviderProfileUpdateResponse.model_validate_json(run_async(next_case.execute())).result
    assert unchanged.status == "UNCHANGED" and unchanged.state == first.state
    assert not unchanged.transitions and not unchanged.candidate_events
