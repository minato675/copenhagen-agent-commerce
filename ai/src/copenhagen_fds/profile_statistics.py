"""Empirical statistics shared by profile builders; no inferred risk thresholds."""

import math
import statistics
from collections import Counter
from fractions import Fraction

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.personal_profiles import (
    CircularSummary, FeatureDistribution, HistogramBin, NumericSummary,
)
from copenhagen_fds.contracts.profiles import FeatureObservation


def sample_status(count: int, minimum: int) -> str:
    return "EMPTY" if count == 0 else ("READY" if count >= minimum else "INSUFFICIENT_SAMPLES")


def numeric_summary(values: tuple[int, ...]) -> NumericSummary:
    """Population standard deviation and linear interpolated empirical quantiles."""
    ordered = sorted(values)
    if not ordered:
        raise ValueError("numeric summary requires at least one observation")

    def quantile(percent):
        numerator = (len(ordered) - 1) * percent
        lower, remainder = divmod(numerator, 100)
        upper = min(lower + 1, len(ordered) - 1)
        return float(ordered[lower] + (ordered[upper] - ordered[lower]) * remainder / 100)

    try:
        median = statistics.median(ordered)
        return NumericSummary(
            minimum=float(ordered[0]), maximum=float(ordered[-1]),
            q05=quantile(5), q25=quantile(25), median=float(median),
            q75=quantile(75), q95=quantile(95), mean=float(statistics.mean(ordered)),
            population_stddev=float(statistics.pstdev(ordered)),
            median_absolute_deviation=float(statistics.median(abs(value - median) for value in ordered)),
        )
    except OverflowError:
        raise ValueError("observations exceed the finite statistics range") from None


def circular_summary(counts: tuple[int, ...]) -> CircularSummary:
    """Retain the histogram; a mean direction alone cannot describe multiple modes."""
    total = sum(counts)
    if not total:
        raise ValueError("circular summary requires at least one observation")
    period = len(counts)
    x = math.fsum(count * math.cos(math.tau * index / period) for index, count in enumerate(counts)) / total
    y = math.fsum(count * math.sin(math.tau * index / period) for index, count in enumerate(counts)) / total
    concentration = min(1.0, math.hypot(x, y))
    position = None
    if concentration > 1e-12:
        position = (math.atan2(y, x) / math.tau * period) % period
        if math.isclose(position, period, abs_tol=1e-12, rel_tol=0):
            position = 0.0
    return CircularSummary(period=period, mean_position=position, concentration=concentration)


def weighted_quantile(values, weights, probability: Fraction) -> float:
    """Inverse empirical CDF; exact rational weights keep boundary ties stable."""
    if not values or len(values) != len(weights) or any(weight <= 0 for weight in weights):
        raise ValueError("weighted quantiles require aligned observations and positive weights")
    if not 0 <= probability <= 1:
        raise ValueError("quantile probability must lie between zero and one")
    ordered = sorted(zip(values, weights))
    threshold = sum(weights) * probability
    cumulative = Fraction(0)
    for value, weight in ordered:
        cumulative += weight
        if cumulative >= threshold:
            return float(value)
    return float(ordered[-1][0])


def weighted_numeric_summary(values: tuple[int, ...], weights: tuple[Fraction, ...]) -> NumericSummary:
    """User-balanced empirical moments and inverse-CDF quantiles, without resampling."""
    if not values or len(values) != len(weights) or any(weight <= 0 for weight in weights):
        raise ValueError("weighted statistics require aligned observations and positive weights")
    total = sum(weights)
    mean = sum(value * weight for value, weight in zip(values, weights)) / total
    # Keep the median exact while computing deviations, including large integers.
    ordered = sorted(zip(values, weights))
    cumulative = Fraction(0)
    for median, weight in ordered:
        cumulative += weight
        if cumulative >= total / 2:
            break
    try:
        variance = sum(weight * (value - mean) ** 2 for value, weight in zip(values, weights)) / total
        return NumericSummary(
            minimum=float(min(values)), maximum=float(max(values)), mean=float(mean),
            q05=weighted_quantile(values, weights, Fraction(5, 100)),
            q25=weighted_quantile(values, weights, Fraction(25, 100)), median=float(median),
            q75=weighted_quantile(values, weights, Fraction(75, 100)),
            q95=weighted_quantile(values, weights, Fraction(95, 100)),
            population_stddev=math.sqrt(float(variance)),
            median_absolute_deviation=weighted_quantile(
                tuple(abs(value - median) for value in values), weights, Fraction(1, 2)),
        )
    except OverflowError:
        raise ValueError("observations exceed the finite statistics range") from None


def feature_distribution(feature_id, samples, *, as_of, min_samples) -> FeatureDistribution:
    """Summarize AVAILABLE values only; a missing observation never becomes zero."""
    observations, missing = [], Counter()
    for sample in samples:
        feature = next(feature for feature in sample.raw_features.features if feature.feature_id == feature_id)
        if feature.status == "AVAILABLE":
            observations.append((sample.purchase_occurred_at, feature.value))
        else:
            missing[feature.status] += 1
    values = tuple(value for _, value in observations)
    dates = [date for date, _ in observations]
    frequencies = Counter(values)
    period = {"kst_hour": 24, "kst_day_of_week": 7}.get(feature_id)
    bins = range(period) if period is not None else sorted(frequencies)
    histogram = tuple(HistogramBin(value=value, count=frequencies[value]) for value in bins)
    numeric = numeric_summary(values) if values and period is None else None
    circular = circular_summary(tuple(item.count for item in histogram)) if values and period is not None else None
    unit, seconds = FEATURE_SPECS[feature_id]
    return FeatureDistribution(
        feature_id=feature_id, unit=unit, window_seconds=seconds,
        status=sample_status(len(values), min_samples),
        observation=FeatureObservation(feature_id=feature_id, valid_sample_count=len(values),
                                       observed_from=min(dates) if dates else None,
                                       last_observed_at=max(dates) if dates else None),
        unavailable_count=sum(missing.values()), not_collected_count=missing["NOT_COLLECTED"],
        insufficient_history_count=missing["INSUFFICIENT_HISTORY"], not_applicable_count=missing["NOT_APPLICABLE"],
        recency_seconds=(as_of - max(dates)).total_seconds() if dates else None,
        numeric=numeric, circular=circular, histogram=histogram,
    )
