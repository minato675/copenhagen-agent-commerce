"""Exact empirical arithmetic shared by numeric personal feature scorers."""

from collections import Counter
from fractions import Fraction
from math import nextafter
from typing import NamedTuple

from copenhagen_fds.contracts.behavior import AnomalyScore


class NumericSpread(NamedTuple):
    median: Fraction
    q95: Fraction
    iqr: Fraction
    mad: Fraction
    zero_spread: bool


def counted_quantile(histogram, percent: int) -> Fraction:
    """LINEAR_N_MINUS_ONE over validated counted values, without expanding counts."""
    bins = sorted((Fraction(value), count) for value, count in histogram if count)
    total = sum(count for _, count in bins)
    if not total or not 0 <= percent <= 100:
        raise ValueError("quantile requires observations and a percentile in range")
    lower, remainder = divmod((total - 1) * percent, 100)
    upper = min(lower + 1, total - 1)
    cumulative, left = 0, None
    for value, count in bins:
        cumulative += count
        if left is None and lower < cumulative:
            left = value
        if upper < cumulative:
            return left + (value - left) * Fraction(remainder, 100)
    raise ValueError("histogram does not cover quantile ranks")


def numeric_spread(histogram) -> NumericSpread:
    histogram = tuple((value, count) for value, count in histogram if count)
    median = counted_quantile(histogram, 50)
    deviations = Counter()
    for value, count in histogram:
        deviations[abs(value - median)] += count
    return NumericSpread(median, counted_quantile(histogram, 95),
        counted_quantile(histogram, 75) - counted_quantile(histogram, 25),
        counted_quantile(tuple(deviations.items()), 50), len(histogram) == 1)


def saturation_distance(spread: NumericSpread, *, relative_floor, minimum, multiplier) -> Fraction:
    base = max(spread.q95 - spread.median, spread.iqr, spread.mad,
               spread.median * Fraction(str(relative_floor)), Fraction(minimum))
    return base * Fraction(str(multiplier))


def bounded_upper_score(value: int, center: Fraction, distance: Fraction) -> AnomalyScore:
    delta = max(Fraction(value) - center, Fraction(0))
    ratio = min(delta / distance, Fraction(1))
    score = float(100 * ratio)
    if ratio > 0 and score == 0:
        score = nextafter(0.0, 1.0)
    return AnomalyScore(value=score, unit="BEHAVIOR_ANOMALY_0_100")
