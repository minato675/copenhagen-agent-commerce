"""Pure optimistic profile updates, with exact bounded-window statistical rebuilding."""

from datetime import timedelta, timezone

from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.population_profiles import PopulationProfileRequest, PopulationProfileSnapshot
from copenhagen_fds.contracts.profile_updates import ProfileUpdateRequest, ProfileUpdateResult, ProfileUpdateState
from copenhagen_fds.personal_profiles import build_personal_profile, content_hash
from copenhagen_fds.population_profiles import build_population_profile
from copenhagen_fds.profile_revision_log import (
    RevisionFailure, initialize_profile_update, make_update_state, reduce_profile_events,
)
from copenhagen_fds.profile_update_records import normalized_data, sample_at_cutoff


def profile_content(snapshot) -> dict:
    """Fitted content independent of externally assigned profile/statistics versions."""
    data = normalized_data(snapshot)
    data["profile"]["reference"].pop("version")
    data["state"]["profile_reference"].pop("version")
    data["state"].pop("state_version")
    return data


def fit_updated_snapshot(snapshot, request: ProfileUpdateRequest, samples):
    """Recompute all order statistics; supplied samples must be known by the cutoff."""
    common = dict(reference=snapshot.profile.reference, state_version="pending",
                  origin=snapshot.profile.origin, source_reference=snapshot.profile.source_reference,
                  as_of=request.as_of.astimezone(timezone.utc),
                  generated_at=request.generated_at.astimezone(timezone.utc),
                  valid_until=request.valid_until.astimezone(timezone.utc))
    carried = tuple(sample_at_cutoff(sample, request.as_of) for sample in samples)
    if isinstance(snapshot, PopulationProfileSnapshot):
        fitted = build_population_profile(PopulationProfileRequest(
            **common, cohort=request.cohort or snapshot.cohort,
            config=request.population_config or snapshot.config,
        ), carried)
    else:
        fitted = build_personal_profile(PersonalProfileRequest(
            **common, user_id=snapshot.profile.reference.user_id, currency=snapshot.profile.currency,
            config=request.personal_config or snapshot.config,
        ), carried)
    if profile_content(fitted) == profile_content(snapshot):
        return snapshot
    version = "profile-content-v1-" + content_hash(profile_content(fitted))
    data = fitted.model_dump(mode="python")
    data["profile"]["reference"]["version"] = version
    data["state"]["profile_reference"]["version"] = version
    data["state"]["state_version"] = version
    return type(fitted).model_validate(data)


def _guard_update(state, request):
    snapshot, profile = state.snapshot, state.snapshot.profile
    if request.expected_state_version != state.state_version or request.expected_profile_reference != profile.reference:
        raise RevisionFailure("VERSION_MISMATCH")
    if request.as_of < profile.as_of or request.generated_at < profile.generated_at:
        raise RevisionFailure("TIME_REGRESSION")
    population = isinstance(snapshot, PopulationProfileSnapshot)
    if (population and request.personal_config is not None) or (not population and (
        request.population_config is not None or request.cohort is not None
    )):
        raise RevisionFailure("SCOPE_MISMATCH")
    if population and request.cohort is not None:
        cohort = normalized_data(request.cohort)
        cohort["member_user_ids"] = sorted(cohort["member_user_ids"])
        if cohort != normalized_data(snapshot.cohort):
            raise RevisionFailure("COHORT_CHANGED", replay=True)
    if request.changes_complete_from > profile.as_of or request.changes_complete_until < request.as_of:
        raise RevisionFailure("CHANGE_HISTORY_GAP", replay=True)
    config = (request.population_config if population else request.personal_config) or snapshot.config
    lower = request.as_of - timedelta(days=config.long_term_days)
    if lower < state.retained_from:
        raise RevisionFailure("RETENTION_GAP", replay=True)
    if any(s.eligibility.target.purchase_request_id == request.current_purchase_request_id
           for s in snapshot.state.retained_samples):
        raise RevisionFailure("CURRENT_PURCHASE_IN_STATE", replay=True)
    return lower


def update_profile(state: ProfileUpdateState, request: ProfileUpdateRequest) -> ProfileUpdateResult:
    """All-or-nothing in memory. The persistence caller must separately perform atomic CAS."""
    previous = dict(previous_state_version=state.state_version,
                    previous_profile_reference=state.snapshot.profile.reference)
    try:
        lower = _guard_update(state, request)
        reduced = reduce_profile_events(state, request, lower=lower)
        snapshot = fit_updated_snapshot(state.snapshot, request, reduced.samples)
        updated = make_update_state(snapshot, reduced.heads, reduced.receipts)
    except RevisionFailure as exc:
        return ProfileUpdateResult(**previous, status="REPLAY_REQUIRED" if exc.replay else "REJECTED",
                                   issue=exc.issue, issue_event_id=exc.event_id)
    return ProfileUpdateResult(**previous, status="UNCHANGED" if updated.state_version == state.state_version else "UPDATED",
                               state=updated, events=reduced.outcomes)
