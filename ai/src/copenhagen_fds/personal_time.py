"""Personal hour/weekday rarity from published circular histograms; no I/O."""

from fractions import Fraction
from math import nextafter

from copenhagen_fds.contracts.behavior import AnomalyScore, FeatureScoreResult
from copenhagen_fds.contracts.personal_time import (
    TIME_PERIODS, TIME_SCORE_VERSION, UNSCORED_TIME, ExactTimeMass, PersonalTimeFeatureResult,
    PersonalTimePolicy, PersonalTimeResult, PersonalTimeWindowResult, TimeCalculation,
)
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.personalization_results import personal_feature_evidence


def _calculation(distribution, position, policy):
    counts = tuple(item.count for item in distribution.histogram)
    period = TIME_PERIODS[distribution.feature_id]
    weight = policy.hour_neighbor_weight if period == 24 else policy.weekday_neighbor_weight
    weight, alpha = Fraction(str(weight)), Fraction(str(policy.pseudocount))
    masses = tuple(Fraction(count) + weight * (counts[(i - 1) % period] + counts[(i + 1) % period]) + alpha
                   for i, count in enumerate(counts))
    return TimeCalculation(period=period, bin_index=position, sample_count=sum(counts),
        direct_count=counts[position], previous_count=counts[(position - 1) % period],
        next_count=counts[(position + 1) % period],
        bin_status="OBSERVED" if counts[position] else "UNOBSERVED",
        smoothed_mass=ExactTimeMass.from_fraction(masses[position]),
        peak_mass=ExactTimeMass.from_fraction(max(masses)), total_mass=ExactTimeMass.from_fraction(sum(masses)))


def _score(calculation):
    ratio = 1 - calculation.smoothed_mass.as_fraction() / calculation.peak_mass.as_fraction()
    value = float(100 * ratio)
    if value == 0 and ratio > 0:
        value = nextafter(0.0, 1.0)
    return AnomalyScore(value=value, unit="BEHAVIOR_ANOMALY_0_100")


def _candidate(window, evidence, raw, availability, minimum, policy):
    diagnostic = None
    if raw.status != "AVAILABLE":
        diagnostic = "INPUT_" + raw.status
    elif availability.status != "AVAILABLE":
        diagnostic = "PROFILE_" + availability.status
    elif evidence.distribution.observation.valid_sample_count == 0:
        diagnostic = "EMPTY"
    elif evidence.distribution.observation.valid_sample_count < minimum:
        diagnostic = "INSUFFICIENT_SAMPLES"
    bases, calculation, score = (), None, None
    if diagnostic is None:
        bases = ({"kind": "PERSONAL_PROFILE", "profile": availability.context.profile.reference},)
        try:
            calculation = _calculation(evidence.distribution, raw.value, policy)
            score = _score(calculation)
            score = AnomalyScore.model_validate(score.model_dump())
            diagnostic = "SCORED"
        except (ArithmeticError, ValueError):
            diagnostic, calculation, score = "CALCULATION_FAILED", None, None
    if score is not None:
        result = FeatureScoreResult(feature_id=raw.feature_id, status="SCORED", score=score,
            bases=bases, reasons=["WITHIN_REFERENCE" if score.value == 0 else "PATTERN_DEVIATION"])
    else:
        status, reason = UNSCORED_TIME[diagnostic]
        result = FeatureScoreResult(feature_id=raw.feature_id, status=status, reasons=[reason], bases=bases)
    return PersonalTimeWindowResult(window=window, evidence=evidence, result=result,
                                   diagnostic=diagnostic, calculation=calculation)


def score_personal_times(
    supplied: PersonalizedScoringInput, policy: PersonalTimePolicy = PersonalTimePolicy(),
) -> PersonalTimeResult:
    """Return independent recent/long-term candidates, without window or stage choice.

    KST raw bins come from transform_features using purchase initiation time,
    not evaluation time. Circular means are never used as a single normal center.
    """
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    policy = PersonalTimePolicy.model_validate(policy.model_dump())
    raw = {f.feature_id: f for f in supplied.cold.raw_features.features if f.feature_id in TIME_PERIODS}
    if not raw:
        raise ValueError("personal time scoring requires a requested time feature")
    settings = {f.feature_id: f for f in supplied.config.features}
    if any(settings[name].score_version != TIME_SCORE_VERSION for name in raw):
        raise ValueError("each requested time feature requires its supported score version")
    availability = supplied.profile_availability()[0]
    profile_minimum = supplied.personal.state.published.config.min_samples if supplied.personal else 2
    features = []
    for name in TIME_PERIODS:
        if name not in raw:
            continue
        minimum = max(2, profile_minimum, settings[name].readiness.minimum_samples)
        evidence = {item.window: item for item in personal_feature_evidence(supplied, name)}
        windows = tuple(_candidate(window, evidence.get(window), raw[name], availability, minimum, policy)
                        for window in ("RECENT", "LONG_TERM"))
        features.append(PersonalTimeFeatureResult(raw=raw[name], minimum_samples=minimum, windows=windows))
    return PersonalTimeResult(input_sha256=personalization_hash(supplied), policy=policy,
        policy_sha256=personalization_hash(policy), profile=availability, features=features)
