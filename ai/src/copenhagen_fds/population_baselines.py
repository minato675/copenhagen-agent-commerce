"""Derive versioned Cold reference artifacts; never execute Cold or final risk evaluation."""

from datetime import timezone
from fractions import Fraction

from copenhagen_fds.contracts.baselines import FeatureReference, PopulationBaseline
from copenhagen_fds.contracts.population_baselines import (
    DerivedFeatureReference, PopulationBaselinePolicy, PopulationBaselineRequest, PopulationBaselineResult,
)
from copenhagen_fds.contracts.population_profiles import PopulationProfileSnapshot
from copenhagen_fds.contracts.profiles import BehaviorProfile
from copenhagen_fds.personal_profiles import content_hash
from copenhagen_fds.profile_statistics import weighted_quantile


def _compact_unimodal(histogram, period):
    """Conservative support check: no holes, separated peaks or half-cycle spread."""
    occupied = [item.value for item in histogram if item.count]
    if not occupied:
        return False
    if len(occupied) == 1:
        return True
    gaps = [((occupied[(i + 1) % len(occupied)] - value) % period, i) for i, value in enumerate(occupied)]
    gap, index = max(gaps)
    span = period - gap
    if span >= period / 2 or span + 1 != len(occupied):
        return False
    start = occupied[(index + 1) % len(occupied)]
    masses = [histogram[(start + offset) % period].probability for offset in range(span + 1)]
    decreasing = False
    for left, right in zip(masses, masses[1:]):
        if right < left:
            decreasing = True
        elif right > left and decreasing:
            return False
    return True


def _derive(feature, config):
    reason, parameter = None, None
    n = feature.observation.valid_sample_count
    if n == 0:
        reason = "EMPTY"
    elif n < config.min_samples:
        reason = "INSUFFICIENT_SAMPLES"
    elif len(feature.contributors) < config.min_users:
        reason = "INSUFFICIENT_USERS"
    elif feature.numeric is not None:
        center = feature.numeric.median
        scale = feature.numeric.q95 - center
        if scale <= 0:
            reason = "ZERO_SCALE"
        else:
            parameter = FeatureReference(feature_id=feature.feature_id, unit=feature.unit,
                                         window_seconds=feature.window_seconds, method="UPPER_DISTANCE",
                                         center=center, scale=scale)
    else:
        period, center = feature.circular.period, feature.circular.mean_position
        if center is None or not _compact_unimodal(feature.histogram, period):
            reason = "CIRCULAR_NOT_REPRESENTABLE"
        else:
            supported = [item for item in feature.histogram if item.count]
            distances = tuple(min(abs(item.value - center), period - abs(item.value - center)) for item in supported)
            weights = tuple(Fraction(str(item.probability)) for item in supported)
            scale = weighted_quantile(distances, weights, Fraction(95, 100))
            # Identical bins have no observed scale, regardless of trigonometric rounding.
            if len(supported) == 1 or scale <= 0:
                reason = "ZERO_SCALE"
            else:
                parameter = FeatureReference(feature_id=feature.feature_id, unit=feature.unit,
                                             method="CIRCULAR_DISTANCE", center=center, scale=scale, period=period)
    return DerivedFeatureReference(feature_id=feature.feature_id, parameter=parameter, reason=reason)


def derive_population_baseline(
    snapshot: PopulationProfileSnapshot, request: PopulationBaselineRequest,
) -> PopulationBaselineResult:
    """Return conversion diagnostics and a distinct content-versioned baseline reference."""
    request = PopulationBaselineRequest.model_validate({**request.model_dump(mode="python"),
                                                       "available_at": request.available_at.astimezone(timezone.utc)})
    source = snapshot.profile
    policy = PopulationBaselinePolicy()
    source_hash = content_hash(snapshot.model_dump(mode="json"))
    common = dict(request=request, policy=policy, source_profile_reference=source.reference, source_profile_sha256=source_hash)
    issue = None
    if source.reference.version != request.expected_source_reference.version or source.feature_schema_version != request.feature_schema_version:
        issue = "VERSION_MISMATCH"
    elif source.reference != request.expected_source_reference:
        issue = "REFERENCE_MISMATCH"
    elif source.currency != request.currency:
        issue = "CURRENCY_MISMATCH"
    elif not source.generated_at <= request.available_at < source.valid_until:
        issue = "INVALID_AVAILABILITY"
    if issue:
        return PopulationBaselineResult(**common, status="UNAVAILABLE", issue=issue)
    features = tuple(_derive(feature, snapshot.config) for feature in snapshot.long_term.features)
    parameters = tuple(feature.parameter for feature in features if feature.parameter is not None)
    baseline = None
    if parameters:
        # The mapping policy and availability are part of identity; never republish a
        # changed parameter set under the original learned-distribution reference.
        version = "population-cold-v1-" + content_hash({
            "source_profile": source_hash, "policy": policy.model_dump(mode="json"),
            "available_at": request.available_at.isoformat(),
            "parameters": [parameter.model_dump(mode="json") for parameter in parameters],
        })
        data = source.model_dump(mode="python")
        data["reference"].update(profile_id="cold-" + source.reference.profile_id, version=version)
        baseline = PopulationBaseline(profile=BehaviorProfile.model_validate(data), available_at=request.available_at,
                                      parameters=parameters)
    status = "COMPATIBLE" if len(parameters) == len(features) else ("PARTIAL" if parameters else "UNAVAILABLE")
    return PopulationBaselineResult(**common, status=status, features=features, baseline=baseline)
