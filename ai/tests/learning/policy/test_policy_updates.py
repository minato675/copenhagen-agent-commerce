from datetime import timedelta

import pytest

from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState, ProfilePolicyUpdateRequest
from copenhagen_fds.profile_policy_updates import initialize_profile_policy, update_profile_policy
from copenhagen_fds.profile_updates import profile_content


def request(state, *, cutoff=None, **changes):
    cutoff = cutoff or state.candidate.snapshot.profile.as_of
    data = dict(expected_policy_state_version=state.state_version, update=dict(
        expected_state_version=state.candidate.state_version,
        expected_profile_reference=state.candidate.snapshot.profile.reference,
        as_of=cutoff, generated_at=cutoff + timedelta(seconds=1), valid_until=cutoff + timedelta(days=1),
        changes_complete_from=state.candidate.snapshot.profile.as_of, changes_complete_until=cutoff))
    data.update(changes)
    return ProfilePolicyUpdateRequest.model_validate(data)


def state_for(policy, rows, **context):
    return initialize_profile_policy(policy.snapshot(rows, **context), rows, policy.config())


def test_candidate_and_published_profiles_keep_different_admission_meanings(policy):
    rows = (*policy.baseline(), policy.row("large", amount=10000))
    state = state_for(policy, rows)
    assert state.candidate.snapshot.long_term.included_count == 4
    assert state.published.long_term.included_count == 3
    assert state.observations[-1].sample.eligibility.status == "INCLUDE"
    assert state.published.profile.reference.profile_id != state.candidate.snapshot.profile.reference.profile_id
    assert state.evaluation.decisions[-1].reason == "ABRUPT_CHANGE"


@pytest.mark.parametrize("population_scope", [False, True])
def test_delayed_normal_and_fraud_withdrawal_equal_independent_full_replay(policy, population_scope):
    original = policy.row("p", status="DEFER")
    base = state_for(policy, (original,), population_scope=population_scope)
    original_json = base.model_dump_json()
    later = policy.cutoff + timedelta(hours=1)
    normal = policy.row("p", at=original.sample.purchase_occurred_at, cutoff=later, review_at=later, review_id="normal-p")
    result = update_profile_policy(base, request(base, cutoff=later), (normal,))
    assert result.state.published.long_term.included_count == 1
    assert result.transitions[0].action == "ADMITTED"
    fresh = state_for(policy, (normal,), population_scope=population_scope, cutoff=later)
    assert profile_content(result.state.published) == profile_content(fresh.published)
    assert result.state.evaluation == fresh.evaluation
    assert base.model_dump_json() == original_json
    fraud_cutoff = later + timedelta(hours=1)
    fraud = policy.row("p", status="EXCLUDE", at=original.sample.purchase_occurred_at, cutoff=fraud_cutoff,
                       review_at=fraud_cutoff, review_id="fraud-p")
    corrected = update_profile_policy(result.state, request(result.state, cutoff=fraud_cutoff), (fraud,))
    assert corrected.state.published.long_term.included_count == 0
    assert corrected.transitions[0].action == "WITHDRAWN"
    assert corrected.transitions[0].reason == "RAW_FRAUD_CONFIRMED"
    full = state_for(policy, (fraud,), population_scope=population_scope, cutoff=fraud_cutoff)
    assert profile_content(corrected.state.published) == profile_content(full.published)
    assert corrected.state.evaluation == full.evaluation


def test_fraud_withdrawal_does_not_wait_for_a_daily_quota_slot(policy):
    rows = (policy.row("p"), policy.row("q"))
    base = initialize_profile_policy(policy.snapshot(rows), rows, policy.config(max_admissions_per_user_day=1))
    assert base.published.long_term.included_count == 2
    fraud = policy.row("p", status="EXCLUDE", at=rows[0].sample.purchase_occurred_at, review_at=policy.cutoff)
    result = update_profile_policy(base, request(base), (fraud, rows[1]))
    assert result.state.published.long_term.included_count == 1
    assert next(t for t in result.transitions if t.purchase_request_id == "p").action == "WITHDRAWN"


def test_state_json_round_trip_and_redelivery_preserve_versions(policy):
    rows = (*policy.baseline(), policy.row("large", amount=10000))
    base = state_for(policy, rows)
    restored = ProfilePolicyState.model_validate_json(base.model_dump_json())
    assert restored == base
    result = update_profile_policy(restored, request(restored), tuple(reversed(rows)) + rows)
    assert result.status == "UNCHANGED" and result.state == base and not result.transitions


@pytest.mark.parametrize("which,issue", [("policy", "POLICY_VERSION_MISMATCH"), ("candidate", "VERSION_MISMATCH"),
                                         ("retention", "RETENTION_GAP")])
def test_policy_update_rejects_stale_versions_and_identifies_replay_gap(policy, which, issue):
    rows = (policy.row("p"),)
    base = state_for(policy, rows)
    data = request(base).model_dump(mode="python")
    if which == "policy":
        data["expected_policy_state_version"] = "stale"
    elif which == "candidate":
        data["update"]["expected_state_version"] = "stale"
    else:
        data["update"]["personal_config"] = {"long_term_days": 91}
    result = update_profile_policy(base, ProfilePolicyUpdateRequest.model_validate(data), rows)
    assert result.issue == issue and result.state is None
    assert not result.transitions and not result.candidate_events


def test_policy_configuration_change_recomputes_and_versions_the_published_profile(policy):
    rows = tuple(policy.row(str(i)) for i in range(5))
    base = state_for(policy, rows)
    result = update_profile_policy(base, request(base, config=policy.config(version="limit-v2", max_admissions_per_user_day=1)), rows)
    assert result.state.published.long_term.included_count == 2
    assert result.state.evaluation.config.version == "limit-v2"
    assert result.state.evaluation.config_sha256 != base.evaluation.config_sha256
    assert result.state.published.profile.reference.version != base.published.profile.reference.version


def test_removal_rebuilds_published_statistics_and_records_withdrawal(policy):
    base = state_for(policy, (policy.row("p"),))
    result = update_profile_policy(base, request(base), ())
    assert result.state.published.long_term.retained_count == 0
    assert result.transitions[0].action == "WITHDRAWN" and result.transitions[0].reason == "OBSERVATION_REMOVED"


def test_confirmed_change_can_be_released_by_later_available_support(policy):
    before = (*policy.baseline(), policy.row("new-1", amount=1000, at=policy.cutoff - timedelta(days=3)))
    base = state_for(policy, before)
    assert base.published.long_term.included_count == 3
    final = (*before, policy.row("new-2", amount=1000, at=policy.cutoff - timedelta(days=2)), policy.row("new-3", amount=1000))
    result = update_profile_policy(base, request(base), final)
    full = state_for(policy, final)
    assert result.state.published.long_term.included_count == 6
    assert profile_content(result.state.published) == profile_content(full.published)
    assert sum(t.action == "ADMITTED" for t in result.transitions) == 3


def test_json_cannot_omit_policy_evidence_or_admitted_samples(policy):
    rows = (policy.row("p"),)
    base = state_for(policy, rows)
    data = base.model_dump(mode="python")
    data["observations"] = ()
    with pytest.raises(ValueError):
        ProfilePolicyState.model_validate(data)
