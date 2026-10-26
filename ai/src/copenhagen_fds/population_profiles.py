"""Fit explicitly provided cohorts from observable, normal-confirmed purchases."""

from collections import Counter, defaultdict
from datetime import timedelta, timezone
from fractions import Fraction

from copenhagen_fds.contracts.features import FEATURE_SCHEMA_VERSION, FEATURE_SPECS
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.population_profiles import (
    MemberSupport, PopulationFeatureDistribution, PopulationProfileRequest,
    PopulationProfileSnapshot, PopulationStatisticsState, PopulationWindow,
    UserContribution, WeightedHistogramBin, population_status,
)
from copenhagen_fds.contracts.profiles import BehaviorProfile
from copenhagen_fds.personal_profiles import canonical_sample, content_hash
from copenhagen_fds.profile_statistics import circular_summary, feature_distribution, weighted_numeric_summary


def _distribution(feature_id, samples, request):
    config = request.config
    raw = feature_distribution(feature_id, samples, as_of=request.as_of, min_samples=config.min_samples)
    observations = []
    for sample in samples:
        feature = next(item for item in sample.raw_features.features if item.feature_id == feature_id)
        if feature.status == "AVAILABLE":
            observations.append((sample.eligibility.target.user_id, feature.value))
    counts = Counter(user for user, _ in observations)
    n, users = len(observations), len(counts)
    per_sample = {user: Fraction(1, users * count) if config.aggregation == "USER_EQUAL" else Fraction(1, n)
                  for user, count in counts.items()}
    mass = defaultdict(Fraction)
    for user, value in observations:
        mass[value] += per_sample[user]
    histogram = tuple(WeightedHistogramBin(value=item.value, count=item.count, probability=float(mass[item.value]))
                      for item in raw.histogram)
    numeric, circular = raw.numeric, raw.circular
    if n and config.aggregation == "USER_EQUAL":
        if raw.numeric is not None:
            numeric = weighted_numeric_summary(tuple(value for _, value in observations),
                                                tuple(per_sample[user] for user, _ in observations))
        else:
            circular = circular_summary(tuple(item.probability for item in histogram))
    inverse = sum(count * per_sample[user] ** 2 for user, count in counts.items())
    data = raw.model_dump(mode="python")
    data.update(
        status=population_status(n, users, config), numeric=numeric, circular=circular, histogram=histogram,
        contributors=tuple(UserContribution(user_id=user, valid_sample_count=counts[user],
                                           total_weight=float(counts[user] * per_sample[user])) for user in sorted(counts)),
        weight_effective_sample_size=float(1 / inverse) if inverse else 0.0,
    )
    return PopulationFeatureDistribution.model_validate(data)


def _window(samples, request, days):
    lower = request.as_of - timedelta(days=days)
    selected = tuple(sample for sample in samples if lower <= sample.purchase_occurred_at < request.as_of)
    counts = Counter(sample.eligibility.status for sample in selected)
    user_counts = Counter((sample.eligibility.target.user_id, sample.eligibility.status) for sample in selected)
    included = tuple(sample for sample in selected if sample.eligibility.status == "INCLUDE")
    members = tuple(MemberSupport(user_id=user, included_count=user_counts[user, "INCLUDE"],
                                  deferred_count=user_counts[user, "DEFER"], excluded_count=user_counts[user, "EXCLUDE"])
                    for user in request.cohort.member_user_ids)
    return PopulationWindow(
        start_inclusive=lower, end_exclusive=request.as_of,
        status=population_status(len(included), sum(item.included_count > 0 for item in members), request.config),
        retained_count=len(selected), included_count=counts["INCLUDE"], deferred_count=counts["DEFER"],
        excluded_count=counts["EXCLUDE"], members=members,
        features=tuple(_distribution(feature_id, included, request) for feature_id in FEATURE_SPECS),
    )


def build_population_profile(
    request: PopulationProfileRequest, samples: tuple[LearningSample, ...],
) -> PopulationProfileSnapshot:
    """No inferred membership, currency conversion, external lookup or risk scoring."""
    data = request.model_dump(mode="python")
    for key in ("as_of", "generated_at", "valid_until"):
        data[key] = data[key].astimezone(timezone.utc)
    data["cohort"]["available_at"] = data["cohort"]["available_at"].astimezone(timezone.utc)
    data["cohort"]["member_user_ids"] = sorted(data["cohort"]["member_user_ids"])
    request = PopulationProfileRequest.model_validate(data)
    lower = request.as_of - timedelta(days=request.config.long_term_days)
    users, selected = set(request.cohort.member_user_ids), {}
    for sample in samples:
        target = sample.eligibility.target
        if target.user_id not in users or target.currency != request.cohort.currency:
            continue
        if (sample.origin, sample.source_reference, sample.eligibility.as_of) != (
            request.origin, request.source_reference, request.as_of,
        ):
            raise ValueError("population samples require matching provenance and learning cutoff")
        if not lower <= sample.purchase_occurred_at < request.as_of:
            continue
        canonical = canonical_sample(sample)
        previous = selected.setdefault(target.purchase_request_id, canonical)
        if previous != canonical:
            raise ValueError("conflicting purchase samples require upstream correction replay")
    retained = tuple(sorted(selected.values(), key=lambda sample: (
        sample.purchase_occurred_at, sample.eligibility.target.user_id, sample.eligibility.target.purchase_request_id,
    )))
    recent = _window(retained, request, request.config.recent_days)
    long_term = _window(retained, request, request.config.long_term_days)
    profile = BehaviorProfile(
        reference=request.reference, currency=request.cohort.currency, origin=request.origin,
        source_reference=request.source_reference, feature_schema_version=FEATURE_SCHEMA_VERSION,
        as_of=request.as_of, generated_at=request.generated_at, valid_until=request.valid_until,
        observations=tuple(feature.observation for feature in long_term.features),
    )
    return PopulationProfileSnapshot(
        cohort=request.cohort, profile=profile, config=request.config,
        config_sha256=content_hash(request.config.model_dump(mode="json")),
        input_sha256=content_hash([sample.model_dump(mode="json") for sample in retained]),
        cohort_sha256=content_hash(request.cohort.model_dump(mode="json")),
        recent=recent, long_term=long_term,
        state=PopulationStatisticsState(state_version=request.state_version, profile_reference=request.reference,
                                        retained_samples=retained),
    )
