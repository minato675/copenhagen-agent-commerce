from datetime import timedelta, timezone

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.profile_updates import ProfileUpdateEvent, ProfileUpdateState
from copenhagen_fds.profile_revision_log import RevisionFailure, reduce_profile_events
from copenhagen_fds.profile_update_records import sample_at_cutoff, sample_content_hash


def test_sample_hash_ignores_cutoff_but_preserves_changed_evidence(updates):
    sample = updates.sample("p")
    later = sample_at_cutoff(sample, updates.cutoff + timedelta(days=1))
    assert sample_content_hash(sample) == sample_content_hash(later)
    assert sample_content_hash(sample) != sample_content_hash(updates.sample("p", status="EXCLUDE"))
    with pytest.raises(ValueError):
        sample_at_cutoff(sample, updates.cutoff - timedelta(seconds=1))


def test_sample_hash_canonicalizes_timezone_and_feature_order(updates):
    original = updates.sample("p")
    data = original.model_dump(mode="python")
    data["raw_features"]["features"] = tuple(reversed(data["raw_features"]["features"]))
    data["purchase_occurred_at"] = original.purchase_occurred_at.astimezone(timezone.utc)
    assert sample_content_hash(type(original).model_validate(data)) == sample_content_hash(original)


@pytest.mark.parametrize("changes", [
    {"revision": 0}, {"revision": True}, {"operation": "REMOVE"},
    {"sample": None}, {"previous_content_sha256": "bad"}, {"unexpected": 1},
])
def test_event_rejects_invalid_contract(updates, changes):
    data = updates.event(updates.sample("p")).model_dump(mode="python")
    data.update(changes)
    with pytest.raises(ValidationError):
        ProfileUpdateEvent.model_validate(data)


def test_event_rejects_future_sample_and_different_target(updates):
    sample = updates.sample("p")
    with pytest.raises(ValidationError):
        updates.event(sample, available_at=updates.cutoff - timedelta(seconds=1))
    with pytest.raises(ValidationError):
        updates.event(sample, target=updates.sample("other").eligibility.target)


def test_bootstrap_json_retains_included_deferred_excluded_and_versions(updates):
    state = updates.state(*(updates.sample(str(i), status=status) for i, status in enumerate(("INCLUDE", "DEFER", "EXCLUDE"))))
    assert ProfileUpdateState.model_validate_json(state.model_dump_json()) == state
    assert {h.revision for h in state.heads} == {0}
    assert len(state.heads) == 3
    data = state.model_dump(mode="python")
    data["heads"] = data["heads"][:-1]
    with pytest.raises(ValidationError):
        ProfileUpdateState.model_validate(data)


def test_reordered_revisions_apply_once_and_do_not_mutate_input(updates):
    base_sample = updates.sample("p", status="DEFER")
    normal, fraud = updates.sample("p"), updates.sample("p", status="EXCLUDE")
    base = updates.state(base_sample)
    first = updates.event(normal, before=base_sample)
    second = updates.event(fraud, before=normal, revision=2)
    original = base.model_dump_json()
    result = reduce_profile_events(base, updates.request(base, second, first, first))
    assert result.samples == (fraud,)
    assert result.heads[0].revision == 2
    assert [item.outcome for item in result.outcomes] == ["APPLIED", "APPLIED"]
    assert base.model_dump_json() == original


@pytest.mark.parametrize("kind,issue,replay", [
    ("gap", "MISSING_REVISION", True), ("previous", "CONTENT_CONFLICT", False),
    ("same_revision", "CONTENT_CONFLICT", False), ("identity", "EVENT_ID_CONFLICT", False),
    ("owner", "SCOPE_MISMATCH", False), ("source", "SOURCE_MISMATCH", False),
])
def test_conflicts_abort_the_whole_batch(updates, kind, issue, replay):
    sample = updates.sample("p")
    base = updates.state()
    event = updates.event(sample)
    if kind == "gap":
        events = (updates.event(sample, revision=2),)
    elif kind == "previous":
        events = (updates.event(sample, previous_content_sha256="f" * 64),)
    elif kind in {"same_revision", "identity"}:
        events = (event, updates.event(updates.sample("p", amount=200), event_id="other" if kind == "same_revision" else event.event_id))
    elif kind == "owner":
        events = (updates.event(updates.sample("q", user="other")),)
    else:
        data = sample.model_dump(mode="python")
        data["source_reference"] = "other-source"
        events = (updates.event(type(sample).model_validate(data)),)
    before = base.model_dump_json()
    with pytest.raises(RevisionFailure) as error:
        reduce_profile_events(base, updates.request(base, *events))
    assert (error.value.issue, error.value.replay) == (issue, replay)
    assert base.model_dump_json() == before


def test_future_conflicts_are_deferred_and_not_remembered(updates):
    base = updates.state()
    known = updates.event(updates.sample("p"))
    future = updates.event(updates.sample("p", amount=500), available_at=updates.cutoff + timedelta(days=1))
    result = reduce_profile_events(base, updates.request(base, known, future))
    assert len(result.samples) == len(result.receipts) == 1
    assert {e.outcome for e in result.outcomes} == {"APPLIED", "DEFERRED_FUTURE"}


def test_current_purchase_is_deferred_without_state(updates):
    base = updates.state()
    result = reduce_profile_events(base, updates.request(base, updates.event(updates.sample("p")), current_purchase_request_id="p"))
    assert not result.samples and not result.heads and not result.receipts
    assert result.outcomes[0].outcome == "DEFERRED_CURRENT"


def test_remove_keeps_tombstone_for_later_deduplication(updates):
    sample = updates.sample("p")
    base = updates.state(sample)
    remove = updates.event(sample, before=sample, operation="REMOVE", sample=None, reason="REMOVAL")
    result = reduce_profile_events(base, updates.request(base, remove))
    assert not result.samples
    assert result.heads[0].content_sha256 is None
    assert result.outcomes[0].before_content_sha256 == sample_content_hash(sample)
