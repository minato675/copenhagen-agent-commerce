"""Read-only personal amount candidates; selection, mixtures and risk decisions are separate."""

from copenhagen_fds.contracts.behavior import AnomalyScore, FeatureScoreResult
from copenhagen_fds.contracts.personal_amount import (
    AMOUNT_SCORE_VERSION, UNSCORED_AMOUNT, AmountCalculation, ExactAmount,
    PersonalAmountPolicy, PersonalAmountResult, PersonalAmountWindowResult,
)
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.personalization_results import personal_feature_evidence
from copenhagen_fds.personal_numeric import (
    bounded_upper_score, counted_quantile as _quantile, numeric_spread, saturation_distance,
)


def _calculation(distribution, policy: PersonalAmountPolicy) -> AmountCalculation:
    histogram = tuple((item.value, item.count) for item in distribution.histogram if item.count)
    spread = numeric_spread(histogram)
    distance = saturation_distance(spread, relative_floor=policy.relative_scale_floor,
                                   minimum=policy.minimum_scale_minor, multiplier=policy.spread_multiplier)
    return AmountCalculation(
        median_minor=ExactAmount.from_fraction(spread.median), q95_minor=ExactAmount.from_fraction(spread.q95),
        iqr_minor=ExactAmount.from_fraction(spread.iqr), mad_minor=ExactAmount.from_fraction(spread.mad),
        saturation_distance_minor=ExactAmount.from_fraction(distance), zero_spread=spread.zero_spread,
    )


def _score(amount: int, calculation: AmountCalculation) -> AnomalyScore:
    return bounded_upper_score(amount, calculation.median_minor.as_fraction(),
                               calculation.saturation_distance_minor.as_fraction())


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
            score = _score(raw.value, calculation)
            score = AnomalyScore.model_validate(score.model_dump())
            diagnostic = "SCORED"
        except (ArithmeticError, ValueError):
            diagnostic, calculation, score = "CALCULATION_FAILED", None, None
    if score is not None:
        result = FeatureScoreResult(feature_id="amount_minor", status="SCORED", score=score,
            bases=bases, reasons=["WITHIN_REFERENCE" if score.value == 0 else "PATTERN_DEVIATION"])
    else:
        status, reason = UNSCORED_AMOUNT[diagnostic]
        result = FeatureScoreResult(feature_id="amount_minor", status=status, reasons=[reason], bases=bases)
    return PersonalAmountWindowResult(window=window, evidence=evidence, result=result,
                                     diagnostic=diagnostic, calculation=calculation)


def score_personal_amount(
    supplied: PersonalizedScoringInput, policy: PersonalAmountPolicy = PersonalAmountPolicy(),
) -> PersonalAmountResult:
    """Score recent and long-term published amount distributions without choosing one.

    Invalid contracts raise before computation; absent/expired/insufficient data
    stays unscored. No clock, learning-state mutation, external service or RNG.
    """
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    policy = PersonalAmountPolicy.model_validate(policy.model_dump())
    raw = next((f for f in supplied.cold.raw_features.features if f.feature_id == "amount_minor"), None)
    if raw is None:
        raise ValueError("personal amount scoring requires a requested amount feature")
    setting = next(f for f in supplied.config.features if f.feature_id == "amount_minor")
    if setting.score_version != AMOUNT_SCORE_VERSION:
        raise ValueError("personal amount scoring requires its supported score version")
    minimum = max(2, setting.readiness.minimum_samples,
                  supplied.personal.state.published.config.min_samples if supplied.personal else 2)
    availability = supplied.profile_availability()[0]
    evidence = {item.window: item for item in personal_feature_evidence(supplied, "amount_minor")}
    windows = tuple(_candidate(name, evidence.get(name), raw, availability, minimum, policy)
                    for name in ("RECENT", "LONG_TERM"))
    return PersonalAmountResult(input_sha256=personalization_hash(supplied), policy=policy,
        policy_sha256=personalization_hash(policy), raw=raw, profile=availability,
        minimum_samples=minimum, windows=windows)
