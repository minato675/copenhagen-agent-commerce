"""Assemble behavior inputs from caller-owned snapshots, without fitting or I/O."""

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.scoring import ColdScoringInput
from copenhagen_fds.features import transform_features
from copenhagen_fds.population_baselines import derive_population_baseline


def build_engine_behavior_input(request: EngineEvaluationRequest) -> PersonalizedScoringInput:
    """Validate publications -> transform observations -> select existing references.

    Invalid request/publication context raises before scoring. A valid derivation
    may be partial or unavailable; retain its diagnostics and existing selection
    rules. In particular, an expired supplied population is not a missing one.
    """
    request = EngineEvaluationRequest.model_validate(request.model_dump())
    derived = (derive_population_baseline(request.population.state.published, request.population_baseline_request)
               if request.population is not None else None)
    if derived is not None and derived.issue is not None:
        raise ValueError(f"population baseline derivation rejected: {derived.issue}")
    baseline = derived.baseline if derived is not None else None
    initial = request.initial_policy
    selection_request = BaselineSelectionRequest(evaluation=request.feature_input.evaluation,
        population_reference=baseline.profile.reference if baseline is not None else None,
        policy_id=initial.policy_id if initial is not None else None,
        policy_version=initial.version if initial is not None else None)
    cold = ColdScoringInput(request=selection_request, raw_features=transform_features(request.feature_input),
        selection=select_cold_baselines(selection_request, population=baseline, policy=initial), trace_id=request.trace_id)
    return PersonalizedScoringInput(cold=cold, config=request.personalization,
        personal=request.personal, population=request.population, population_baseline=derived)


def validate_engine_behavior_input(request: EngineEvaluationRequest, scoring: PersonalizedScoringInput) -> None:
    """Compare supplied evidence to its original snapshots without rerunning scores."""
    scoring = PersonalizedScoringInput.model_validate(scoring.model_dump())
    if scoring != build_engine_behavior_input(request):
        raise ValueError("behavior input must preserve original events, references, publications and settings")
