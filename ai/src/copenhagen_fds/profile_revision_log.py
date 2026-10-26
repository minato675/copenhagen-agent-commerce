"""Apply complete purchase revisions to copied state, without fitting or persistence."""

from dataclasses import dataclass
from datetime import timedelta, timezone

from copenhagen_fds.contracts.population_profiles import PopulationProfileSnapshot
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.profile_updates import (
    EventReceipt, ProfileEventOutcome, ProfileUpdateEvent, ProfileUpdateRequest,
    ProfileUpdateState, PurchaseRevisionHead,
)
from copenhagen_fds.personal_profiles import canonical_sample, content_hash
from copenhagen_fds.profile_update_records import normalized_data, sample_content_hash


class RevisionFailure(ValueError):
    def __init__(self, issue, event_id=None, *, replay=False):
        super().__init__(issue)
        self.issue, self.event_id, self.replay = issue, event_id, replay


@dataclass(frozen=True)
class RevisionReduction:
    samples: tuple[LearningSample, ...]
    heads: tuple[PurchaseRevisionHead, ...]
    receipts: tuple[EventReceipt, ...]
    outcomes: tuple[ProfileEventOutcome, ...]


def in_profile_scope(snapshot, target):
    if target.currency != snapshot.profile.currency:
        return False
    if isinstance(snapshot, PopulationProfileSnapshot):
        return target.user_id in snapshot.cohort.member_user_ids
    return target.user_id == snapshot.profile.reference.user_id


def normalize_event(event):
    data = event.model_dump(mode="python")
    data["available_at"] = event.available_at.astimezone(timezone.utc)
    if event.sample is not None:
        data["sample"] = canonical_sample(event.sample)
    return ProfileUpdateEvent.model_validate(data)


def make_update_state(snapshot, heads, receipts=()) -> ProfileUpdateState:
    data = dict(snapshot=snapshot, retained_from=snapshot.long_term.start_inclusive,
                heads=tuple(sorted(heads, key=lambda h: h.target.purchase_request_id)),
                receipts=tuple(sorted(receipts, key=lambda r: r.event_id)))
    return ProfileUpdateState(state_version="profile-revisions-v1-" + content_hash(normalized_data(data)), **data)


def initialize_profile_update(snapshot) -> ProfileUpdateState:
    """Bootstrap a complete fitted snapshot; its existing purchases start at revision 0."""
    heads = tuple(PurchaseRevisionHead(
        target=sample.eligibility.target, revision=0, content_sha256=sample_content_hash(sample),
        purchase_occurred_at=sample.purchase_occurred_at, available_at=snapshot.profile.as_of,
    ) for sample in snapshot.state.retained_samples)
    return make_update_state(snapshot, heads)


def reduce_profile_events(state: ProfileUpdateState, request: ProfileUpdateRequest, *, lower=None) -> RevisionReduction:
    """Revision order wins over delivery order; any conflict rejects the whole batch."""
    snapshot = state.snapshot
    lower = lower or request.as_of - timedelta(days=snapshot.config.long_term_days)
    samples = {s.eligibility.target.purchase_request_id: s for s in snapshot.state.retained_samples}
    heads = {h.target.purchase_request_id: h for h in state.heads}
    receipts = {r.event_id: r for r in state.receipts if r.available_at >= lower}
    outcomes, known = [], {}

    def outcome(event, status, before=None, after=None, learning_status=None):
        return ProfileEventOutcome(event_id=event.event_id, purchase_request_id=event.target.purchase_request_id,
                                   revision=event.revision, reason=event.reason, outcome=status, before_content_sha256=before,
                                   after_content_sha256=after, learning_status=learning_status)

    # Future facts must not affect conflict decisions or the persisted revision log.
    deferred = {}
    for supplied in request.events:
        if supplied.available_at > request.as_of:
            deferred[supplied.event_id, supplied.revision, "DEFERRED_FUTURE"] = supplied
        elif supplied.target.purchase_request_id == request.current_purchase_request_id:
            deferred[supplied.event_id, supplied.revision, "DEFERRED_CURRENT"] = supplied
        elif supplied.available_at < lower:
            deferred[supplied.event_id, supplied.revision, "IGNORED_EXPIRED"] = supplied
        else:
            event = normalize_event(supplied)
            fingerprint = content_hash(normalized_data(event))
            if event.event_id in known and known[event.event_id][1] != fingerprint:
                raise RevisionFailure("EVENT_ID_CONFLICT", event.event_id)
            known[event.event_id] = event, fingerprint
    outcomes.extend(outcome(event, key[2]) for key, event in sorted(deferred.items()))
    for event, fingerprint in sorted(known.values(), key=lambda pair: (
        pair[0].target.purchase_request_id, pair[0].revision, pair[0].event_id,
    )):
        if not in_profile_scope(snapshot, event.target):
            raise RevisionFailure("SCOPE_MISMATCH", event.event_id)
        if event.sample is not None and (event.sample.origin, event.sample.source_reference) != (
            snapshot.profile.origin, snapshot.profile.source_reference,
        ):
            raise RevisionFailure("SOURCE_MISMATCH", event.event_id)
        if event.event_id in receipts:
            if receipts[event.event_id].event_sha256 != fingerprint:
                raise RevisionFailure("EVENT_ID_CONFLICT", event.event_id)
            outcomes.append(outcome(event, "DUPLICATE"))
            continue
        pid = event.target.purchase_request_id
        head = heads.get(pid)
        before = None if head is None else head.content_sha256
        after = None if event.sample is None else sample_content_hash(event.sample)
        if head is not None and (head.target.user_id, head.target.currency) != (event.target.user_id, event.target.currency):
            raise RevisionFailure("CONTENT_CONFLICT", event.event_id)
        if head is not None and event.revision < head.revision:
            status = "STALE"
        elif head is not None and event.revision == head.revision:
            if after != before or event.target != head.target:
                raise RevisionFailure("CONTENT_CONFLICT", event.event_id)
            status = "DUPLICATE"
        else:
            if event.revision != (1 if head is None else head.revision + 1):
                raise RevisionFailure("MISSING_REVISION", event.event_id, replay=True)
            if event.previous_content_sha256 != before:
                raise RevisionFailure("CONTENT_CONFLICT", event.event_id)
            if event.operation == "REMOVE":
                if head is None or event.target != head.target or event.available_at < head.purchase_occurred_at:
                    raise RevisionFailure("CONTENT_CONFLICT", event.event_id)
                samples.pop(pid, None)
                occurred = head.purchase_occurred_at
            else:
                samples[pid] = event.sample
                occurred = event.sample.purchase_occurred_at
            heads[pid] = PurchaseRevisionHead(target=event.target, revision=event.revision, content_sha256=after,
                                               purchase_occurred_at=occurred, available_at=event.available_at)
            status = "APPLIED"
        receipts[event.event_id] = EventReceipt(event_id=event.event_id, event_sha256=fingerprint, available_at=event.available_at)
        current = samples.get(pid)
        outcomes.append(outcome(event, status, before, heads[pid].content_sha256,
                                None if current is None else current.eligibility.status))
    kept_heads = tuple(head for _, head in sorted(heads.items()) if max(head.purchase_occurred_at, head.available_at) >= lower)
    return RevisionReduction(tuple(samples[key] for key in sorted(samples)), kept_heads,
                             tuple(receipts[key] for key in sorted(receipts)),
                             tuple(sorted(outcomes, key=lambda item: (item.purchase_request_id, item.revision, item.event_id, item.outcome))))
