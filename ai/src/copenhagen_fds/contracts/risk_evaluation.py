"""Final decision projection with complete, reproducible policy evidence."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.risk_classification import RiskClassificationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_results import NativeFraudDecision, PolicyReason
from copenhagen_fds.contracts.values import InputModel


def assemble_risk_decision(classification: RiskClassificationResult) -> NativeFraudDecision:
    evidence = classification.evidence
    aggregation = evidence.rules.aggregation
    assessment = aggregation.assessment
    coverage = assessment.coverage
    reasons, actions = [], set(evidence.review_actions)
    if assessment.status == "FAILED":
        reasons.append(PolicyReason(code="EVALUATION_FAILED"))
        actions.add("RETRY_EVALUATION")
    elif assessment.status == "NOT_RUN":
        reasons.append(PolicyReason(code="NOT_EXECUTED"))
        actions.add("RETRY_EVALUATION")
    for row in coverage.features:
        if row.feature.score is None:
            reasons.append(PolicyReason(code="INSUFFICIENT_DATA", group_id=row.group_id,
                                        feature_id=row.feature.feature_id))
            actions.add("CHECK_INPUT")
        elif row.feature.score.value > 0:
            reasons.append(PolicyReason(code="BEHAVIOR_DEVIATION", group_id=row.group_id,
                                        feature_id=row.feature.feature_id))
    reasons.extend((*evidence.rules.reasons, *evidence.intent.reasons))
    recommendation = None
    if classification.status == "SUCCESS":
        if classification.risk_level == "HIGH":
            recommendation = "REQUIRE_AUTH"
        elif classification.risk_level == "MEDIUM" or evidence.intent.signal == "MISMATCH":
            recommendation = "REVIEW_ORDER"
        elif evidence.intent.signal in {"UNKNOWN", "NOT_RUN"}:
            recommendation = "VERIFY_USER_INTENT"
        else:
            recommendation = "NO_ADDITIONAL_REVIEW"
        if recommendation != "NO_ADDITIONAL_REVIEW":
            actions.add(recommendation)
    if not reasons:
        reasons.append(PolicyReason(code="WITHIN_POLICY"))
    return NativeFraudDecision(input_reference=coverage.input_reference, policy=coverage.policy,
        policy_sha256=coverage.policy_sha256, status=classification.status,
        evaluated_at=coverage.input_reference.evaluated_at, evaluation_scope=coverage.evaluation_scope,
        score_coverage=coverage.score_coverage, groups=aggregation.groups, behavior_score=aggregation.behavior_score,
        risk_level=classification.risk_level, recommendation=recommendation, reasons=reasons,
        review_actions=tuple(action for action in (
            "CHECK_INPUT", "RETRY_EVALUATION", "REVIEW_ORDER", "VERIFY_USER_INTENT", "REQUIRE_AUTH",
        ) if action in actions), rule_evidence=evidence.rules.rule_evidence)


class RiskEvaluationResult(InputModel):
    schema_version: Literal["risk-evaluation-result-v1"] = "risk-evaluation-result-v1"
    evaluation_version: Literal["risk-decision-assembly-v1"] = "risk-decision-assembly-v1"
    classification: RiskClassificationResult
    decision: NativeFraudDecision

    @model_validator(mode="after")
    def validate_projection(self) -> Self:
        if self.decision != assemble_risk_decision(self.classification):
            raise ValueError("decision must preserve the complete classification, evidence and review policy")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        self.classification.validate_for_input(supplied)
        self.decision.validate_for_input(supplied)
        return type(self).model_validate(self.model_dump())
