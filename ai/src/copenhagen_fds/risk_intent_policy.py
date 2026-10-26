"""Attach intent review policy while retaining scores, floors and failures."""

from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_intent_policy import RiskIntentPolicyResult, intent_review_fields
from copenhagen_fds.risk_intent import interpret_risk_intent
from copenhagen_fds.risk_rules import evaluate_risk_rules


def evaluate_risk_intent_policy(supplied: RiskPolicyInput) -> RiskIntentPolicyResult:
    intent = interpret_risk_intent(supplied)
    rules = evaluate_risk_rules(supplied)
    return RiskIntentPolicyResult(rules=rules, intent=intent,
        **intent_review_fields(intent, required=rules.aggregation.assessment.coverage.policy.intent_required))
