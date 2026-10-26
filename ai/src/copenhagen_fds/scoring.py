"""Deterministic per-feature deviations; neither fraud probabilities nor weights."""

from fractions import Fraction
from math import nextafter

from copenhagen_fds.contracts.baselines import FeatureReference, parameter_error
from copenhagen_fds.contracts.behavior import AnomalyScore
from copenhagen_fds.contracts.features import RawFeatureValue


SCORING_VERSION = "cold-distance-linear-v1"


def score_feature(raw: RawFeatureValue, reference: FeatureReference) -> AnomalyScore:
    """Return 100 * min(distance / scale, 1) for one available raw feature.

    Scale is a configured distance to saturation, not an inferred standard
    deviation. Missing data and invalid contracts raise; the evaluation mapper
    handles unavailable data separately from calculation failures.
    """
    raw = RawFeatureValue.model_validate(raw.model_dump())
    reference = FeatureReference.model_validate(reference.model_dump())
    if raw.status != "AVAILABLE":
        raise ValueError("scoring requires an available raw feature")
    if raw.feature_id != reference.feature_id or parameter_error((reference,)):
        raise ValueError("reference must match the feature, unit, window and method")

    # Preserve integer amounts/counts and the decimal representation of supplied
    # parameters. Compute the bounded ratio before conversion to binary float so
    # very large amounts and very small scales cannot overflow intermediate math.
    delta = Fraction(raw.value) - Fraction(str(reference.center))
    if reference.method == "UPPER_DISTANCE":
        distance = max(delta, Fraction(0))
    elif reference.method == "ABSOLUTE_DISTANCE":
        distance = abs(delta)
    else:
        distance = abs(delta) % reference.period
        distance = min(distance, reference.period - distance)
    ratio = min(distance / Fraction(str(reference.scale)), Fraction(1))
    value = float(100 * ratio)
    if ratio > 0 and value == 0:
        # A positive deviation must not turn into WITHIN_REFERENCE via underflow.
        value = nextafter(0.0, 1.0)
    return AnomalyScore(value=value, unit="BEHAVIOR_ANOMALY_0_100")
