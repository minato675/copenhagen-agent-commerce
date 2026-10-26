"""Versioned candidate and admitted profiles with complete policy provenance."""

from collections import Counter
from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.profile_policy import PolicyEvaluation, PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.contracts.profile_updates import ProfileEventOutcome, ProfileSnapshot, ProfileUpdateRequest, ProfileUpdateState
from copenhagen_fds.contracts.profiles import ProfileReference
from copenhagen_fds.contracts.values import Array, Identifier, InputModel
from copenhagen_fds.profile_policy_reference import KST
from copenhagen_fds.profile_update_records import sample_content_hash


class ProfilePolicyState(InputModel):
    schema_version: Literal["profile-policy-state-v1"] = "profile-policy-state-v1"
    state_version: Identifier
    candidate: ProfileUpdateState
    published: ProfileSnapshot
    observations: Array[PolicyObservation]
    evaluation: PolicyEvaluation

    @model_validator(mode="after")
    def validate_state(self) -> Self:
        profile, published = self.candidate.snapshot.profile, self.published.profile
        if (profile.as_of, self.candidate.retained_from) != (self.evaluation.as_of, self.evaluation.replay_from):
            raise ValueError("policy and candidate replay boundaries must match")
        if (profile.currency, profile.origin, profile.source_reference, profile.as_of) != (
            published.currency, published.origin, published.source_reference, published.as_of,
        ) or self.published.config != self.candidate.snapshot.config:
            raise ValueError("published policy profile must preserve its candidate context")
        expected_reference = profile.reference.model_dump(mode="python")
        expected_reference.update(profile_id=profile.reference.profile_id + "-admitted", version=published.reference.version)
        if published.reference != ProfileReference.model_validate(expected_reference):
            raise ValueError("published profile must have a separate identity with the same scope")
        if hasattr(self.candidate.snapshot, "cohort") and self.published.cohort != self.candidate.snapshot.cohort:
            raise ValueError("published membership must match candidate membership")
        ids = [row.sample.eligibility.target.purchase_request_id for row in self.observations]
        if ids != sorted(set(ids)) or ids != [d.target.purchase_request_id for d in self.evaluation.decisions]:
            raise ValueError("policy evidence and decisions must be complete and ordered")
        expected = {s.eligibility.target.purchase_request_id: sample_content_hash(s) for s in self.candidate.snapshot.state.retained_samples}
        if expected != {pid: sample_content_hash(row.sample) for pid, row in zip(ids, self.observations)}:
            raise ValueError("policy evidence must cover all candidate samples")
        selected, counts = {}, Counter()
        for row, decision in zip(self.observations, self.evaluation.decisions):
            if row.feedback is not None and row.feedback.available_at > profile.as_of:
                raise ValueError("stored policy evidence cannot contain future feedback")
            if (decision.sample_sha256, decision.target, decision.feedback, decision.raw_status, decision.raw_reason) != (
                sample_content_hash(row.sample), row.sample.eligibility.target, row.feedback,
                row.sample.eligibility.status, row.sample.eligibility.reason,
            ):
                raise ValueError("policy decision must preserve the underlying observation")
            if decision.status == "INCLUDE":
                if decision.admitted_at < max(row.sample.raw_features.evaluated_at, row.feedback.available_at):
                    raise ValueError("admission cannot precede the available observation")
                counts[decision.target.user_id, decision.admitted_at.astimezone(KST).date()] += 1
            # Keep raw non-normal eligibility records for the existing statistical counters.
            if decision.status == "INCLUDE" or row.sample.eligibility.status != "INCLUDE":
                selected[decision.target.purchase_request_id] = decision.sample_sha256
        if counts and max(counts.values()) > self.evaluation.config.max_admissions_per_user_day:
            raise ValueError("admissions exceed the per-user daily limit")
        actual = {s.eligibility.target.purchase_request_id: sample_content_hash(s) for s in self.published.state.retained_samples}
        if selected != actual:
            raise ValueError("published profile must contain exactly the policy-selected samples")
        return self


class ProfilePolicyUpdateRequest(InputModel):
    expected_policy_state_version: Identifier
    update: ProfileUpdateRequest
    config: ProfilePolicyConfig | None = None

    @model_validator(mode="after")
    def validate_replay_request(self) -> Self:
        if self.update.events:
            raise ValueError("policy update takes complete observation replay, not mixed explicit events")
        return self


class PolicyTransition(InputModel):
    purchase_request_id: Identifier
    action: Literal["ADMITTED", "WITHDRAWN", "REPLACED", "HELD", "EXCLUDED", "REMOVED"]
    reason: Identifier
    previous_status: Literal["INCLUDE", "DEFER", "EXCLUDE"] | None
    next_status: Literal["INCLUDE", "DEFER", "EXCLUDE"] | None


class ProfilePolicyUpdateResult(InputModel):
    status: Literal["UPDATED", "UNCHANGED", "REJECTED", "REPLAY_REQUIRED"]
    previous_state_version: Identifier
    previous_published_reference: ProfileReference
    issue: Identifier | None = None
    state: ProfilePolicyState | None = None
    candidate_events: Array[ProfileEventOutcome] = ()
    transitions: Array[PolicyTransition] = ()

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        success = self.status in {"UPDATED", "UNCHANGED"}
        if success != (self.state is not None) or success == (self.issue is not None):
            raise ValueError("success requires state and failure requires an issue")
        if not success and (self.candidate_events or self.transitions):
            raise ValueError("failed policy batches cannot claim partial changes")
        if self.status == "UNCHANGED" and self.state.state_version != self.previous_state_version:
            raise ValueError("unchanged policy state must preserve its version")
        return self
