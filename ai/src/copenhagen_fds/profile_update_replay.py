"""Reconcile a complete observable learning replay with bounded revision state."""

from datetime import timedelta, timezone

from copenhagen_fds.contracts.profile_updates import ProfileUpdateEvent, ProfileUpdateRequest, ProfileUpdateResult
from copenhagen_fds.personal_profiles import canonical_sample, content_hash
from copenhagen_fds.profile_revision_log import RevisionFailure, in_profile_scope
from copenhagen_fds.profile_update_records import normalized_data, sample_content_hash
from copenhagen_fds.profile_updates import _guard_update, fit_updated_snapshot, profile_content, update_profile


def _replay_samples(state, request, samples):
    """The supplied replay must be complete, at one cutoff and from the same source."""
    profile = state.snapshot.profile
    config = request.personal_config or request.population_config or state.snapshot.config
    lower = request.as_of - timedelta(days=config.long_term_days)
    selected = {}
    for sample in samples:
        if (sample.eligibility.as_of, sample.origin, sample.source_reference) != (
            request.as_of, profile.origin, profile.source_reference,
        ):
            raise ValueError("complete replay must match the requested cutoff and source")
        target = sample.eligibility.target
        if not in_profile_scope(state.snapshot, target):
            continue
        if target.purchase_request_id == request.current_purchase_request_id:
            raise ValueError("complete replay must exclude the current evaluated purchase")
        if not lower <= sample.purchase_occurred_at < request.as_of:
            continue
        canonical = canonical_sample(sample)
        previous = selected.get(target.purchase_request_id)
        if previous is not None and sample_content_hash(previous) != sample_content_hash(canonical):
            raise ValueError("complete replay contains conflicting purchase content")
        selected[target.purchase_request_id] = canonical
    return selected


def derive_replay_events(state, request: ProfileUpdateRequest, samples) -> tuple[ProfileUpdateEvent, ...]:
    """Generate a net replacement for every affected purchase, including derived features.

    This adapter requires a complete final snapshot, never a partial delivery batch.
    REPLAY_DIFF events describe when that net difference was observed; they do not
    reconstruct the underlying feedback delivery sequence.
    """
    if request.events:
        raise ValueError("replay diff request must not also supply revision events")
    lower = _guard_update(state, request)
    desired = _replay_samples(state, request, samples)
    heads = {head.target.purchase_request_id: head for head in state.heads}
    candidates = set(desired) | {
        s.eligibility.target.purchase_request_id for s in state.snapshot.state.retained_samples
        if lower <= s.purchase_occurred_at < request.as_of
    }
    events = []
    for pid in sorted(candidates):
        head, sample = heads.get(pid), desired.get(pid)
        before = head.content_sha256 if head else None
        after = sample_content_hash(sample) if sample else None
        if before == after:
            continue
        target = sample.eligibility.target if sample else head.target
        revision = head.revision + 1 if head else 1
        data = dict(target=target, revision=revision, previous_content_sha256=before,
                    available_at=request.as_of.astimezone(timezone.utc),
                    operation="UPSERT" if sample else "REMOVE", reason="REPLAY_DIFF", sample=sample)
        events.append(ProfileUpdateEvent(event_id="replay-diff-" + content_hash(normalized_data(data)), **data))
    return tuple(events)


def compare_profile_replay(state, request, samples) -> dict:
    """Fit the authoritative final observations independently of the revision reducer."""
    selected = _replay_samples(state, request, samples)
    full = fit_updated_snapshot(state.snapshot, request, tuple(selected.values()))
    actual = state.snapshot
    expected_content, actual_content = profile_content(full), profile_content(actual)
    return {
        "matched": expected_content == actual_content,
        "recent_equal": normalized_data(full.recent) == normalized_data(actual.recent),
        "long_term_equal": normalized_data(full.long_term) == normalized_data(actual.long_term),
        "retained_samples_equal": normalized_data(full.state.retained_samples) == normalized_data(actual.state.retained_samples),
        "full_replay_sha256": content_hash(expected_content), "updated_sha256": content_hash(actual_content),
        "retained_samples": full.long_term.retained_count, "included_samples": full.long_term.included_count,
    }


def replay_profile_update(state, request, samples):
    """Return update result and an independent full-fit comparison, without persistence."""
    samples = tuple(samples)
    try:
        events = derive_replay_events(state, request, samples)
    except RevisionFailure as exc:
        return ProfileUpdateResult(
            status="REPLAY_REQUIRED" if exc.replay else "REJECTED", issue=exc.issue, issue_event_id=exc.event_id,
            previous_state_version=state.state_version, previous_profile_reference=state.snapshot.profile.reference,
        ), None
    data = request.model_dump(mode="python")
    data["events"] = events
    result = update_profile(state, ProfileUpdateRequest.model_validate(data))
    comparison = compare_profile_replay(result.state, request, samples) if result.state else None
    return result, comparison
