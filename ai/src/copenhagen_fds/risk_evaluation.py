"""Pure FDS policy entry point; returns advice, never payment authorization."""

from copenhagen_fds.contracts.risk_evaluation import RiskEvaluationResult, assemble_risk_decision
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_classification import classify_risk


def evaluate_risk_policy(supplied: RiskPolicyInput) -> RiskEvaluationResult:
    classification = classify_risk(supplied)
    return RiskEvaluationResult(classification=classification, decision=assemble_risk_decision(classification))
