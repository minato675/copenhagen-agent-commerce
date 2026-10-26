"""Classify policy evidence without executing scorers or external services."""

from copenhagen_fds.contracts.risk_classification import RiskClassificationResult, classification_fields
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_intent_policy import evaluate_risk_intent_policy


def classify_risk(supplied: RiskPolicyInput) -> RiskClassificationResult:
    evidence = evaluate_risk_intent_policy(supplied)
    return RiskClassificationResult(evidence=evidence, **classification_fields(evidence))
