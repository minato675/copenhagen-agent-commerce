from datetime import timedelta

import pytest

from copenhagen_fds.profile_update_records import sample_at_cutoff
from copenhagen_fds.profile_update_replay import compare_profile_replay, derive_replay_events, replay_profile_update
from copenhagen_fds.profile_updates import update_profile


@pytest.mark.parametrize("population_scope", [False, True])
def test_complete_diff_covers_confirmation_correction_removal_and_derived_features(updates, population_scope):
    pending = updates.sample("pending", amount=900, status="DEFER")
    removed = updates.sample("removed")
    affected = updates.sample("affected", counts=2)
    corrected = updates.sample("corrected", amount=300)
    base = updates.state(pending, removed, affected, corrected, population_scope=population_scope)
    later = updates.cutoff + timedelta(days=1)
    final = tuple(sample_at_cutoff(s, later) for s in (
        updates.sample("pending", amount=900), updates.sample("affected", counts=1),
        updates.sample("corrected", amount=300, status="EXCLUDE"), updates.sample("new", amount=500),
    ))
    request = updates.request(base, as_of=later)
    events = derive_replay_events(base, request, final)
    assert len(events) == 5
    assert {e.reason for e in events} == {"REPLAY_DIFF"}
    assert [e.target.purchase_request_id for e in events if e.operation == "REMOVE"] == ["removed"]
    result, comparison = replay_profile_update(base, request, reversed(final))
    assert result.status == "UPDATED" and comparison["matched"]
    assert comparison["recent_equal"] and comparison["long_term_equal"] and comparison["retained_samples_equal"]
    assert result.state.snapshot.long_term.included_count == 3
    assert updates.feature(result.state.snapshot, "purchase_count_10m").histogram[0].value == 0
    assert derive_replay_events(result.state, updates.request(result.state), final) == ()


def test_missing_derived_correction_is_detected_by_independent_full_replay(updates):
    original = updates.sample("p", counts=3)
    base = updates.state(original)
    changed = updates.sample("p", counts=2)
    # A caller incorrectly declaring an empty complete change stream cannot be detected
    # without the authoritative final observations; the independent comparison catches it.
    result = update_profile(base, updates.request(base))
    comparison = compare_profile_replay(result.state, updates.request(base), (changed,))
    assert comparison["matched"] is False
    assert comparison["long_term_equal"] is False
    assert comparison["retained_samples_equal"] is False


def test_complete_replay_does_not_remove_naturally_expired_purchases(updates):
    sample = updates.sample("old", at=updates.cutoff - timedelta(days=90))
    base = updates.state(sample)
    request = updates.request(base, as_of=updates.cutoff + timedelta(seconds=1))
    assert not derive_replay_events(base, request, ())
    result, comparison = replay_profile_update(base, request, ())
    assert result.state.snapshot.long_term.retained_count == 0 and comparison["matched"]


@pytest.mark.parametrize("kind", ["cutoff", "source", "duplicate", "current", "mixed_events"])
def test_replay_rejects_invalid_or_ambiguous_authoritative_inputs(updates, kind):
    sample = updates.sample("p")
    base = updates.state()
    request, samples = updates.request(base), (sample,)
    if kind == "cutoff":
        request = updates.request(base, as_of=updates.cutoff + timedelta(days=1))
    elif kind == "source":
        data = sample.model_dump(mode="python")
        data["source_reference"] = "different"
        samples = (type(sample).model_validate(data),)
    elif kind == "duplicate":
        samples = (sample, updates.sample("p", amount=200))
    elif kind == "current":
        request = updates.request(base, current_purchase_request_id="p")
    else:
        request = updates.request(base, updates.event(sample))
    with pytest.raises(ValueError):
        replay_profile_update(base, request, samples)


def test_replay_applies_same_scope_only_and_is_order_independent(updates):
    base = updates.state()
    final = (updates.sample("a"), updates.sample("b", user="another"), updates.sample("usd", currency="USD"))
    request = updates.request(base)
    one, check = replay_profile_update(base, request, final)
    two, _ = replay_profile_update(base, request, tuple(reversed(final)) + final)
    assert one == two and check["matched"]
    assert one.state.snapshot.long_term.retained_count == 1


def test_replay_preserves_guard_failure(updates):
    base = updates.state()
    result, comparison = replay_profile_update(base, updates.request(base, expected_state_version="old"), ())
    assert result.issue == "VERSION_MISMATCH" and comparison is None
