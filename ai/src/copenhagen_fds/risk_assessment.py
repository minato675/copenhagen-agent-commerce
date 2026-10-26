"""Preserve observed execution failures and determine behavior assessment readiness."""

from copenhagen_fds.contracts.risk_assessment import RiskBehaviorAssessment, RiskFeatureEvidence, assessment_fields
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_coverage import evaluate_risk_coverage


def assess_risk_behavior(supplied: RiskPolicyInput) -> RiskBehaviorAssessment:
    """No recalculation, profile update or GPT semantic policy is performed."""
    supplied = RiskPolicyInput.model_validate(supplied.model_dump())
    coverage = evaluate_risk_coverage(supplied)
    behavior = supplied.behavior
    evidence = () if behavior is None else tuple(
        RiskFeatureEvidence(detail=detail, readiness=readiness, personal_execution=execution)
        for detail, readiness, execution in zip(behavior.result.features,
            behavior.components.transition.readiness.features, behavior.components.features, strict=True))
    fields = assessment_fields(coverage, evidence)
    if behavior is not None and fields["execution_status"] != behavior.result.behavior.status:
        raise ValueError("assessment execution status must agree with the supplied behavior result")
    return RiskBehaviorAssessment(coverage=coverage, evidence=evidence,
        source_reasons=behavior.result.behavior.reasons if behavior else (), **fields)
