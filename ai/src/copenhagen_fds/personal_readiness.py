"""Weakest-axis personal support from published observations and source coverage."""

from datetime import timedelta
from fractions import Fraction
from math import nextafter

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.personal_readiness import (
    READINESS_VERSION, FeatureReadiness, PersonalReadinessResult, ReadinessFactors,
    ReadinessMeasures, WindowReadiness,
)
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.personalization_results import personal_feature_evidence


def _unit_float(value: Fraction) -> float:
    """Keep exact endpoints distinct from a rounded open transition interval."""
    number = float(value)
    if value > 0 and number == 0:
        return nextafter(0.0, 1.0)
    if value < 1 and number == 1:
        return nextafter(1.0, 0.0)
    return number


def _ramp(value, lower, upper):
    if value is None:
        return None
    value, lower, upper = (v if isinstance(v, Fraction) else Fraction(str(v)) for v in (value, lower, upper))
    return min(Fraction(1), max(Fraction(0), (value - lower) / (upper - lower)))


def _coverage(supplied, evidence):
    """Check each AVAILABLE count's historical evaluation window, not a continuous calendar span."""
    dist = evidence.distribution
    if dist.window_seconds is None:
        return "NOT_REQUIRED", None, None
    if dist.observation.valid_sample_count == 0:
        return "UNAVAILABLE", 0, 0
    checked = supported = 0
    for sample in supplied.personal.state.published.state.retained_samples:
        if sample.eligibility.status != "INCLUDE" or not (
            evidence.start_inclusive <= sample.purchase_occurred_at < evidence.end_exclusive
        ):
            continue
        raw = next(f for f in sample.raw_features.features if f.feature_id == dist.feature_id)
        if raw.status != "AVAILABLE":
            continue
        checked += 1
        stream = sample.purchase_coverage if dist.unit == "PURCHASE_COUNT" else sample.attempt_coverage
        at = sample.raw_features.evaluated_at
        if stream.status == "COLLECTED" and stream.complete_from <= at - timedelta(seconds=dist.window_seconds) and stream.complete_until >= at:
            supported += 1
    if checked != dist.observation.valid_sample_count:
        raise ValueError("frequency readiness samples must match the published distribution")
    return "COMPLETE" if checked == supported else "INCOMPLETE", checked, supported


def _window(supplied, name, evidence, availability, thresholds, minimum, full):
    if evidence is None:
        return WindowReadiness(window=name, evidence=None,
            measures=ReadinessMeasures(**{f: None for f in ReadinessMeasures.model_fields}),
            factors=ReadinessFactors(**{f: None for f in ReadinessFactors.model_fields}),
            coverage_status="UNAVAILABLE", personal_weight=0.0, reasons=["PROFILE_NOT_SUPPLIED"])
    dist, observed = evidence.distribution, evidence.distribution.observation
    n, total = observed.valid_sample_count, observed.valid_sample_count + dist.unavailable_count
    span = (observed.last_observed_at - observed.observed_from).total_seconds() if n else None
    age = (supplied.cold.request.evaluation.evaluated_at - observed.last_observed_at).total_seconds() if n else None
    fraction = Fraction(n, total) if total else None
    coverage, checked, supported = _coverage(supplied, evidence)
    freshness = _ramp(age, thresholds.fresh_for_seconds, thresholds.stale_after_seconds)
    axes = {
        "samples": _ramp(n, minimum, full),
        "observed_period": _ramp(span, thresholds.minimum_observed_seconds, thresholds.full_observed_seconds),
        "freshness": 1 - freshness if freshness is not None else None,
        "availability": _ramp(fraction, thresholds.minimum_available_fraction, thresholds.full_available_fraction),
    }
    reasons = []
    if availability.status != "AVAILABLE":
        reasons.append("PROFILE_" + availability.status)
    if coverage == "INCOMPLETE":
        reasons.append("INCOMPLETE_SOURCE_COVERAGE")
    for axis, factor in axes.items():
        if factor is None:
            reasons.append(axis.upper() + "_UNAVAILABLE")
        elif factor < 1:
            reasons.append("STALE_OBSERVATIONS" if axis == "freshness" and factor == 0 else
                           axis.upper() + ("_MINIMUM_NOT_EXCEEDED" if factor == 0 else "_PARTIAL"))
    blocked = availability.status != "AVAILABLE" or coverage in {"INCOMPLETE", "UNAVAILABLE"} or any(v is None for v in axes.values())
    weight = Fraction(0) if blocked else min(axes.values())
    return WindowReadiness(window=name, evidence=evidence,
        measures=ReadinessMeasures(valid_samples=n, considered_samples=total, observed_seconds=span,
            recency_seconds=age, available_fraction=_unit_float(fraction) if fraction is not None else None,
            coverage_checked_samples=checked, coverage_supported_samples=supported),
        factors=ReadinessFactors(**{k: _unit_float(v) if v is not None else None for k, v in axes.items()}),
        coverage_status=coverage, personal_weight=_unit_float(weight), reasons=reasons or ["ALL_AXES_FULL"])


def evaluate_personal_readiness(supplied: PersonalizedScoringInput) -> PersonalReadinessResult:
    """No score calculation, clock access, learning, or automatic probability calibration."""
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    if supplied.config.transition_policy_version != READINESS_VERSION:
        raise ValueError("readiness requires its supported transition policy version")
    availability = supplied.profile_availability()[0]
    requested = set(supplied.cold.request.evaluation.requested_features)
    settings = {f.feature_id: f for f in supplied.config.features}
    profile_minimum = supplied.personal.state.published.config.min_samples if supplied.personal else 2
    features = []
    for feature_id in FEATURE_SPECS:
        if feature_id not in requested:
            continue
        thresholds = settings[feature_id].readiness
        minimum = max(2, profile_minimum, thresholds.minimum_samples)
        full = max(minimum + 1, thresholds.full_samples)
        evidence = {e.window: e for e in personal_feature_evidence(supplied, feature_id)}
        windows = tuple(_window(supplied, name, evidence.get(name), availability, thresholds, minimum, full)
                        for name in ("RECENT", "LONG_TERM"))
        features.append(FeatureReadiness(feature_id=feature_id, thresholds=thresholds,
            effective_minimum_samples=minimum, effective_full_samples=full, windows=windows))
    return PersonalReadinessResult(input_sha256=personalization_hash(supplied), config=supplied.config,
        config_sha256=personalization_hash(supplied.config), profile=availability, features=features)
