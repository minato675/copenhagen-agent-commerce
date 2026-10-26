"""Apply bounded admission replay to immutable revisioned personal/population profiles."""

from copenhagen_fds.contracts.profile_policy import ProfilePolicyConfig
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState, ProfilePolicyUpdateResult, PolicyTransition
from copenhagen_fds.contracts.profile_updates import ProfileUpdateRequest
from copenhagen_fds.personal_profiles import content_hash
from copenhagen_fds.profile_policy import assess_profile_policy, select_policy_observations
from copenhagen_fds.profile_revision_log import RevisionFailure, initialize_profile_update
from copenhagen_fds.profile_update_records import normalized_data
from copenhagen_fds.profile_update_replay import derive_replay_events
from copenhagen_fds.profile_updates import fit_updated_snapshot, profile_content, update_profile


def _build_policy_state(candidate, observations, config):
    snapshot = candidate.snapshot
    known = select_policy_observations(snapshot, observations)
    evaluation = assess_profile_policy(snapshot, known, config)
    admitted = {d.target.purchase_request_id for d in evaluation.decisions if d.status == "INCLUDE"}
    selected = tuple(row.sample for row in known if row.sample.eligibility.status != "INCLUDE"
                     or row.sample.eligibility.target.purchase_request_id in admitted)
    profile = snapshot.profile
    request = ProfileUpdateRequest(expected_state_version=candidate.state_version, expected_profile_reference=profile.reference,
        as_of=profile.as_of, generated_at=profile.generated_at, valid_until=profile.valid_until,
        changes_complete_from=profile.as_of, changes_complete_until=profile.as_of)
    fitted = fit_updated_snapshot(snapshot, request, selected)
    version = "profile-admission-v1-" + content_hash({"fitted": profile_content(fitted), "policy": normalized_data(evaluation)})
    data = fitted.model_dump(mode="python")
    for reference in (data["profile"]["reference"], data["state"]["profile_reference"]):
        reference.update(profile_id=profile.reference.profile_id + "-admitted", version=version)
    data["state"]["state_version"] = version
    published = type(fitted).model_validate(data)
    state_version = "policy-state-v1-" + content_hash({"candidate": candidate.state_version, "published": version})
    return ProfilePolicyState(state_version=state_version, candidate=candidate, published=published,
                              observations=known, evaluation=evaluation)


def initialize_profile_policy(snapshot, observations, config=ProfilePolicyConfig()):
    """Apply policy to a complete fitted candidate snapshot, including initialization."""
    return _build_policy_state(initialize_profile_update(snapshot), observations, config)


def _transitions(previous, current):
    old = {d.target.purchase_request_id: d for d in previous.evaluation.decisions}
    new = {d.target.purchase_request_id: d for d in current.evaluation.decisions}
    occurred = {r.sample.eligibility.target.purchase_request_id: r.sample.purchase_occurred_at for r in previous.observations}
    changes = []
    for pid in sorted(old.keys() | new.keys()):
        before, after = old.get(pid), new.get(pid)
        if before is not None and after is not None and (before.status, before.reason, before.sample_sha256, before.feedback) == (
            after.status, after.reason, after.sample_sha256, after.feedback,
        ):
            continue
        if before is not None and before.status == "INCLUDE" and (after is None or after.status != "INCLUDE"):
            action = "WITHDRAWN"
        elif after is None:
            action = "REMOVED"
        elif after.status == "INCLUDE":
            action = "REPLACED" if before is not None and before.status == "INCLUDE" else "ADMITTED"
        else:
            action = "HELD" if after.status == "DEFER" else "EXCLUDED"
        reason = after.reason if after is not None else (
            "RETENTION_EXPIRED" if occurred[pid] < current.candidate.retained_from else "OBSERVATION_REMOVED"
        )
        changes.append(PolicyTransition(purchase_request_id=pid, action=action, reason=reason,
            previous_status=before.status if before else None, next_status=after.status if after else None))
    return tuple(changes)


def update_profile_policy(state, request, observations):
    """Complete observation replay is mandatory. Persist both versions with atomic CAS externally."""
    previous = dict(previous_state_version=state.state_version, previous_published_reference=state.published.profile.reference)
    if request.expected_policy_state_version != state.state_version:
        return ProfilePolicyUpdateResult(**previous, status="REJECTED", issue="POLICY_VERSION_MISMATCH")
    observations = tuple(observations)
    try:
        events = derive_replay_events(state.candidate, request.update, (row.sample for row in observations))
    except RevisionFailure as exc:
        return ProfilePolicyUpdateResult(**previous, status="REPLAY_REQUIRED" if exc.replay else "REJECTED", issue=exc.issue)
    data = request.update.model_dump(mode="python")
    data["events"] = events
    updated = update_profile(state.candidate, ProfileUpdateRequest.model_validate(data))
    if updated.state is None:
        return ProfilePolicyUpdateResult(**previous, status=updated.status, issue=updated.issue)
    next_state = _build_policy_state(updated.state, observations, request.config or state.evaluation.config)
    return ProfilePolicyUpdateResult(**previous, status="UNCHANGED" if next_state.state_version == state.state_version else "UPDATED",
        state=next_state, candidate_events=updated.events, transitions=_transitions(state, next_state))
