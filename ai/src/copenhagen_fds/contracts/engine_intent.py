"""Lossless policy projection with explicit origin of the first analysis failure."""

from typing import Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.analysis_results import IntentComparisonResult
from copenhagen_fds.contracts.engine_analysis import EngineAnalysisResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_intent import IntentSignal, intent_signal_fields
from copenhagen_fds.contracts.risk_results import PolicyReason
from copenhagen_fds.contracts.values import Array, InputModel
from copenhagen_fds.engine_behavior_inputs import validate_engine_behavior_input


def policy_intent_source(record: EngineAnalysisResult) -> tuple[str, IntentComparisonResult]:
    """A skipped comparison must not replace the extraction that prevented it."""
    run = record.analysis
    if run.extraction.status == "SUCCESS":
        return "COMPARISON", run.comparison
    first = run.extraction
    return "EXTRACTION", IntentComparisonResult(status=first.status, metadata=first.metadata,
        reasons=first.reasons, telemetry=first.telemetry, failure=first.failure)


class EngineIntentEvidence(InputModel):
    schema_version: Literal["engine-intent-evidence-v1"] = "engine-intent-evidence-v1"
    analysis: EngineAnalysisResult = Field(repr=False)
    source_stage: Literal["EXTRACTION", "COMPARISON"]
    failure_stage: Literal["EXTRACTION", "COMPARISON"] | None
    intent: IntentComparisonResult = Field(repr=False)
    signal: IntentSignal
    reasons: Array[PolicyReason]

    @model_validator(mode="after")
    def validate_projection(self) -> Self:
        record = EngineAnalysisResult.model_validate(self.analysis.model_dump())
        stage, source = policy_intent_source(record)
        failure_stage = stage if source.status == "FAILED" else None
        if (self.source_stage, self.intent, self.failure_stage) != (stage, source, failure_stage):
            raise ValueError("policy projection must retain the original outcome and failure stage")
        for name, expected in intent_signal_fields(source).items():
            if getattr(self, name) != expected:
                raise ValueError("policy projection must distinguish semantics from execution failure")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        evidence = type(self).model_validate(self.model_dump())
        supplied = RiskPolicyInput.model_validate(supplied.model_dump())
        record, request = evidence.analysis, evidence.analysis.request
        if (supplied.request, supplied.policy, supplied.intent) != (
            request.feature_input.evaluation.request, request.risk_policy, evidence.intent,
        ):
            raise ValueError("policy must retain the original purchase, configured policy and analysis outcome")
        validate_engine_behavior_input(request, supplied.scoring_input)
        if supplied.behavior is not None and supplied.behavior.components.policies != request.scoring:
            raise ValueError("policy must retain the configured behavior scoring policies")
        checked_at = supplied.policy_evaluated_at or supplied.evaluated_at
        if checked_at < record.completed_at:
            raise ValueError("policy cannot precede the complete analysis record")
        # Both stages are evidence: a fresh comparison cannot revive stale extraction.
        for result in (record.analysis.extraction, record.analysis.comparison):
            metadata = result.metadata
            if metadata.evaluated_at is not None and not 0 <= (
                checked_at - metadata.evaluated_at
            ).total_seconds() < request.risk_policy.intent_max_age_seconds:
                raise ValueError("analysis evidence must be within the exclusive policy age limit")
            if metadata.valid_until is not None and metadata.valid_until <= checked_at:
                raise ValueError("expired analysis evidence cannot enter current policy")
        return evidence
