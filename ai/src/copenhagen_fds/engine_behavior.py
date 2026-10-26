"""Read-only engine behavior stage; no GPT, training, repository or synthetic loader."""

from copenhagen_fds.contracts.engine_behavior import EngineBehaviorResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.engine_behavior_inputs import build_engine_behavior_input
from copenhagen_fds.personal_evaluation import evaluate_personalized_features


def evaluate_engine_behavior(request: EngineEvaluationRequest) -> EngineBehaviorResult:
    """Run existing Cold/personal scoring once and retain all of its evidence.

    Invalid inputs raise before scoring. Component arithmetic failures remain in
    the existing result, including a usable Cold fallback. Unexpected execution
    errors propagate to the caller; this stage does not fabricate a normal score.
    """
    request = EngineEvaluationRequest.model_validate(request.model_dump())
    supplied = build_engine_behavior_input(request)
    evaluation = evaluate_personalized_features(supplied, request.scoring)
    return EngineBehaviorResult(request=request, reference=EngineRequestReference.from_request(request),
        scoring_input=supplied, evaluation=evaluation)
