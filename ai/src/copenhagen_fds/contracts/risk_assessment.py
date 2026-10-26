"""Execution readiness and preserved calculation evidence, separate from risk classification."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.decisions import DecisionStatusValue
from copenhagen_fds.contracts.personal_components import PersonalComponentResult
from copenhagen_fds.contracts.personal_readiness import FeatureReadiness
from copenhagen_fds.contracts.personalization_results import PersonalizedFeatureResult
from copenhagen_fds.contracts.results import EvaluationReason, StatusValue
from copenhagen_fds.contracts.risk_coverage import RiskCoverageResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.values import Array, Identifier, InputModel


AssessmentReason = Literal[
    "ALL_SCORES_AVAILABLE", "BEHAVIOR_NOT_RUN", "CALCULATION_FAILED", "REQUIRED_SCORES_UNAVAILABLE",
    "OPTIONAL_SCORES_UNAVAILABLE", "FEATURES_NOT_REQUESTED", "COLD_FALLBACK_AFTER_FAILURE",
]


class RiskFeatureEvidence(InputModel):
    detail: PersonalizedFeatureResult
    readiness: FeatureReadiness
    personal_execution: PersonalComponentResult

    @model_validator(mode="after")
    def validate_feature(self) -> Self:
        if self.detail.combined.feature_id != self.readiness.feature_id:
            raise ValueError("feature evidence must retain its own readiness measures")
        if self.detail.personal != self.personal_execution.result:
            raise ValueError("personal score evidence must preserve its execution diagnostics")
        return self


class RiskCalculationFailure(InputModel):
    component: Literal["COLD", "PERSONAL", "COMBINED"]
    result: FeatureScoreResult

    @model_validator(mode="after")
    def validate_failure(self) -> Self:
        if self.result.status != "FAILED":
            raise ValueError("calculation failure must retain the failed result without a score")
        return self


def assessment_fields(coverage: RiskCoverageResult, evidence: tuple[RiskFeatureEvidence, ...]) -> dict:
    failures = tuple(RiskCalculationFailure(component=side, result=result)
        for row in evidence
        for side, result in (("COLD", row.detail.cold), ("PERSONAL", row.detail.personal), ("COMBINED", row.detail.combined))
        if result.status == "FAILED")
    fallbacks = tuple(row.detail.combined.feature_id for row in evidence
        if row.detail.personal.status == "FAILED" and row.detail.stage == "COLD" and row.detail.combined.status == "SCORED")
    execution = "NOT_RUN" if not evidence else "FAILED" if failures else "SUCCESS"
    status = execution if execution != "SUCCESS" else "SUCCESS" if coverage.can_aggregate else "INCOMPLETE"
    reasons = []
    if execution == "NOT_RUN":
        reasons.append("BEHAVIOR_NOT_RUN")
    elif execution == "FAILED":
        reasons.append("CALCULATION_FAILED")
    for present, reason in ((coverage.required_unavailable, "REQUIRED_SCORES_UNAVAILABLE"),
                            (coverage.optional_unavailable, "OPTIONAL_SCORES_UNAVAILABLE"),
                            (coverage.unrequested_features, "FEATURES_NOT_REQUESTED"),
                            (fallbacks, "COLD_FALLBACK_AFTER_FAILURE")):
        if present:
            reasons.append(reason)
    return dict(execution_status=execution, status=status, failures=failures, cold_fallback_features=fallbacks,
                reasons=tuple(reasons or ["ALL_SCORES_AVAILABLE"]))


class RiskBehaviorAssessment(InputModel):
    schema_version: Literal["risk-behavior-assessment-v1"] = "risk-behavior-assessment-v1"
    coverage: RiskCoverageResult
    execution_status: StatusValue
    status: DecisionStatusValue
    source_reasons: Array[EvaluationReason]
    evidence: Annotated[Array[RiskFeatureEvidence], Field(max_length=9)]
    failures: Array[RiskCalculationFailure]
    cold_fallback_features: Array[Identifier]
    reasons: Annotated[Array[AssessmentReason], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_assessment(self) -> Self:
        rows = [row.feature for row in self.coverage.features if row.source == "RESULT"]
        if tuple(rows) != tuple(row.detail.combined for row in self.evidence):
            raise ValueError("assessment must preserve every evaluated final feature and its ordered evidence")
        for field, expected in assessment_fields(self.coverage, self.evidence).items():
            if getattr(self, field) != expected:
                raise ValueError("assessment state must preserve execution failures, coverage and Cold fallback")
        codes = {r.code for r in self.source_reasons}
        if self.execution_status == "FAILED" and "EVALUATION_FAILED" not in codes:
            raise ValueError("failed behavior assessment must retain the source failure reason")
        if self.execution_status == "NOT_RUN" and self.source_reasons:
            raise ValueError("absent behavior result cannot claim source reasons")
        if self.execution_status == "SUCCESS" and codes.intersection({"EVALUATION_FAILED", "TIMEOUT", "INVALID_RESPONSE", "NOT_EXECUTED"}):
            raise ValueError("successful behavior execution cannot contain failure reasons")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        from copenhagen_fds.risk_assessment import assess_risk_behavior

        if self != assess_risk_behavior(supplied):
            raise ValueError("assessment must preserve the exact source scores, readiness, diagnostics and input")
        return self
