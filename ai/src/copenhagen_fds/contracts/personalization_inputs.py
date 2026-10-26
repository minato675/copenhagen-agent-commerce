"""Pinned, observable published profiles for read-only personal feature evaluation."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.personalization_config import PersonalizationConfig
from copenhagen_fds.contracts.population_baselines import PopulationBaselineResult
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState
from copenhagen_fds.contracts.profiles import BehaviorProfile, ProfileReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.scoring import ColdScoringInput
from copenhagen_fds.contracts.values import Identifier, InputModel, Sha256
from copenhagen_fds.personal_profiles import content_hash
from copenhagen_fds.population_baselines import derive_population_baseline
from copenhagen_fds.profile_update_records import normalized_data


def personalization_hash(value) -> str:
    """Hash exact supplied content with equivalent timestamps normalized to UTC."""
    return content_hash(normalized_data(value))


class PublishedProfileContext(InputModel):
    profile: BehaviorProfile
    policy_state_version: Identifier
    policy_version: Identifier
    policy_config_sha256: Sha256
    state_sha256: Sha256
    available_at: Timestamp

    @model_validator(mode="after")
    def validate_availability(self) -> Self:
        if self.available_at < self.profile.generated_at:
            raise ValueError("published profile cannot be available before generation")
        return self


class PublishedProfileInput(InputModel):
    """No standalone candidate/statistical snapshot can substitute for policy output."""

    state: ProfilePolicyState
    expected_reference: ProfileReference
    expected_policy_state_version: Identifier
    available_at: Timestamp

    @model_validator(mode="after")
    def validate_publication(self) -> Self:
        state, profile = self.state, self.state.published.profile
        if profile.reference != self.expected_reference or state.state_version != self.expected_policy_state_version:
            raise ValueError("published profile and policy state must match their pinned versions")
        if self.available_at < max(profile.generated_at, state.candidate.snapshot.profile.generated_at):
            raise ValueError("policy output cannot be available before generation")
        if state.evaluation.config_sha256 != personalization_hash(state.evaluation.config):
            raise ValueError("policy configuration must match its recorded content hash")
        return self

    def context(self) -> PublishedProfileContext:
        return PublishedProfileContext(
            profile=self.state.published.profile,
            policy_state_version=self.state.state_version,
            policy_version=self.state.evaluation.policy_version,
            policy_config_sha256=self.state.evaluation.config_sha256,
            state_sha256=personalization_hash(self.state), available_at=self.available_at,
        )


class ProfileAvailability(InputModel):
    scope: Literal["PERSONAL", "POPULATION"]
    status: Literal["NOT_SUPPLIED", "AVAILABLE", "EXPIRED"]
    context: PublishedProfileContext | None = None

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if (self.status == "NOT_SUPPLIED") != (self.context is None):
            raise ValueError("only a supplied profile may carry publication evidence")
        if self.context is not None and self.context.profile.reference.scope != self.scope:
            raise ValueError("profile availability must preserve its scope")
        return self


class PersonalizedScoringInput(InputModel):
    schema_version: Literal["personalized-scoring-input-v1"] = "personalized-scoring-input-v1"
    cold: ColdScoringInput
    config: PersonalizationConfig
    personal: PublishedProfileInput | None = None
    population: PublishedProfileInput | None = None
    population_baseline: PopulationBaselineResult | None = None

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        evaluation = self.cold.request.evaluation
        configured = {item.feature_id for item in self.config.features}
        if not set(evaluation.requested_features).issubset(configured):
            raise ValueError("personalization settings must cover every requested feature")
        amount = next((f for f in self.cold.raw_features.features if f.feature_id == "amount_minor"), None)
        if amount is not None and amount.status == "AVAILABLE" and amount.value != evaluation.request.order.total.amount_minor:
            raise ValueError("raw amount must preserve the evaluated order total")
        for scope, supplied in (("PERSONAL", self.personal), ("POPULATION", self.population)):
            if supplied is None:
                continue
            profile = supplied.state.published.profile
            if profile.reference.scope != scope:
                raise ValueError("published profile must occupy its declared input scope")
            if scope == "PERSONAL" and profile.reference.user_id != evaluation.target.user_id:
                raise ValueError("personal profile must belong to the evaluated user")
            if profile.currency != evaluation.request.order.total.currency:
                raise ValueError("published profile must match evaluation currency")
            if profile.feature_schema_version != evaluation.feature_schema_version:
                raise ValueError("published profile must match the feature schema")
            if profile.as_of >= evaluation.evaluated_at or supplied.available_at > evaluation.evaluated_at:
                raise ValueError("evaluation requires a prior cutoff and already available publication")
            # Check candidates as well: even a withheld current purchase can affect policy replay.
            if any(row.sample.eligibility.target.purchase_request_id == evaluation.target.purchase_request_id
                   for row in supplied.state.observations):
                raise ValueError("current purchase must be absent from the supplied policy history")
        self._validate_population_derivation()
        for profile in evaluation.profiles:
            if profile.reference.scope == "PERSONAL" and (
                self.personal is None or profile != self.personal.state.published.profile
            ):
                raise ValueError("personal metadata must refer to the supplied published snapshot")
        return self

    def _validate_population_derivation(self) -> None:
        derived = self.population_baseline
        if derived is None:
            if self.population is not None and self.cold.request.population_reference is not None:
                raise ValueError("learned population and Cold linkage require the explicit derivation artifact")
            return
        if self.population is None:
            raise ValueError("population derivation requires its published source and policy evidence")
        if not self.population.available_at <= derived.request.available_at <= self.cold.request.evaluation.evaluated_at:
            raise ValueError("derived baseline must be available after publication and before evaluation")
        expected = derive_population_baseline(self.population.state.published, derived.request)
        if derived != expected or derived.issue is not None:
            raise ValueError("population derivation must reproduce from the exact published source")
        reference = derived.baseline.profile.reference if derived.baseline is not None else None
        if self.cold.request.population_reference != reference:
            raise ValueError("Cold must pin the derived baseline identity, not the statistical identity")
        for selected in self.cold.selection.features:
            if selected.basis is None or selected.basis.profile is None:
                continue
            baseline = derived.baseline
            parameter = next((p for p in baseline.parameters if p.feature_id == selected.feature_id), None) if baseline else None
            if baseline is None or selected.basis.profile != reference or selected.parameter != parameter:
                raise ValueError("selected population parameters must match the supplied derivation")
        if any(derived.baseline is None or p != derived.baseline.profile for p in self.cold.selection.profiles):
            raise ValueError("selected population metadata must match its derived baseline")

    def profile_availability(self) -> tuple[ProfileAvailability, ...]:
        at = self.cold.request.evaluation.evaluated_at
        result = []
        for scope, supplied in (("PERSONAL", self.personal), ("POPULATION", self.population)):
            status = "NOT_SUPPLIED" if supplied is None else (
                "EXPIRED" if supplied.state.published.profile.valid_until <= at else "AVAILABLE"
            )
            result.append(ProfileAvailability(scope=scope, status=status,
                                              context=supplied.context() if supplied else None))
        return tuple(result)
