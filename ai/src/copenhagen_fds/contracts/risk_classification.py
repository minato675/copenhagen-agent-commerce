"""Failure-first classification of already evaluated behavior and intent."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.results import RiskValue
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_intent_policy import RiskIntentPolicyResult
from copenhagen_fds.contracts.risk_results import RISK_RANK
from copenhagen_fds.contracts.values import InputModel


def classification_fields(evidence: RiskIntentPolicyResult) -> dict:
    aggregation = evidence.rules.aggregation
    assessment = aggregation.assessment
    thresholds = assessment.coverage.policy.thresholds
    score = aggregation.behavior_score
    threshold_risk = "UNKNOWN" if score is None else (
        "HIGH" if score.value >= thresholds.high_from else
        "MEDIUM" if score.value >= thresholds.medium_from else "LOW")
    if assessment.status == "FAILED" or evidence.status == "FAILED":
        status = "FAILED"
    elif assessment.status != "SUCCESS" or evidence.status == "INCOMPLETE":
        status = "INCOMPLETE"
    else:
        status = "SUCCESS"
    risk = "UNKNOWN" if status != "SUCCESS" else max(
        (threshold_risk, evidence.rules.minimum_risk or "LOW"), key=RISK_RANK.get)
    return dict(status=status, threshold_risk=threshold_risk, risk_level=risk)


class RiskClassificationResult(InputModel):
    schema_version: Literal["risk-classification-result-v1"] = "risk-classification-result-v1"
    evaluation_version: Literal["risk-failure-first-v1"] = "risk-failure-first-v1"
    evidence: RiskIntentPolicyResult
    status: Literal["SUCCESS", "INCOMPLETE", "FAILED"]
    # Arithmetic threshold evidence can be known even when final risk is UNKNOWN.
    threshold_risk: RiskValue
    risk_level: RiskValue

    @model_validator(mode="after")
    def validate_priority(self) -> Self:
        for field, expected in classification_fields(self.evidence).items():
            if getattr(self, field) != expected:
                raise ValueError("classification must preserve failure, availability and observed risk floors")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        self.evidence.validate_for_input(supplied)
        return type(self).model_validate(self.model_dump())
