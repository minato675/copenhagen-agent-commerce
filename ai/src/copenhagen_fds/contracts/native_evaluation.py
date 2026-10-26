"""Native FDS outcome and lossless execution/policy evidence; no legacy Sift projection."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.engine_execution import EngineExecutionResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_results import EngineStageExecution
from copenhagen_fds.contracts.results import RiskValue, Timestamp
from copenhagen_fds.contracts.risk_evaluation import RiskEvaluationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_results import Advice, Review
from copenhagen_fds.contracts.values import Array, InputModel
from copenhagen_fds.engine_intent import build_engine_intent_policy_input, project_engine_intent


class NativeEvaluationOutcome(InputModel):
    """Consumable outcome. A retained late policy result is only historical evidence."""
    status: Literal["SUCCESS", "INCOMPLETE", "FAILED", "CANCELLED"]
    risk_level: RiskValue
    recommendation: Advice | None
    review_actions: Array[Review]

    @model_validator(mode="after")
    def validate_advice(self) -> Self:
        if self.status == "SUCCESS":
            if self.risk_level == "UNKNOWN" or self.recommendation is None:
                raise ValueError("successful evaluation requires known risk and review advice")
            if self.recommendation == "NO_ADDITIONAL_REVIEW":
                if self.risk_level != "LOW" or self.review_actions:
                    raise ValueError("no additional review requires LOW without pending review")
            elif self.recommendation not in self.review_actions:
                raise ValueError("recommended review must remain in the action list")
        elif self.risk_level != "UNKNOWN" or self.recommendation is not None or not self.review_actions:
            raise ValueError("unresolved evaluation requires UNKNOWN, no recommendation and follow-up actions")
        return self


def native_outcome(execution, policy_stage, policy_result, termination) -> NativeEvaluationOutcome:
    if termination != "COMPLETED":
        return NativeEvaluationOutcome(status="CANCELLED" if termination == "CANCELLED" else "FAILED",
            risk_level="UNKNOWN", recommendation=None, review_actions=("RETRY_EVALUATION",))
    if policy_result is not None:
        decision = policy_result.decision
        return NativeEvaluationOutcome(status=decision.status.value, risk_level=decision.risk_level,
            recommendation=decision.recommendation, review_actions=decision.review_actions)
    failed = policy_stage.status == "FAILED" or any(s.status == "FAILED" for s in execution.stages)
    return NativeEvaluationOutcome(status="FAILED" if failed else "INCOMPLETE", risk_level="UNKNOWN",
        recommendation=None, review_actions=("CHECK_INPUT", "RETRY_EVALUATION"))


class NativeEvaluationResult(InputModel):
    schema_version: Literal["native-evaluation-result-v1"] = "native-evaluation-result-v1"
    execution: EngineExecutionResult = Field(repr=False)
    policy_stage: EngineStageExecution
    policy_input: RiskPolicyInput | None = Field(repr=False)
    policy_result: RiskEvaluationResult | None = Field(repr=False)
    completed_at: Timestamp
    elapsed_seconds: Annotated[float, Field(ge=0, allow_inf_nan=False)]
    termination: Literal["COMPLETED", "BUDGET_EXHAUSTED", "CANCELLED"]
    outcome: NativeEvaluationOutcome

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        execution = EngineExecutionResult.model_validate(self.execution.model_dump())
        stage = self.policy_stage
        if self.completed_at < execution.completed_at or self.elapsed_seconds < execution.elapsed_seconds:
            raise ValueError("finalization must retain consumed time and follow execution")
        exhausted = self.elapsed_seconds >= execution.budget_seconds
        if self.termination != "CANCELLED" and (self.termination == "BUDGET_EXHAUSTED") != exhausted:
            raise ValueError("final outcome must preserve shared budget exhaustion")
        if execution.termination == "CANCELLED" and self.termination != "CANCELLED":
            raise ValueError("finalization cannot undo caller cancellation")
        if stage.stage != "POLICY" or stage.skip_reason == "NOT_REQUESTED":
            raise ValueError("finalization requires a resolved policy stage")
        if stage.status != "NOT_RUN":
            if execution.termination != "COMPLETED":
                raise ValueError("interrupted execution cannot start policy evaluation")
            if any(value is None for value in (execution.behavior, execution.extraction, execution.comparison)):
                raise ValueError("policy execution requires complete upstream envelopes")
            if not execution.completed_at <= stage.started_at <= stage.completed_at <= self.completed_at:
                raise ValueError("policy requires its actual interval after original execution")
        else:
            if self.policy_input is not None or self.policy_result is not None:
                raise ValueError("unexecuted policy cannot claim calculated policy evidence")
            if stage.skip_reason == "CANCELLED" and self.termination != "CANCELLED":
                raise ValueError("cancelled policy requires cancelled finalization")
            if stage.skip_reason == "BUDGET_EXHAUSTED" and not exhausted:
                raise ValueError("budget skip requires consumed total budget")
            if stage.skip_reason not in {"CANCELLED", "BUDGET_EXHAUSTED", "UPSTREAM_FAILED"}:
                raise ValueError("policy skip must identify interruption or unavailable upstream evidence")
            if stage.skip_reason == "UPSTREAM_FAILED" and all(
                value is not None for value in (execution.behavior, execution.extraction, execution.comparison)
            ):
                raise ValueError("available upstream envelopes cannot claim missing policy evidence")
        if self.policy_input is not None:
            behavior = execution.behavior
            if behavior is None:
                raise ValueError("policy input requires original behavior input and result")
            expected = build_engine_intent_policy_input(project_engine_intent(execution.to_analysis_result()),
                scoring_input=behavior.scoring_input, behavior=behavior.evaluation, policy_evaluated_at=stage.started_at)
            if self.policy_input != expected:
                raise ValueError("policy input must preserve exact original behavior, GPT, request and versions")
        if self.policy_result is not None:
            if self.policy_input is None or stage.status != "SUCCESS":
                raise ValueError("policy result requires its input and completed policy calculation")
            RiskEvaluationResult.model_validate(self.policy_result.model_dump()).validate_for_input(self.policy_input)
            if any(s.status == "FAILED" for s in execution.stages) and self.policy_result.decision.status != "FAILED":
                raise ValueError("policy cannot hide a failed upstream stage")
        elif stage.status == "SUCCESS":
            raise ValueError("successful policy requires its original result")
        if self.outcome != native_outcome(execution, stage, self.policy_result, self.termination):
            raise ValueError("final outcome must retain policy decisions, failures and interruptions")
        return self

    def validate_for_request(self, request: EngineEvaluationRequest) -> Self:
        self.execution.validate_for_request(request)
        return type(self).model_validate(self.model_dump())
