"""Connect existing GPT outcomes to existing risk policy, without numeric scoring."""

from datetime import datetime

from copenhagen_fds.contracts.engine_analysis import EngineAnalysisResult
from copenhagen_fds.contracts.engine_intent import EngineIntentEvidence, policy_intent_source
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_intent import intent_signal_fields


def project_engine_intent(record: EngineAnalysisResult) -> EngineIntentEvidence:
    record = EngineAnalysisResult.model_validate(record.model_dump())
    stage, intent = policy_intent_source(record)
    return EngineIntentEvidence(analysis=record, source_stage=stage,
        failure_stage=stage if intent.status == "FAILED" else None,
        intent=intent, **intent_signal_fields(intent))


def build_engine_intent_policy_input(
    evidence: EngineIntentEvidence, *, scoring_input: PersonalizedScoringInput,
    behavior: PersonalizedEvaluationResult | None, policy_evaluated_at: datetime,
) -> RiskPolicyInput:
    """Keep original BE intent for rules; extracted intent remains GPT evidence.

    This only assembles policy input; no model, behavior scorer or policy runs.
    Retain evidence alongside it to keep clarifications and the first failure stage.
    """
    evidence = EngineIntentEvidence.model_validate(evidence.model_dump())
    request = evidence.analysis.request
    supplied = RiskPolicyInput(request=request.feature_input.evaluation.request,
        scoring_input=scoring_input, behavior=behavior, intent=evidence.intent, policy=request.risk_policy,
        evaluated_at=request.feature_input.evaluation.evaluated_at, policy_evaluated_at=policy_evaluated_at)
    evidence.validate_for_input(supplied)
    return supplied
