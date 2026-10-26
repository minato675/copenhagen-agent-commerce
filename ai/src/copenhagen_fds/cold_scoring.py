"""Map Cold scores and unavailable evidence without loading data or calling APIs."""

from copenhagen_fds.contracts.behavior import (
    AnomalyScore, BehaviorEvaluationResult, FeatureScoreResult,
)
from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.results import EvaluationMetadata, EvaluationReason
from copenhagen_fds.contracts.scoring import (
    ColdScoringInput, ColdScoringResult, UNAVAILABLE_STATES, reference_sufficiency,
)
from copenhagen_fds.scoring import SCORING_VERSION, score_feature


def _evaluate_feature(raw, reference) -> FeatureScoreResult:
    unavailable = UNAVAILABLE_STATES.get(raw.status)
    if unavailable is None and reference.status == "UNAVAILABLE":
        unavailable = ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE")
    if unavailable is not None:
        return FeatureScoreResult(feature_id=raw.feature_id, status=unavailable[0], reasons=[unavailable[1]])
    try:
        score = score_feature(raw, reference.parameter)
        # Reject nonfinite/out-of-range computation results at the mapper boundary.
        score = AnomalyScore.model_validate(score.model_dump())
    except (ArithmeticError, ValueError):
        return FeatureScoreResult(feature_id=raw.feature_id, status="FAILED",
                                  bases=[reference.basis], reasons=["CALCULATION_FAILED"])
    return FeatureScoreResult(
        feature_id=raw.feature_id, status="SCORED", score=score, bases=[reference.basis],
        reasons=["WITHIN_REFERENCE" if score.value == 0 else "PATTERN_DEVIATION"],
    )


def score_cold_features(supplied: ColdScoringInput) -> ColdScoringResult:
    """Score an already transformed vector against already selected references.

    Invalid input/context raises before execution. Missing evidence is explicit;
    per-feature arithmetic failure preserves other scores and fails the component.
    No history transformation, baseline loading, clock, DB, network or randomness.
    """
    supplied = ColdScoringInput.model_validate(supplied.model_dump())
    raw = {f.feature_id: f for f in supplied.raw_features.features}
    references = {f.feature_id: f for f in supplied.selection.features}
    requested = tuple(name for name in FEATURE_SPECS if name in raw)
    features = tuple(_evaluate_feature(raw[name], references[name]) for name in requested)
    scored = sum(f.status == "SCORED" for f in features)
    failed = any(f.status == "FAILED" for f in features)
    coverage = "UNAVAILABLE" if not scored else "COMPLETE" if scored == len(features) else "PARTIAL"
    reasons = []
    if failed:
        reasons.append(EvaluationReason(code="EVALUATION_FAILED", source="FDS"))
    if any(f.status not in {"SCORED", "FAILED"} for f in features):
        reasons.append(EvaluationReason(code="INSUFFICIENT_DATA", source="FDS"))
    used = {basis.profile for feature in features for basis in feature.bases if basis.profile is not None}
    behavior = BehaviorEvaluationResult(
        status="FAILED" if failed else "SUCCESS",
        metadata=EvaluationMetadata(target=supplied.request.evaluation.target,
                                    trace_id=supplied.trace_id, sources=["FDS"],
                                    evaluated_at=supplied.request.evaluation.evaluated_at),
        reasons=reasons, feature_schema_version=supplied.raw_features.feature_schema_version,
        requested_features=requested, scoring_version=SCORING_VERSION,
        coverage=coverage, features=features,
        profiles=[p for p in supplied.selection.profiles if p.reference in used],
    )
    has_personal = any(p.reference.scope == "PERSONAL" for p in supplied.request.evaluation.profiles)
    return ColdScoringResult(
        behavior=behavior, raw_features=supplied.raw_features, selection=supplied.selection,
        personal_profile_status="SUPPLIED_NOT_USED" if has_personal else "NOT_SUPPLIED",
        sufficiency=reference_sufficiency(supplied.selection),
    ).validate_for_input(supplied)
