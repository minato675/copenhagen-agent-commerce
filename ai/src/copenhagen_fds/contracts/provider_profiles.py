"""Full-observation calculation contracts; the AI storage layer owns persistence/CAS."""

from datetime import timedelta
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.population_profiles import PopulationProfileRequest
from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState, ProfilePolicyUpdateRequest, ProfilePolicyUpdateResult
from copenhagen_fds.contracts.provider_evaluation import CallMetadata, ConfigurationReference
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, InputModel, Sha256
from copenhagen_fds.profile_update_records import sample_content_hash


class FullObservationReplay(InputModel):
    # All fields are required so a one-event caller cannot silently inherit replay semantics.
    mode: Literal["FULL_REPLAY"]
    omitted_purchase: Literal["REMOVE"]
    complete_from: Timestamp
    complete_until: Timestamp
    observations: Array[PolicyObservation] = Field(repr=False)

    @model_validator(mode="after")
    def validate_replay(self) -> Self:
        if self.complete_from >= self.complete_until:
            raise ValueError("full replay requires a nonempty declared time range")
        ids = [r.sample.eligibility.target.purchase_request_id for r in self.observations]
        if len(ids) != len(set(ids)):
            raise ValueError("full replay contains at most one complete observation per purchase")
        for row in self.observations:
            sample = row.sample
            if not self.complete_from <= sample.purchase_occurred_at < self.complete_until:
                raise ValueError("observation must fall within the declared retention window")
            if sample.eligibility.as_of != self.complete_until:
                raise ValueError("learning eligibility must use the complete replay cutoff")
            if row.feedback is not None and row.feedback.available_at > self.complete_until:
                raise ValueError("full replay cannot contain future feedback")
        return self


def _validate_observations(replay, *, reference, currency, origin, source_reference, as_of, days,
                           cohort=None, current_purchase_request_id=None):
    if (replay.complete_from, replay.complete_until) != (as_of - timedelta(days=days), as_of):
        raise ValueError("full replay must cover the entire configured retention window")
    for row in replay.observations:
        sample, target = row.sample, row.sample.eligibility.target
        if (target.currency, sample.origin, sample.source_reference) != (currency, origin, source_reference):
            raise ValueError("observation currency and provenance must match the profile")
        if reference.scope == "PERSONAL" and target.user_id != reference.user_id:
            raise ValueError("personal replay cannot contain another user's observation")
        if reference.scope == "POPULATION" and target.user_id not in cohort.member_user_ids:
            raise ValueError("population replay requires explicit cohort membership")
        if target.purchase_request_id == current_purchase_request_id:
            raise ValueError("current evaluation purchase cannot be learned during evaluation")


class ProviderProfileCreateRequest(InputModel):
    schema_version: Literal["fds-provider-v1"] = "fds-provider-v1"
    operation: Literal["CREATE_PERSONAL_PROFILE", "CREATE_POPULATION_PROFILE"]
    call: CallMetadata
    configuration: ConfigurationReference
    profile: PersonalProfileRequest | PopulationProfileRequest = Field(repr=False)
    policy: ProfilePolicyConfig
    replay: FullObservationReplay = Field(repr=False)

    @model_validator(mode="after")
    def validate_creation(self) -> Self:
        personal = isinstance(self.profile, PersonalProfileRequest)
        if personal != (self.operation == "CREATE_PERSONAL_PROFILE"):
            raise ValueError("operation must match the supplied profile scope")
        profile = self.profile
        if not profile.generated_at <= self.call.sent_at < profile.valid_until:
            raise ValueError("profile generation and expiry must bracket request submission")
        if self.configuration.content_sha256 != personalization_hash((profile.config, self.policy)):
            raise ValueError("configuration must bind statistical and admission policies")
        _validate_observations(self.replay, reference=profile.reference,
            currency=profile.currency if personal else profile.cohort.currency,
            origin=profile.origin, source_reference=profile.source_reference, as_of=profile.as_of,
            days=profile.config.long_term_days, cohort=None if personal else profile.cohort)
        return self


def _ordered_cohort(cohort):
    # The native builder sorts members; their order is not membership identity.
    return cohort.model_copy(update={"member_user_ids": tuple(sorted(cohort.member_user_ids))})


class ProviderProfileUpdateRequest(InputModel):
    schema_version: Literal["fds-provider-v1"] = "fds-provider-v1"
    operation: Literal["UPDATE_PROFILE"]
    call: CallMetadata
    configuration: ConfigurationReference
    state: ProfilePolicyState = Field(repr=False)
    update: ProfilePolicyUpdateRequest
    replay: FullObservationReplay = Field(repr=False)

    @model_validator(mode="after")
    def validate_update(self) -> Self:
        state, outer = self.state, self.update
        update, snapshot = outer.update, state.candidate.snapshot
        profile = snapshot.profile
        if (outer.expected_policy_state_version, update.expected_state_version, update.expected_profile_reference) != (
            state.state_version, state.candidate.state_version, profile.reference,
        ):
            raise ValueError("expected versions must match the supplied state and candidate reference")
        if not profile.as_of <= update.as_of <= update.generated_at <= self.call.sent_at < update.valid_until:
            raise ValueError("update cannot regress time or use an already expired publication")
        if update.changes_complete_from > profile.as_of or update.changes_complete_until != update.as_of:
            raise ValueError("change coverage must bridge the previous and new cutoffs")
        personal = profile.reference.scope == "PERSONAL"
        if (personal and (update.population_config is not None or update.cohort is not None)) or (
            not personal and update.personal_config is not None
        ):
            raise ValueError("update configuration cannot switch profile scope")
        cohort = None if personal else (update.cohort or snapshot.cohort)
        if cohort is not None and _ordered_cohort(cohort) != _ordered_cohort(snapshot.cohort):
            raise ValueError("changed cohort membership requires a separate initial rebuild")
        config = (update.personal_config if personal else update.population_config) or snapshot.config
        policy = outer.config or state.evaluation.config
        if self.configuration.content_sha256 != personalization_hash((config, policy)):
            raise ValueError("configuration must bind the effective statistical and admission policies")
        _validate_observations(self.replay, reference=profile.reference, currency=profile.currency,
            origin=profile.origin, source_reference=profile.source_reference, as_of=update.as_of,
            days=config.long_term_days, cohort=cohort, current_purchase_request_id=update.current_purchase_request_id)
        return self


class ProviderProfileCreateResponse(InputModel):
    schema_version: Literal["fds-provider-v1"] = "fds-provider-v1"
    operation: Literal["CREATE_PERSONAL_PROFILE", "CREATE_POPULATION_PROFILE"]
    call: CallMetadata
    request_sha256: Sha256
    completed_at: Timestamp
    status: Literal["CREATED"] = "CREATED"
    # Trusted AI service/storage payload: contains evidence; not a user-facing DTO.
    state: ProfilePolicyState = Field(repr=False)
    storage_status: Literal["NOT_PERSISTED"] = "NOT_PERSISTED"

    @model_validator(mode="after")
    def validate_response(self) -> Self:
        profile = self.state.candidate.snapshot.profile
        if (profile.reference.scope == "PERSONAL") != (self.operation == "CREATE_PERSONAL_PROFILE"):
            raise ValueError("created state must match response scope")
        if not profile.generated_at <= self.call.sent_at <= self.completed_at < profile.valid_until:
            raise ValueError("created state must remain unexpired at completion")
        return self

    def validate_for_request(self, request: ProviderProfileCreateRequest) -> Self:
        if (self.operation, self.call, self.request_sha256) != (
            request.operation, request.call, personalization_hash(request),
        ):
            raise ValueError("creation response must bind the complete submitted request")
        snapshot, profile = self.state.candidate.snapshot, request.profile
        actual = snapshot.profile
        if (actual.reference, actual.origin, actual.source_reference, actual.as_of, actual.generated_at,
            actual.valid_until, snapshot.config, self.state.evaluation.config) != (
            profile.reference, profile.origin, profile.source_reference, profile.as_of, profile.generated_at,
            profile.valid_until, profile.config, request.policy,
        ):
            raise ValueError("created state must preserve the requested context and policy")
        if actual.currency != (profile.currency if isinstance(profile, PersonalProfileRequest) else profile.cohort.currency):
            raise ValueError("created state must preserve currency")
        if isinstance(profile, PopulationProfileRequest):
            if _ordered_cohort(snapshot.cohort) != _ordered_cohort(profile.cohort):
                raise ValueError("created state must preserve cohort membership")
        if snapshot.state.state_version != profile.state_version:
            raise ValueError("created statistical state must preserve its requested initial version")
        _validate_state_observations(self.state, request.replay)
        return self


def _validate_state_observations(state, replay):
    def rows(observations):
        return {r.sample.eligibility.target.purchase_request_id:
                (sample_content_hash(r.sample), r.feedback) for r in observations}
    if rows(state.observations) != rows(replay.observations):
        raise ValueError("returned state must preserve the complete supplied observation replay")


class ProviderProfileUpdateResponse(InputModel):
    schema_version: Literal["fds-provider-v1"] = "fds-provider-v1"
    operation: Literal["UPDATE_PROFILE"]
    call: CallMetadata
    request_sha256: Sha256
    completed_at: Timestamp
    result: ProfilePolicyUpdateResult = Field(repr=False)
    storage_status: Literal["NOT_PERSISTED"] = "NOT_PERSISTED"

    @model_validator(mode="after")
    def validate_response(self) -> Self:
        if self.completed_at < self.call.sent_at:
            raise ValueError("update completion cannot precede submission")
        if self.result.state is not None and self.completed_at >= self.result.state.published.profile.valid_until:
            raise ValueError("returned publication must remain unexpired at completion")
        return self

    def validate_for_request(self, request: ProviderProfileUpdateRequest) -> Self:
        previous = request.state
        if (self.call, self.request_sha256, self.result.previous_state_version, self.result.previous_published_reference) != (
            request.call, personalization_hash(request), previous.state_version, previous.published.profile.reference,
        ):
            raise ValueError("update response must bind the submitted request and previous publication")
        state = self.result.state
        if state is not None:
            before, after = previous.candidate.snapshot.profile, state.candidate.snapshot.profile
            if (before.reference.scope, before.reference.user_id, before.reference.cohort_id,
                before.reference.profile_id, before.currency, before.origin, before.source_reference) != (
                after.reference.scope, after.reference.user_id, after.reference.cohort_id,
                after.reference.profile_id, after.currency, after.origin, after.source_reference,
            ):
                raise ValueError("updated state must retain the same profile identity and provenance")
            update = request.update.update
            if (after.as_of, after.generated_at, after.valid_until) != (update.as_of, update.generated_at, update.valid_until):
                raise ValueError("updated state must preserve the requested publication times")
            expected_config = (update.personal_config or update.population_config or previous.candidate.snapshot.config)
            if (state.candidate.snapshot.config, state.evaluation.config) != (
                expected_config, request.update.config or previous.evaluation.config,
            ):
                raise ValueError("updated state must preserve effective configurations")
            if before.reference.scope == "POPULATION" and (
                _ordered_cohort(state.candidate.snapshot.cohort) != _ordered_cohort(previous.candidate.snapshot.cohort)
            ):
                raise ValueError("updated state must preserve cohort membership")
            _validate_state_observations(state, request.replay)
        return self
