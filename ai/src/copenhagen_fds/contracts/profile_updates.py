"""Immutable, bounded revision state and optimistic profile update contracts."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.learning import LearningPurchaseReference
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_profiles import PersonalProfileConfig, PersonalProfileSnapshot
from copenhagen_fds.contracts.population_profiles import CohortDefinition, PopulationProfileConfig, PopulationProfileSnapshot
from copenhagen_fds.contracts.profiles import ProfileReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256
from copenhagen_fds.profile_update_records import sample_content_hash


ProfileSnapshot = Annotated[PersonalProfileSnapshot | PopulationProfileSnapshot, Field(discriminator="schema_version")]


class ProfileUpdateEvent(InputModel):
    event_id: Identifier
    target: LearningPurchaseReference
    revision: Annotated[int, Field(gt=0)]
    previous_content_sha256: Sha256 | None
    available_at: Timestamp
    operation: Literal["UPSERT", "REMOVE"]
    reason: Literal["NEW_PURCHASE", "FEEDBACK", "CORRECTION", "REMOVAL", "REPLAY_DIFF"]
    sample: LearningSample | None = None

    @model_validator(mode="after")
    def validate_event(self) -> Self:
        if (self.operation == "UPSERT") != (self.sample is not None):
            raise ValueError("upsert requires a complete sample; removal forbids a sample")
        if self.sample is not None:
            if self.sample.eligibility.target != self.target:
                raise ValueError("revision target must match the complete supplied purchase reference")
            if self.sample.eligibility.as_of > self.available_at:
                raise ValueError("revision cannot expose facts from after its availability")
        elif self.previous_content_sha256 is None:
            raise ValueError("removal requires the previous purchase content hash")
        return self


class PurchaseRevisionHead(InputModel):
    target: LearningPurchaseReference
    revision: Annotated[int, Field(ge=0)]
    content_sha256: Sha256 | None
    purchase_occurred_at: Timestamp
    available_at: Timestamp

    @model_validator(mode="after")
    def validate_time(self) -> Self:
        if self.purchase_occurred_at > self.available_at:
            raise ValueError("revision head cannot precede its purchase")
        return self


class EventReceipt(InputModel):
    event_id: Identifier
    event_sha256: Sha256
    available_at: Timestamp


class ProfileUpdateState(InputModel):
    schema_version: Literal["profile-update-state-v1"] = "profile-update-state-v1"
    state_version: Identifier
    snapshot: ProfileSnapshot
    # The caller attests the initial snapshot was built from the full declared range.
    retained_from: Timestamp
    heads: Array[PurchaseRevisionHead]
    receipts: Array[EventReceipt] = ()

    @model_validator(mode="after")
    def validate_state(self) -> Self:
        snapshot = self.snapshot
        if self.retained_from != snapshot.long_term.start_inclusive:
            raise ValueError("update state retention must match the statistical retention window")
        ids = [head.target.purchase_request_id for head in self.heads]
        if ids != sorted(set(ids)):
            raise ValueError("revision heads must contain unique ordered purchase identities")
        event_ids = [receipt.event_id for receipt in self.receipts]
        if event_ids != sorted(set(event_ids)):
            raise ValueError("event receipts must contain unique ordered event identities")
        by_id = {sample.eligibility.target.purchase_request_id: sample for sample in snapshot.state.retained_samples}
        profile = snapshot.profile
        for head in self.heads:
            owner = head.target.user_id
            if head.target.currency != profile.currency or (profile.reference.scope == "PERSONAL" and owner != profile.reference.user_id):
                raise ValueError("revision heads must match the profile owner and currency")
            if isinstance(snapshot, PopulationProfileSnapshot) and owner not in snapshot.cohort.member_user_ids:
                raise ValueError("revision heads must match supplied population membership")
            if head.available_at > profile.as_of or max(head.purchase_occurred_at, head.available_at) < self.retained_from:
                raise ValueError("revision head lies outside retained knowledge")
            sample = by_id.pop(head.target.purchase_request_id, None)
            active = head.content_sha256 is not None and self.retained_from <= head.purchase_occurred_at < profile.as_of
            if active != (sample is not None):
                raise ValueError("active revision heads must match retained statistical samples")
            if sample is not None and (sample.eligibility.target != head.target or sample.purchase_occurred_at != head.purchase_occurred_at
                                       or sample_content_hash(sample) != head.content_sha256):
                raise ValueError("revision head must match retained purchase content")
        if by_id:
            raise ValueError("every retained purchase requires a revision head")
        if any(not self.retained_from <= receipt.available_at <= profile.as_of for receipt in self.receipts):
            raise ValueError("event receipts must stay within the retained knowledge window")
        return self


class ProfileUpdateRequest(InputModel):
    expected_state_version: Identifier
    expected_profile_reference: ProfileReference
    as_of: Timestamp
    generated_at: Timestamp
    valid_until: Timestamp
    changes_complete_from: Timestamp
    changes_complete_until: Timestamp
    current_purchase_request_id: Identifier | None = None
    personal_config: PersonalProfileConfig | None = None
    population_config: PopulationProfileConfig | None = None
    cohort: CohortDefinition | None = None
    events: Array[ProfileUpdateEvent] = ()

    @model_validator(mode="after")
    def validate_time(self) -> Self:
        if not self.as_of <= self.generated_at < self.valid_until:
            raise ValueError("update cutoff, generation and expiry must be ordered")
        if self.changes_complete_from > self.changes_complete_until or self.changes_complete_until > self.as_of:
            raise ValueError("declared change coverage must be ordered and cannot exceed the cutoff")
        if self.personal_config is not None and self.population_config is not None:
            raise ValueError("only one profile scope configuration may be supplied")
        return self


UpdateIssue = Literal[
    "VERSION_MISMATCH", "TIME_REGRESSION", "SCOPE_MISMATCH", "SOURCE_MISMATCH",
    "CHANGE_HISTORY_GAP", "RETENTION_GAP", "COHORT_CHANGED", "MISSING_REVISION",
    "CONTENT_CONFLICT", "EVENT_ID_CONFLICT", "CURRENT_PURCHASE_IN_STATE",
]


class ProfileEventOutcome(InputModel):
    event_id: Identifier
    purchase_request_id: Identifier
    revision: Annotated[int, Field(gt=0)]
    reason: Identifier
    outcome: Literal["APPLIED", "DUPLICATE", "STALE", "DEFERRED_FUTURE", "DEFERRED_CURRENT", "IGNORED_EXPIRED"]
    before_content_sha256: Sha256 | None = None
    after_content_sha256: Sha256 | None = None
    learning_status: Literal["INCLUDE", "DEFER", "EXCLUDE"] | None = None


class ProfileUpdateResult(InputModel):
    schema_version: Literal["profile-update-result-v1"] = "profile-update-result-v1"
    status: Literal["UPDATED", "UNCHANGED", "REJECTED", "REPLAY_REQUIRED"]
    previous_state_version: Identifier
    previous_profile_reference: ProfileReference
    issue: UpdateIssue | None = None
    issue_event_id: Identifier | None = None
    state: ProfileUpdateState | None = None
    events: Array[ProfileEventOutcome] = ()

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        success = self.status in {"UPDATED", "UNCHANGED"}
        if success != (self.state is not None) or success == (self.issue is not None):
            raise ValueError("successful updates require state; failed updates require a reason")
        if not success and self.events:
            raise ValueError("failed batches cannot claim partially applied events")
        if self.status == "UNCHANGED" and (self.state.state_version != self.previous_state_version
                                         or self.state.snapshot.profile.reference != self.previous_profile_reference):
            raise ValueError("unchanged result must preserve both versions")
        return self
