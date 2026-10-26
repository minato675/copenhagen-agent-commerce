"""Read-only per-feature personal/Cold mixtures, with explicit partial failures."""

import math

from copenhagen_fds.cold_scoring import score_cold_features
from copenhagen_fds.contracts.behavior import AnomalyScore, BehaviorEvaluationResult, FeatureScoreResult
from copenhagen_fds.contracts.personal_components import PersonalScoringPolicies
from copenhagen_fds.contracts.personal_evaluation import FeatureScaleAgreement, PersonalizedEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.personalization_results import PersonalizedFeatureResult, PersonalizedScoringResult
from copenhagen_fds.contracts.results import EvaluationMetadata
from copenhagen_fds.contracts.scoring import UNAVAILABLE_STATES
from copenhagen_fds.personal_components import evaluate_personal_components


def scale_agreement(decision, personal_version) -> FeatureScaleAgreement:
    reference = decision.cold_reference
    method = reference.parameter.method if reference.parameter is not None else None
    rule = {None: "NO_COLD_REFERENCE", "UPPER_DISTANCE": "UPPER_DEVIATION",
            "ABSOLUTE_DISTANCE": "INCOMPATIBLE_DIRECTION",
            "CIRCULAR_DISTANCE": "CIRCULAR_DISTANCE_RARITY_HEURISTIC"}[method]
    return FeatureScaleAgreement(feature_id=decision.raw.feature_id, personal_score_version=personal_version,
                                 cold_method=method, rule=rule)


def _applied_weight(cold, personal, decision, agreement):
    """Never increase partial personal support when a required Cold score fails."""
    weight = decision.preferred_personal_weight
    if weight > 0 and personal.status == "SCORED":
        if weight == 1:
            return 1.0, "PERSONAL_SCORE_APPLIED"
        if cold.status == "SCORED" and agreement.rule != "INCOMPATIBLE_DIRECTION":
            return weight, "PERSONAL_COLD_BLEND_APPLIED"
        if cold.status == "SCORED":
            return 0.0, "INCOMPATIBLE_SCALE_COLD_FALLBACK"
        return None, "REQUIRED_COLD_SCORE_UNAVAILABLE"
    if cold.status == "SCORED":
        return 0.0, "PERSONAL_CALCULATION_FAILED_COLD_FALLBACK" if personal.status == "FAILED" else "COLD_SCORE_APPLIED"
    return None, "NO_USABLE_COMPONENT_SCORE"


def _weighted_score(cold, personal, weight):
    parts, bases = [], []
    positive = False
    for w, score in ((1.0 - weight, cold), (weight, personal)):
        if w > 0:
            parts.append(w * score.score.value)
            bases.extend(score.bases)
            positive = positive or score.score.value > 0
    value = min(100.0, math.fsum(parts))
    if positive and value == 0:
        value = math.nextafter(0.0, 1.0)
    return FeatureScoreResult(feature_id=cold.feature_id, status="SCORED",
        score=AnomalyScore(value=value, unit="BEHAVIOR_ANOMALY_0_100"), bases=bases,
        reasons=["WITHIN_REFERENCE" if value == 0 else "PATTERN_DEVIATION"])


def combine_feature(cold, component, decision, readiness, agreement, score_version) -> PersonalizedFeatureResult:
    """Pure result assembly, also used to check a detached report's applied policy."""
    personal = component.result
    reasons = list(decision.reasons)
    unavailable = UNAVAILABLE_STATES.get(decision.raw.status)
    weight = None
    if unavailable is not None:
        status, reason = unavailable
        applied_reason = "CURRENT_INPUT_UNAVAILABLE"
    else:
        weight, applied_reason = _applied_weight(cold, personal, decision, agreement)
        failed = cold.status == "FAILED" or personal.status == "FAILED"
        status, reason = ("FAILED", "CALCULATION_FAILED") if failed else ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE")
    reasons.append(applied_reason)
    combined = (FeatureScoreResult(feature_id=cold.feature_id, status=status, reasons=[reason]) if weight is None
                else _weighted_score(cold, personal, weight))
    stage = "UNAVAILABLE" if weight is None else "COLD" if weight == 0 else "PERSONALIZED" if weight == 1 else "TRANSITION"
    attempted = bool(personal.bases)
    return PersonalizedFeatureResult(cold=cold, personal=personal, combined=combined,
        stage=stage, personal_weight=weight, cold_weight=None if weight is None else 1.0 - weight,
        personal_window=component.candidate.window if attempted else None,
        personal_score_version=score_version if attempted else None,
        evidence=tuple(w.evidence for w in readiness.windows if w.evidence is not None), stage_reasons=reasons)


def _behavior(supplied, features):
    combined = tuple(f.combined for f in features)
    failed = any(c.status == "FAILED" for f in features for c in (f.cold, f.personal, f.combined))
    scored = sum(f.status == "SCORED" for f in combined)
    coverage = "UNAVAILABLE" if not scored else "COMPLETE" if scored == len(combined) else "PARTIAL"
    reasons = []
    if failed:
        reasons.append(dict(code="EVALUATION_FAILED", source="FDS"))
    if any(f.status not in {"SCORED", "FAILED"} for f in combined):
        reasons.append(dict(code="INSUFFICIENT_DATA", source="FDS"))
    used = {b.profile for f in combined for b in f.bases if b.profile is not None}
    available = {p.reference: p for p in supplied.cold.evaluation_with_selected_profiles().profiles}
    for profile in supplied.profile_availability():
        if profile.status == "AVAILABLE":
            available[profile.context.profile.reference] = profile.context.profile
    # Stable order follows first use in canonical feature order, not supplied profile order.
    references = dict.fromkeys(b.profile for f in combined for b in f.bases if b.profile in used)
    return BehaviorEvaluationResult(status="FAILED" if failed else "SUCCESS", coverage=coverage,
        metadata=EvaluationMetadata(target=supplied.cold.request.evaluation.target,
            trace_id=supplied.cold.trace_id, evaluated_at=supplied.cold.request.evaluation.evaluated_at,
            sources=["FDS"], policy_version=supplied.config.transition_policy_version),
        feature_schema_version=supplied.cold.raw_features.feature_schema_version,
        requested_features=[f.feature_id for f in combined], scoring_version="personalized-features-v1",
        features=combined, profiles=[available[ref] for ref in references], reasons=reasons)


def evaluate_personalized_features(
    supplied: PersonalizedScoringInput, policies: PersonalScoringPolicies = PersonalScoringPolicies(),
) -> PersonalizedEvaluationResult:
    """Return auditable feature results; no training, final risk decision, DB or API.

    Invalid input/configuration raises before execution. Arithmetic failures are
    retained per component even when a usable Cold fallback yields full coverage.
    """
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    components = evaluate_personal_components(supplied, policies)
    cold = score_cold_features(supplied.cold)
    settings = {f.feature_id: f for f in supplied.config.features}
    agreements, features = [], []
    for cold_feature, component, decision, readiness in zip(cold.behavior.features, components.features,
            components.transition.features, components.transition.readiness.features):
        version = settings[cold_feature.feature_id].score_version
        agreement = scale_agreement(decision, version)
        agreements.append(agreement)
        features.append(combine_feature(cold_feature, component, decision, readiness, agreement, version))
    result = PersonalizedScoringResult(input_sha256=personalization_hash(supplied), config=supplied.config,
        config_sha256=personalization_hash(supplied.config), cold=cold, behavior=_behavior(supplied, features),
        profiles=supplied.profile_availability(), features=features).validate_for_input(supplied)
    return PersonalizedEvaluationResult(components=components, scale_agreements=agreements, result=result)
