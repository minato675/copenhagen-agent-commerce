"""Cold-only evaluation of supplied events and reference candidates; no I/O."""

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.cold_scoring import score_cold_features
from copenhagen_fds.contracts.cold import ColdEvaluationInput
from copenhagen_fds.contracts.scoring import ColdScoringInput, ColdScoringResult
from copenhagen_fds.features import transform_features


def evaluate_cold(supplied: ColdEvaluationInput, *, population=None, policy=None) -> ColdScoringResult:
    """Validate -> transform events -> select references -> score and explain.

    Candidate formats and diagnostics follow select_cold_baselines. Invalid
    requests or conflicting history raise ValueError; missing/invalid references
    and per-feature calculation failures retain the existing result semantics.
    """
    supplied = ColdEvaluationInput.model_validate(supplied.model_dump())
    request = supplied.baseline_request()
    raw_features = transform_features(supplied.feature_input)
    selection = select_cold_baselines(request, population=population, policy=policy)
    return score_cold_features(ColdScoringInput(
        request=request, raw_features=raw_features, selection=selection,
        trace_id=supplied.trace_id,
    ))
