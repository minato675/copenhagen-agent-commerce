"""S15P21A207-303: policy updates preserve held and admitted publication evidence."""

from datetime import timedelta

from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateResponse
from copenhagen_fds.profile_policy_updates import initialize_profile_policy


def test_abrupt_change_is_held_then_admitted_only_with_later_support(update_case, policy, run_async):
    baseline = policy.baseline()
    state = initialize_profile_policy(policy.snapshot(baseline), baseline, policy.config())
    rows = (*baseline, policy.row("new-1", amount=1000, at=policy.cutoff - timedelta(days=3)))
    held_case = update_case(state=state, rows=rows, cutoff=policy.cutoff)
    held = ProviderProfileUpdateResponse.model_validate_json(run_async(held_case.execute())).result
    assert held.status == "UPDATED"  # A state update is not blanket learning admission.
    assert [(t.action, t.reason) for t in held.transitions] == [("HELD", "ABRUPT_CHANGE")]
    assert held.state.published.long_term.included_count == 3
    rows = (*rows, policy.row("new-2", amount=1000, at=policy.cutoff - timedelta(days=2)), policy.row("new-3", amount=1000))
    supported_case = update_case(state=held.state, rows=rows, cutoff=policy.cutoff)
    supported = ProviderProfileUpdateResponse.model_validate_json(run_async(supported_case.execute())).result
    assert supported.state.published.long_term.included_count == 6
    assert len(supported.transitions) == 3 and all(t.action == "ADMITTED" for t in supported.transitions)


def test_authorized_policy_change_rebuilds_publication_without_changing_candidate(update_case, policy, run_async):
    rows = tuple(policy.row(str(i)) for i in range(5))
    state = initialize_profile_policy(policy.snapshot(rows), rows, policy.config())

    def configure(data):
        data["config"] = policy.config(version="limit-v2", max_admissions_per_user_day=1)

    case = update_case(state=state, cutoff=policy.cutoff, configure=configure)
    result = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).result
    assert result.status == "UPDATED" and result.state.candidate == state.candidate
    assert result.state.published.long_term.included_count == 2
    assert result.state.evaluation.config.version == "limit-v2"
    assert result.state.published.profile.reference.version != state.published.profile.reference.version
