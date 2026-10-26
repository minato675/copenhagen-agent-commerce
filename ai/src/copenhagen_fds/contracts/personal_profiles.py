"""Fitted personal distributions and the bounded observations needed to rebuild them."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.profiles import BehaviorProfile, FeatureObservation, OriginValue, ProfileReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, CurrencyCode, Identifier, InputModel, Sha256


Count = Annotated[int, Field(ge=0)]
PositiveCount = Annotated[int, Field(gt=0)]
Finite = Annotated[float, Field(ge=0, allow_inf_nan=False)]
Probability = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]
SummaryStatus = Literal["EMPTY", "INSUFFICIENT_SAMPLES", "READY"]


class PersonalProfileConfig(InputModel):
    version: Identifier = "personal-statistics-config-v1"
    recent_days: Annotated[int, Field(gt=0, le=3650)] = 7
    long_term_days: Annotated[int, Field(gt=0, le=3650)] = 90
    min_samples: PositiveCount = 5
    timezone: Literal["Asia/Seoul"] = "Asia/Seoul"
    quantile_method: Literal["LINEAR_N_MINUS_ONE"] = "LINEAR_N_MINUS_ONE"

    @model_validator(mode="after")
    def validate_windows(self) -> Self:
        if self.recent_days > self.long_term_days:
            raise ValueError("recent window must fit within the retained long-term window")
        return self


class PersonalProfileRequest(InputModel):
    user_id: Identifier
    currency: CurrencyCode
    reference: ProfileReference
    state_version: Identifier
    origin: OriginValue
    source_reference: Identifier
    as_of: Timestamp
    generated_at: Timestamp
    valid_until: Timestamp
    config: PersonalProfileConfig = PersonalProfileConfig()

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if self.reference.scope != "PERSONAL" or self.reference.user_id != self.user_id:
            raise ValueError("personal profile reference must match the requested user")
        if not self.as_of <= self.generated_at < self.valid_until:
            raise ValueError("profile cutoff, generation and expiry must be ordered")
        return self


class NumericSummary(InputModel):
    minimum: Finite
    q05: Finite
    q25: Finite
    median: Finite
    q75: Finite
    q95: Finite
    maximum: Finite
    mean: Finite
    population_stddev: Finite
    median_absolute_deviation: Finite

    @model_validator(mode="after")
    def validate_range(self) -> Self:
        ordered = [self.minimum, self.q05, self.q25, self.median, self.q75, self.q95, self.maximum]
        if ordered != sorted(ordered) or not self.minimum <= self.mean <= self.maximum:
            raise ValueError("numeric summary must preserve its range and quantile order")
        return self


class HistogramBin(InputModel):
    value: Count
    count: Count


class CircularSummary(InputModel):
    period: Literal[7, 24]
    mean_position: Finite | None
    concentration: Probability

    @model_validator(mode="after")
    def validate_mean(self) -> Self:
        if self.mean_position is not None and self.mean_position >= self.period:
            raise ValueError("circular mean must lie inside its period")
        if (self.concentration <= 1e-12) != (self.mean_position is None):
            raise ValueError("a directionless circular distribution has no mean position")
        return self


class FeatureDistribution(InputModel):
    feature_id: Identifier
    unit: Identifier
    window_seconds: PositiveCount | None
    status: SummaryStatus
    observation: FeatureObservation
    unavailable_count: Count
    not_collected_count: Count
    insufficient_history_count: Count
    not_applicable_count: Count
    recency_seconds: Finite | None
    numeric: NumericSummary | None = None
    circular: CircularSummary | None = None
    histogram: Array[HistogramBin] = ()

    @model_validator(mode="after")
    def validate_distribution(self) -> Self:
        if FEATURE_SPECS.get(self.feature_id) != (self.unit, self.window_seconds):
            raise ValueError("distribution must use the raw feature unit and window")
        if self.observation.feature_id != self.feature_id:
            raise ValueError("observation must match its distribution")
        n = self.observation.valid_sample_count
        if (n == 0) != (self.status == "EMPTY") or (n == 0) != (self.recency_seconds is None):
            raise ValueError("empty distribution must have no observations or recency")
        if self.unavailable_count != self.not_collected_count + self.insufficient_history_count + self.not_applicable_count:
            raise ValueError("unavailable count must preserve each missingness reason")
        period = {"kst_hour": 24, "kst_day_of_week": 7}.get(self.feature_id)
        values = [item.value for item in self.histogram]
        if values != sorted(set(values)) or sum(item.count for item in self.histogram) != n:
            raise ValueError("histogram must have ordered unique bins and the observed count")
        if period is not None:
            if values != list(range(period)) or self.numeric is not None:
                raise ValueError("time distribution must retain every circular bin, not a linear average")
            if (n > 0) != (self.circular is not None):
                raise ValueError("nonempty time distribution requires circular statistics")
            if self.circular is not None and self.circular.period != period:
                raise ValueError("circular summary must match the feature period")
        elif (n > 0) != (self.numeric is not None) or self.circular is not None:
            raise ValueError("nonempty numeric distribution requires numeric statistics")
        return self


class ProfileWindow(InputModel):
    start_inclusive: Timestamp
    end_exclusive: Timestamp
    status: SummaryStatus
    retained_count: Count
    included_count: Count
    deferred_count: Count
    excluded_count: Count
    features: Array[FeatureDistribution]

    @model_validator(mode="after")
    def validate_window(self) -> Self:
        if self.start_inclusive >= self.end_exclusive:
            raise ValueError("profile window must be ordered")
        if self.retained_count != self.included_count + self.deferred_count + self.excluded_count:
            raise ValueError("window counts must preserve every eligibility state")
        if (self.included_count == 0) != (self.status == "EMPTY"):
            raise ValueError("window emptiness must match included purchases")
        if [f.feature_id for f in self.features] != list(FEATURE_SPECS):
            raise ValueError("profile window requires the complete ordered feature schema")
        for feature in self.features:
            observation = feature.observation
            if observation.valid_sample_count + feature.unavailable_count != self.included_count:
                raise ValueError("feature sample counts must account for all included purchases")
            if observation.observed_from is not None and not (
                self.start_inclusive <= observation.observed_from <= observation.last_observed_at < self.end_exclusive
            ):
                raise ValueError("observation timestamps must lie within the profile window")
            if observation.last_observed_at is not None and feature.recency_seconds != (
                self.end_exclusive - observation.last_observed_at
            ).total_seconds():
                raise ValueError("feature recency must match the information cutoff")
        return self


class PersonalStatisticsState(InputModel):
    schema_version: Literal["personal-statistics-state-v1"] = "personal-statistics-state-v1"
    state_version: Identifier
    profile_reference: ProfileReference
    # Include withheld/excluded records too so later corrections can be replayed.
    retained_samples: Array[LearningSample]


class PersonalProfileSnapshot(InputModel):
    schema_version: Literal["personal-statistical-profile-v1"] = "personal-statistical-profile-v1"
    algorithm_version: Literal["empirical-personal-v1"] = "empirical-personal-v1"
    profile: BehaviorProfile
    config: PersonalProfileConfig
    config_sha256: Sha256
    input_sha256: Sha256
    recent: ProfileWindow
    long_term: ProfileWindow
    state: PersonalStatisticsState

    @model_validator(mode="after")
    def validate_snapshot(self) -> Self:
        profile = self.profile
        if profile.reference.scope != "PERSONAL" or self.state.profile_reference != profile.reference:
            raise ValueError("snapshot must preserve its personal profile in the learning state")
        for days, window in ((self.config.recent_days, self.recent), (self.config.long_term_days, self.long_term)):
            if window.end_exclusive != profile.as_of or (window.end_exclusive - window.start_inclusive).total_seconds() != days * 86400:
                raise ValueError("profile statistics must match configured time windows")
            expected = "EMPTY" if window.included_count == 0 else (
                "READY" if window.included_count >= self.config.min_samples else "INSUFFICIENT_SAMPLES"
            )
            if window.status != expected:
                raise ValueError("window status must match the configured sample threshold")
            for feature in window.features:
                n = feature.observation.valid_sample_count
                expected = "EMPTY" if n == 0 else ("READY" if n >= self.config.min_samples else "INSUFFICIENT_SAMPLES")
                if feature.status != expected:
                    raise ValueError("feature status must match its own valid sample count")
        if profile.observations != tuple(feature.observation for feature in self.long_term.features):
            raise ValueError("profile metadata must preserve long-term feature observations")
        samples = self.state.retained_samples
        identities = [sample.eligibility.target.purchase_request_id for sample in samples]
        if len(set(identities)) != len(identities):
            raise ValueError("statistical state permits one sample per purchase")
        if len(samples) != self.long_term.retained_count:
            raise ValueError("retained samples must account for the long-term window")
        for sample in samples:
            if (sample.eligibility.target.user_id, sample.eligibility.target.currency) != (profile.reference.user_id, profile.currency):
                raise ValueError("retained sample must match the profile owner and currency")
            if (sample.origin, sample.source_reference, sample.eligibility.as_of) != (profile.origin, profile.source_reference, profile.as_of):
                raise ValueError("retained sample provenance and cutoff must match the profile")
            if not self.long_term.start_inclusive <= sample.purchase_occurred_at < profile.as_of:
                raise ValueError("retained purchase must lie inside the long-term window")
        return self
