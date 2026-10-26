"""Point-in-time profile publications using the existing full-replay policy updater.

This is a sequential benchmark state machine, not a persistence service. Its
source reference identifies a dataset namespace, never a hash of future rows.
"""

from copy import deepcopy
from dataclasses import dataclass
from datetime import timedelta, timezone

from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.personalization_inputs import PublishedProfileInput, personalization_hash
from copenhagen_fds.contracts.population_baselines import PopulationBaselineRequest
from copenhagen_fds.contracts.population_profiles import CohortDefinition, PopulationProfileConfig, PopulationProfileRequest
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyUpdateRequest
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.population_profiles import build_population_profile
from copenhagen_fds.profile_policy_replay import policy_observations_from_replay
from copenhagen_fds.profile_policy_updates import initialize_profile_policy, update_profile_policy
from copenhagen_fds.synthetic.learning import OBSERVABLE_TABLES, synthetic_learning_inputs
from copenhagen_fds.synthetic.replay import instant


@dataclass(frozen=True)
class ProfilePublications:
    personal: PublishedProfileInput | None
    population: PublishedProfileInput | None
    population_baseline_request: PopulationBaselineRequest | None
    audit: dict


class ProfileTimeline:
    """Keep personal/currency-cohort states while evaluating first purchase arrivals.

    Every update is a complete observable replay, including withdrawals. Failed
    updates remain visible in the audit and supply no profile for that scope;
    they never trigger an implicit successful rebuild. A current-purchase
    exclusion or observed cohort change is an explicit, separately audited rebuild.
    """

    def __init__(self, observations: dict, settings: PersonalReplaySettings, source_reference: str,
                 *, population_config: PopulationProfileConfig | None = None):
        if not isinstance(source_reference, str) or not source_reference.strip():
            raise ValueError("profile timeline requires a stable dataset namespace")
        self._data = {name: deepcopy(observations[name]) for name in OBSERVABLE_TABLES}
        self.settings = PersonalReplaySettings.model_validate(settings.model_dump())
        self.source_reference = source_reference
        supplied = population_config or PopulationProfileConfig(
            recent_days=settings.profile.recent_days, long_term_days=settings.profile.long_term_days)
        self.population_config = PopulationProfileConfig.model_validate(supplied.model_dump())
        self._personal = {}
        self._population = {}
        self._last_cutoff = None

    def _users_at(self, cutoff):
        known = {}
        for user in self._data["users"]:
            if instant(user["registered_at"]) > cutoff:
                continue
            if known.setdefault(user["user_id"], user) != user:
                raise ValueError("observable user identity has conflicting contents")
        return known

    def _cohort(self, members, currency):
        identities = tuple(sorted(members))
        identity = personalization_hash((self.source_reference, currency))[:24]
        return CohortDefinition(cohort_id="temporal-cohort-" + identity,
            membership_version="temporal-members-" + personalization_hash(identities),
            definition="Observable registered users sharing one currency",
            source_reference=self.source_reference,
            available_at=max(instant(user["registered_at"]) for user in members.values()),
            currency=currency, member_user_ids=identities)

    def _new_state(self, observations, cutoff, currency, *, user_id=None, cohort=None):
        config = self.settings.profile if user_id is not None else self.population_config
        identity = personalization_hash((self.source_reference, currency, user_id,
                                         None if cohort is None else cohort.cohort_id))[:24]
        version = "temporal-profile-" + personalization_hash({
            "observations": observations, "as_of": cutoff, "config": config,
            "admission": self.settings.admission, "cohort": cohort,
            "ttl": self.settings.profile_valid_for_seconds,
        })
        common = dict(state_version=version, origin="SYNTHETIC", source_reference=self.source_reference,
            as_of=cutoff, generated_at=cutoff,
            valid_until=cutoff + timedelta(seconds=self.settings.profile_valid_for_seconds), config=config)
        samples = tuple(row.sample for row in observations)
        if user_id is not None:
            request = PersonalProfileRequest(**common, user_id=user_id, currency=currency,
                reference=dict(scope="PERSONAL", user_id=user_id,
                    profile_id="temporal-personal-" + identity, version=version))
            snapshot = build_personal_profile(request, samples)
        else:
            request = PopulationProfileRequest(**common, cohort=cohort,
                reference=dict(scope="POPULATION", cohort_id=cohort.cohort_id,
                    profile_id="temporal-population-" + identity, version=version))
            snapshot = build_population_profile(request, samples)
        return initialize_profile_policy(snapshot, observations, self.settings.admission)

    def _advance(self, previous, observations, cutoff, currency, current, *, user_id=None, cohort=None):
        reasons = []
        if previous is not None:
            if cohort is not None and previous.candidate.snapshot.cohort != cohort:
                reasons.append("COHORT_MEMBERSHIP_CHANGED")
            if any(row.sample.eligibility.target.purchase_request_id == current for row in previous.observations):
                reasons.append("CURRENT_PURCHASE_EXCLUSION")
        audit = dict(previous_state_version=None if previous is None else previous.state_version,
            cutoff=cutoff.isoformat(), current_purchase_request_id=current,
            observation_count=len(observations), rebuild_reasons=reasons)
        if previous is None or reasons:
            state = self._new_state(observations, cutoff, currency, user_id=user_id, cohort=cohort)
            audit.update(status="CREATED" if previous is None else "REBUILT", transitions=[], issue=None)
        else:
            profile = previous.candidate.snapshot.profile
            request = ProfilePolicyUpdateRequest(expected_policy_state_version=previous.state_version,
                update=dict(expected_state_version=previous.candidate.state_version,
                    expected_profile_reference=profile.reference, as_of=cutoff, generated_at=cutoff,
                    valid_until=cutoff + timedelta(seconds=self.settings.profile_valid_for_seconds),
                    changes_complete_from=profile.as_of, changes_complete_until=cutoff,
                    current_purchase_request_id=current))
            result = update_profile_policy(previous, request, observations)
            audit.update(status=result.status, issue=result.issue,
                transitions=[item.model_dump(mode="json") for item in result.transitions],
                candidate_events=[item.model_dump(mode="json") for item in result.candidate_events])
            if result.state is None:
                audit.update(published=False, state_version=previous.state_version,
                    profile_reference=None, state_sha256=None)
                return previous, None, audit
            state = result.state
        publication = PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
            expected_policy_state_version=state.state_version, available_at=cutoff)
        audit.update(published=True, state_version=state.state_version,
            profile_reference=state.published.profile.reference.model_dump(mode="json"),
            state_sha256=personalization_hash(state),
            retained_count=state.published.long_term.retained_count,
            included_count=state.published.long_term.included_count)
        return state, publication, audit

    def before(self, feature: FeatureTransformInput) -> ProfilePublications:
        """Publish only information available one microsecond before evaluation.

        At a later first-arrival evaluation the previous purchase may enter the
        complete replay, but the current purchase is excluded from both scopes.
        Same-time evaluations are allowed; time regression is rejected.
        """
        feature = FeatureTransformInput.model_validate(feature.model_dump())
        evaluation = feature.evaluation
        cutoff = evaluation.evaluated_at.astimezone(timezone.utc) - timedelta(microseconds=1)
        if self._last_cutoff is not None and cutoff < self._last_cutoff:
            raise ValueError("profile timeline evaluations must be chronological")
        users = self._users_at(cutoff)
        target, currency = evaluation.target, evaluation.request.order.total.currency
        if target.user_id not in users or users[target.user_id]["currency"] != currency:
            raise ValueError("evaluation requires an observable user with matching currency")
        members = {identity: user for identity, user in users.items() if user["currency"] == currency}
        data = {name: [row for row in rows if row["user_id"] in members] for name, rows in self._data.items()}
        inputs = synthetic_learning_inputs(data, as_of=cutoff,
                                           current_purchase_request_id=target.purchase_request_id)
        rows = policy_observations_from_replay(inputs, origin="SYNTHETIC", source_reference=self.source_reference)
        personal_rows = tuple(row for row in rows if row.sample.eligibility.target.user_id == target.user_id)
        personal_state, personal, personal_audit = self._advance(self._personal.get(target.user_id),
            personal_rows, cutoff, currency, target.purchase_request_id, user_id=target.user_id)
        cohort = self._cohort(members, currency)
        population_state, population, population_audit = self._advance(self._population.get(currency),
            rows, cutoff, currency, target.purchase_request_id, cohort=cohort)
        self._personal[target.user_id] = personal_state
        self._population[currency] = population_state
        self._last_cutoff = cutoff
        baseline = None if population is None else PopulationBaselineRequest(
            expected_source_reference=population.state.published.profile.reference,
            currency=currency, available_at=cutoff)
        return ProfilePublications(personal=personal, population=population,
            population_baseline_request=baseline, audit=dict(cutoff=cutoff.isoformat(),
                source_reference=self.source_reference, persistence="IN_MEMORY_ONLY",
                personal=personal_audit, population=population_audit))
