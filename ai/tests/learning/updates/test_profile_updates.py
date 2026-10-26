from datetime import timedelta

import pytest

from copenhagen_fds.contracts.profile_updates import ProfileUpdateResult, ProfileUpdateState
from copenhagen_fds.profile_update_records import sample_at_cutoff
from copenhagen_fds.profile_updates import update_profile


@pytest.mark.parametrize("population_scope", [False, True])
def test_empty_update_and_redelivery_are_idempotent(updates, population_scope):
    base = updates.state(population_scope=population_scope)
    assert update_profile(base, updates.request(base)).status == "UNCHANGED"
    event = updates.event(updates.sample("p"))
    result = update_profile(base, updates.request(base, event, event))
    assert result.status == "UPDATED"
    assert result.state.snapshot.long_term.included_count == 1
    retry = update_profile(result.state, updates.request(result.state, event))
    assert retry.status == "UNCHANGED"
    assert retry.events[0].outcome == "DUPLICATE"
    assert retry.state == result.state
    assert ProfileUpdateResult.model_validate_json(result.model_dump_json()) == result


def test_receipt_only_change_versions_the_log_without_changing_profile(updates):
    event = updates.event(updates.sample("p"))
    base = updates.state()
    first = update_profile(base, updates.request(base, event)).state
    alias = updates.event(event.sample, event_id="same-revision-different-delivery")
    result = update_profile(first, updates.request(first, alias))
    assert result.status == "UPDATED" and result.events[0].outcome == "DUPLICATE"
    assert result.state.snapshot == first.snapshot
    assert result.state.state_version != first.state_version


@pytest.mark.parametrize("field", ["expected_state_version", "expected_profile_reference"])
def test_expected_version_mismatch_rejects_without_mutation(updates, field):
    base = updates.state(updates.sample("p"))
    changes = {field: "stale" if field == "expected_state_version" else {
        **base.snapshot.profile.reference.model_dump(mode="python"), "version": "stale",
    }}
    before = base.model_dump_json()
    result = update_profile(base, updates.request(base, **changes))
    assert result.status == "REJECTED" and result.issue == "VERSION_MISMATCH"
    assert result.state is None and not result.events
    assert base.model_dump_json() == before


@pytest.mark.parametrize("kind,issue", [
    ("time", "TIME_REGRESSION"), ("scope", "SCOPE_MISMATCH"),
    ("gap_start", "CHANGE_HISTORY_GAP"), ("gap_end", "CHANGE_HISTORY_GAP"),
    ("retention", "RETENTION_GAP"), ("current", "CURRENT_PURCHASE_IN_STATE"),
])
def test_invalid_context_returns_actionable_issue(updates, kind, issue):
    base = updates.state(updates.sample("p"))
    next_day = updates.cutoff + timedelta(days=1)
    changes = {
        "time": dict(as_of=updates.cutoff - timedelta(seconds=1), changes_complete_from=updates.cutoff - timedelta(seconds=1)),
        "scope": dict(population_config={}),
        "gap_start": dict(as_of=next_day, changes_complete_from=updates.cutoff + timedelta(seconds=1)),
        "gap_end": dict(as_of=next_day, changes_complete_until=next_day - timedelta(seconds=1)),
        "retention": dict(personal_config={"long_term_days": 91}),
        "current": dict(current_purchase_request_id="p"),
    }[kind]
    result = update_profile(base, updates.request(base, **changes))
    assert result.issue == issue
    assert result.status == ("REJECTED" if kind in {"time", "scope"} else "REPLAY_REQUIRED")
    assert result.state is None


def test_population_membership_change_requires_replay(updates):
    base = updates.state(updates.sample("p"), population_scope=True)
    cohort = base.snapshot.cohort.model_dump(mode="python")
    cohort["member_user_ids"] = ("a", "new")
    result = update_profile(base, updates.request(base, cohort=cohort))
    assert result.status == "REPLAY_REQUIRED" and result.issue == "COHORT_CHANGED"


def test_population_membership_order_does_not_change_content(updates):
    base = updates.state(population_scope=True)
    cohort = base.snapshot.cohort.model_dump(mode="python")
    cohort["member_user_ids"] = tuple(reversed(cohort["member_user_ids"]))
    assert update_profile(base, updates.request(base, cohort=cohort)).status == "UNCHANGED"


def test_normal_confirmation_and_fraud_correction_rebuild_exact_quantiles(updates):
    pending = updates.sample("p", amount=900, status="DEFER")
    low, middle = updates.sample("a", amount=100), updates.sample("b", amount=300)
    base = updates.state(low, middle, pending)
    normal = updates.sample("p", amount=900)
    confirmed = update_profile(base, updates.request(base, updates.event(normal, before=pending)))
    stats = updates.feature(confirmed.state.snapshot, "amount_minor").numeric
    assert (stats.mean, stats.median, stats.q25, stats.q75) == (1300 / 3, 300, 200, 600)
    fraud = updates.sample("p", amount=900, status="EXCLUDE")
    corrected = update_profile(confirmed.state, updates.request(confirmed.state, updates.event(fraud, before=normal, revision=2)))
    corrected_stats = updates.feature(corrected.state.snapshot, "amount_minor").numeric
    assert (corrected_stats.mean, corrected_stats.median, corrected_stats.q25, corrected_stats.q75) == (200, 200, 150, 250)
    assert corrected.state.snapshot.long_term.excluded_count == 1
    assert base.snapshot.long_term.deferred_count == 1
    assert corrected.previous_profile_reference == confirmed.state.snapshot.profile.reference
    assert corrected.events[0].reason == "CORRECTION"


def test_json_restored_state_can_correct_exact_order_statistics(updates):
    normal = updates.sample("p", amount=900)
    base = updates.state(updates.sample("q", amount=100), normal)
    restored = ProfileUpdateState.model_validate_json(base.model_dump_json())
    event = updates.event(updates.sample("p", amount=200), before=normal)
    left = update_profile(base, updates.request(base, event))
    right = update_profile(restored, updates.request(restored, event))
    assert left == right
    assert updates.feature(right.state.snapshot, "amount_minor").numeric.median == 150


def test_recent_window_expires_without_new_events_and_larger_window_uses_retained_data(updates):
    old = updates.sample("p", at=updates.cutoff - timedelta(days=7))
    base = updates.state(old)
    assert base.snapshot.recent.included_count == 1
    result = update_profile(base, updates.request(base, as_of=updates.cutoff + timedelta(seconds=1)))
    assert result.state.snapshot.recent.included_count == 0
    assert result.state.snapshot.long_term.included_count == 1
    # A one-day extension is available after advancing two days: no lost observations are needed.
    extended = update_profile(base, updates.request(base, as_of=updates.cutoff + timedelta(days=2),
                                                   personal_config={"long_term_days": 91}))
    assert extended.status == "UPDATED"


def test_old_samples_and_receipts_expire_together(updates):
    base = updates.state()
    applied = update_profile(base, updates.request(base, updates.event(updates.sample("p")))).state
    result = update_profile(applied, updates.request(applied, as_of=updates.cutoff + timedelta(days=91)))
    assert result.state.snapshot.long_term.retained_count == 0
    assert not result.state.heads and not result.state.receipts


def test_delayed_confirmation_inside_retention_uses_its_original_purchase_time(updates):
    old = updates.sample("p", at=updates.cutoff - timedelta(days=60), status="DEFER")
    base = updates.state(old)
    next_day = updates.cutoff + timedelta(days=1)
    confirmed = sample_at_cutoff(updates.sample("p", at=old.purchase_occurred_at), next_day)
    result = update_profile(base, updates.request(base, updates.event(confirmed, before=old), as_of=next_day))
    assert result.state.snapshot.long_term.included_count == 1
    assert result.state.snapshot.recent.included_count == 0


def test_revision_gap_and_conflict_are_atomic_even_after_valid_events(updates):
    base = updates.state()
    valid = updates.event(updates.sample("a"))
    gap = updates.event(updates.sample("z"), revision=2)
    result = update_profile(base, updates.request(base, valid, gap))
    assert result.status == "REPLAY_REQUIRED" and result.issue == "MISSING_REVISION"
    assert result.state is None and not result.events
    assert base.snapshot.long_term.retained_count == 0


def test_older_revision_never_overwrites_latest_and_reused_id_conflicts(updates):
    base = updates.state()
    normal, fraud = updates.sample("p"), updates.sample("p", status="EXCLUDE")
    first = updates.event(normal)
    second = updates.event(fraud, before=normal, revision=2)
    applied = update_profile(base, updates.request(base, first, second)).state
    stale = updates.event(normal, event_id="redelivered-old")
    result = update_profile(applied, updates.request(applied, stale))
    assert result.events[0].outcome == "STALE"
    assert result.state.snapshot.long_term.excluded_count == 1
    conflict = updates.event(fraud, event_id=first.event_id)
    rejected = update_profile(applied, updates.request(applied, conflict))
    assert rejected.issue == "EVENT_ID_CONFLICT" and rejected.status == "REJECTED"


def test_remove_then_reinsert_requires_the_tombstone_revision(updates):
    sample = updates.sample("p")
    base = updates.state(sample)
    remove = updates.event(sample, before=sample, operation="REMOVE", sample=None, reason="REMOVAL")
    removed = update_profile(base, updates.request(base, remove)).state
    assert removed.snapshot.long_term.included_count == 0
    inserted = update_profile(removed, updates.request(removed, updates.event(sample, revision=2)))
    assert inserted.state.snapshot.long_term.included_count == 1
    assert inserted.state.heads[0].revision == 2


def test_population_user_equal_weights_rebalance_after_removal(updates):
    one, two, other = updates.sample("p", amount=100), updates.sample("q", amount=300), updates.sample("r", user="b", amount=900)
    base = updates.state(one, two, other, population_scope=True)
    assert updates.feature(base.snapshot, "amount_minor").numeric.mean == 550
    remove = updates.event(two, before=two, operation="REMOVE", sample=None)
    result = update_profile(base, updates.request(base, remove))
    assert updates.feature(result.state.snapshot, "amount_minor").numeric.mean == 500
    assert result.state.snapshot.long_term.members[0].included_count == 1
