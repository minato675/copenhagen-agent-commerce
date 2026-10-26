"""Personal count candidates from existing raw features; no event reaggregation."""

from copenhagen_fds.contracts.behavior import AnomalyScore, FeatureScoreResult
from copenhagen_fds.contracts.personal_frequency import (
    FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION, UNSCORED_FREQUENCY, ExactCount,
    FrequencyCalculation, PersonalFrequencyFeatureResult, PersonalFrequencyPolicy,
    PersonalFrequencyResult, PersonalFrequencyWindowResult,
)
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.personalization_results import personal_feature_evidence
from copenhagen_fds.personal_numeric import bounded_upper_score, numeric_spread, saturation_distance


def _calculation(distribution, policy):
    spread = numeric_spread(tuple((item.value, item.count) for item in distribution.histogram))
    distance = saturation_distance(spread, relative_floor=policy.relative_scale_floor,
                                   minimum=policy.minimum_scale_count, multiplier=policy.spread_multiplier)
    return FrequencyCalculation(
        median_count=ExactCount.from_fraction(spread.median), q95_count=ExactCount.from_fraction(spread.q95),
        iqr_count=ExactCount.from_fraction(spread.iqr), mad_count=ExactCount.from_fraction(spread.mad),
        saturation_distance_count=ExactCount.from_fraction(distance), zero_spread=spread.zero_spread,
    )


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
            calculation = _calculation(evidence.distribution, policy)
            score = bounded_upper_score(raw.value, calculation.median_count.as_fraction(),
                                        calculation.saturation_distance_count.as_fraction())
            score = AnomalyScore.model_validate(score.model_dump())
            diagnostic = "SCORED"
        except (ArithmeticError, ValueError):
            diagnostic, calculation, score = "CALCULATION_FAILED", None, None
    if score is not None:
        result = FeatureScoreResult(feature_id=raw.feature_id, status="SCORED", score=score,
            bases=bases, reasons=["WITHIN_REFERENCE" if score.value == 0 else "PATTERN_DEVIATION"])
    else:
        status, reason = UNSCORED_FREQUENCY[diagnostic]
        result = FeatureScoreResult(feature_id=raw.feature_id, status=status, reasons=[reason], bases=bases)
    return PersonalFrequencyWindowResult(window=window, evidence=evidence, result=result,
                                        diagnostic=diagnostic, calculation=calculation)


def score_personal_frequencies(
    supplied: PersonalizedScoringInput, policy: PersonalFrequencyPolicy = PersonalFrequencyPolicy(),
) -> PersonalFrequencyResult:
    """Evaluate only requested count features, independently by stream and time window.

    Invalid contracts raise before execution; missingness and arithmetic failures
    are explicit per candidate. Does not choose a window, mix scores or learn.
    """
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    policy = PersonalFrequencyPolicy.model_validate(policy.model_dump())
    raw = {f.feature_id: f for f in supplied.cold.raw_features.features if f.feature_id in FREQUENCY_FEATURES}
    if not raw:
        raise ValueError("personal frequency scoring requires a requested frequency feature")
    settings = {f.feature_id: f for f in supplied.config.features}
    if any(settings[name].score_version != FREQUENCY_SCORE_VERSION for name in raw):
        raise ValueError("each requested frequency requires its supported score version")
    availability = supplied.profile_availability()[0]
    profile_minimum = supplied.personal.state.published.config.min_samples if supplied.personal else 2
    features = []
    for name in FREQUENCY_FEATURES:
        if name not in raw:
            continue
        minimum = max(2, profile_minimum, settings[name].readiness.minimum_samples)
        evidence = {item.window: item for item in personal_feature_evidence(supplied, name)}
        windows = tuple(_candidate(window, evidence.get(window), raw[name], availability, minimum, policy)
                        for window in ("RECENT", "LONG_TERM"))
        features.append(PersonalFrequencyFeatureResult(raw=raw[name], minimum_samples=minimum, windows=windows))
    return PersonalFrequencyResult(input_sha256=personalization_hash(supplied), policy=policy,
        policy_sha256=personalization_hash(policy), profile=availability, features=features)
