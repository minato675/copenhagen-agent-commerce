"""Explicit cohort membership, weighted distributions and retained population state."""

import math
from collections import Counter
from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.features import FEATURE_SCHEMA_VERSION
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_profiles import (
    Count, FeatureDistribution, Finite, HistogramBin, PersonalProfileConfig,
    PositiveCount, Probability, ProfileWindow,
)
from copenhagen_fds.contracts.profiles import BehaviorProfile, OriginValue, ProfileReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, CurrencyCode, Identifier, InputModel, Sha256, Text


class CohortDefinition(InputModel):
    cohort_id: Identifier
    membership_version: Identifier
    definition: Text
    source_reference: Identifier
    available_at: Timestamp
    currency: CurrencyCode
    member_user_ids: Array[Identifier]

    @model_validator(mode="after")
    def validate_members(self) -> Self:
        if len(set(self.member_user_ids)) != len(self.member_user_ids):
            raise ValueError("cohort membership must contain unique users")
        return self


class PopulationProfileConfig(PersonalProfileConfig):
    version: Identifier = "population-statistics-config-v1"
    min_samples: PositiveCount = 20
    min_users: PositiveCount = 3
    aggregation: Literal["TRANSACTION", "USER_EQUAL"] = "USER_EQUAL"
    weighted_quantile_method: Literal["INVERTED_CDF"] = "INVERTED_CDF"


class PopulationProfileRequest(InputModel):
    cohort: CohortDefinition
    reference: ProfileReference
    state_version: Identifier
    origin: OriginValue
    source_reference: Identifier
    as_of: Timestamp
    generated_at: Timestamp
    valid_until: Timestamp
    config: PopulationProfileConfig = PopulationProfileConfig()

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if self.reference.scope != "POPULATION" or self.reference.cohort_id != self.cohort.cohort_id:
            raise ValueError("population reference must match the explicitly supplied cohort")
        if not self.cohort.available_at <= self.as_of <= self.generated_at < self.valid_until:
            raise ValueError("membership availability, cutoff, generation and expiry must be ordered")
        return self


class UserContribution(InputModel):
    user_id: Identifier
    valid_sample_count: PositiveCount
    total_weight: Probability


class WeightedHistogramBin(HistogramBin):
    probability: Probability


class PopulationFeatureDistribution(FeatureDistribution):
    histogram: Array[WeightedHistogramBin] = ()
    contributors: Array[UserContribution]
    weight_effective_sample_size: Finite

    @model_validator(mode="after")
    def validate_weight_support(self) -> Self:
        users = [item.user_id for item in self.contributors]
        n = self.observation.valid_sample_count
        if users != sorted(set(users)) or sum(item.valid_sample_count for item in self.contributors) != n:
            raise ValueError("feature contributors must preserve unique users and actual sample counts")
        expected_mass = 1.0 if n else 0.0
        for total in (math.fsum(item.total_weight for item in self.contributors),
                      math.fsum(item.probability for item in self.histogram)):
            if not math.isclose(total, expected_mass, abs_tol=1e-12, rel_tol=0):
                raise ValueError("nonempty feature weights must sum to one")
        if any((item.count == 0) != (item.probability == 0) for item in self.histogram):
            raise ValueError("histogram mass must preserve observed support")
        if any(item.total_weight <= 0 for item in self.contributors):
            raise ValueError("observed users require positive weights")
        inverse = math.fsum(item.total_weight ** 2 / item.valid_sample_count for item in self.contributors)
        expected = 1 / inverse if inverse else 0.0
        if not math.isclose(self.weight_effective_sample_size, expected, rel_tol=1e-12, abs_tol=1e-12):
            raise ValueError("effective sample size must match per-purchase weights")
        return self


class MemberSupport(InputModel):
    user_id: Identifier
    included_count: Count
    deferred_count: Count
    excluded_count: Count


class PopulationWindow(ProfileWindow):
    features: Array[PopulationFeatureDistribution]
    members: Array[MemberSupport]

    @model_validator(mode="after")
    def validate_member_support(self) -> Self:
        users = [member.user_id for member in self.members]
        if users != sorted(set(users)):
            raise ValueError("window members must be ordered and unique")
        for field in ("included_count", "deferred_count", "excluded_count"):
            if sum(getattr(member, field) for member in self.members) != getattr(self, field):
                raise ValueError("window totals must equal member totals")
        by_user = {member.user_id: member.included_count for member in self.members}
        for feature in self.features:
            if any(item.valid_sample_count > by_user.get(item.user_id, 0) for item in feature.contributors):
                raise ValueError("feature support must belong to included cohort members")
        return self


def population_status(samples: int, users: int, config: PopulationProfileConfig) -> str:
    if samples == 0:
        return "EMPTY"
    return "READY" if samples >= config.min_samples and users >= config.min_users else "INSUFFICIENT_SAMPLES"


class PopulationStatisticsState(InputModel):
    schema_version: Literal["population-statistics-state-v1"] = "population-statistics-state-v1"
    state_version: Identifier
    profile_reference: ProfileReference
    retained_samples: Array[LearningSample]


class PopulationProfileSnapshot(InputModel):
    schema_version: Literal["population-statistical-profile-v1"] = "population-statistical-profile-v1"
    algorithm_version: Literal["empirical-population-v1"] = "empirical-population-v1"
    cohort: CohortDefinition
    profile: BehaviorProfile
    config: PopulationProfileConfig
    config_sha256: Sha256
    input_sha256: Sha256
    cohort_sha256: Sha256
    recent: PopulationWindow
    long_term: PopulationWindow
    state: PopulationStatisticsState

    @model_validator(mode="after")
    def validate_snapshot(self) -> Self:
        profile, cohort = self.profile, self.cohort
        if profile.reference.scope != "POPULATION" or profile.reference.cohort_id != cohort.cohort_id:
            raise ValueError("snapshot must reference its population cohort")
        if self.state.profile_reference != profile.reference or profile.currency != cohort.currency:
            raise ValueError("state reference and currency must match the population profile")
        if profile.feature_schema_version != FEATURE_SCHEMA_VERSION or cohort.available_at > profile.as_of:
            raise ValueError("snapshot requires the supported feature schema and known membership")
        samples = self.state.retained_samples
        identities = [sample.eligibility.target.purchase_request_id for sample in samples]
        if len(set(identities)) != len(identities) or len(samples) != self.long_term.retained_count:
            raise ValueError("population state must retain one bounded sample per purchase")
        for sample in samples:
            target = sample.eligibility.target
            if target.user_id not in cohort.member_user_ids or target.currency != cohort.currency:
                raise ValueError("retained purchase must match cohort membership and currency")
            if (sample.origin, sample.source_reference, sample.eligibility.as_of) != (
                profile.origin, profile.source_reference, profile.as_of,
            ) or not self.long_term.start_inclusive <= sample.purchase_occurred_at < profile.as_of:
                raise ValueError("retained purchase must match source, cutoff and retention window")
        for days, window in ((self.config.recent_days, self.recent), (self.config.long_term_days, self.long_term)):
            if window.end_exclusive != profile.as_of or (window.end_exclusive - window.start_inclusive).total_seconds() != days * 86400:
                raise ValueError("population windows must match configured durations and cutoff")
            if tuple(member.user_id for member in window.members) != tuple(sorted(cohort.member_user_ids)):
                raise ValueError("each window must account for all provided members, including empty users")
            counts = Counter((sample.eligibility.target.user_id, sample.eligibility.status) for sample in samples
                             if window.start_inclusive <= sample.purchase_occurred_at)
            for member in window.members:
                if (member.included_count, member.deferred_count, member.excluded_count) != tuple(
                    counts[member.user_id, status] for status in ("INCLUDE", "DEFER", "EXCLUDE")
                ):
                    raise ValueError("member counts must preserve retained eligibility decisions")
            if window.status != population_status(window.included_count, sum(m.included_count > 0 for m in window.members), self.config):
                raise ValueError("window status must honor sample and contributor thresholds")
            for feature in window.features:
                n, users = feature.observation.valid_sample_count, len(feature.contributors)
                if feature.status != population_status(n, users, self.config):
                    raise ValueError("each feature must honor its own sample and contributor thresholds")
                for item in feature.contributors:
                    expected = 1 / users if self.config.aggregation == "USER_EQUAL" else item.valid_sample_count / n
                    if not math.isclose(item.total_weight, expected, rel_tol=1e-12):
                        raise ValueError("contributor weights must match the configured aggregation")
        if profile.observations != tuple(feature.observation for feature in self.long_term.features):
            raise ValueError("population metadata must preserve long-term feature observations")
        return self
