"""Lossless engine execution records; validation does not run a model or update profiles."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.analysis.intent_analyzer import IntentAnalysisRun
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.risk_evaluation import RiskEvaluationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.values import Array, InputModel
from copenhagen_fds.engine_behavior_inputs import validate_engine_behavior_input


StageName = Literal["BEHAVIOR", "EXTRACTION", "COMPARISON", "POLICY"]
STAGES = ("BEHAVIOR", "EXTRACTION", "COMPARISON", "POLICY")


class EngineStageExecution(InputModel):
    stage: StageName
    status: Literal["SUCCESS", "FAILED", "NOT_RUN"]
    started_at: Timestamp | None = None
    completed_at: Timestamp | None = None
    failure_code: Literal["API_ERROR", "TIMEOUT", "REFUSAL", "INCOMPLETE_RESPONSE", "SCHEMA_ERROR",
                          "INSUFFICIENT_INPUT", "INVALID_INPUT", "CALCULATION_FAILED", "INTERNAL_ERROR"] | None = None
    skip_reason: Literal["MISSING_INPUT", "UPSTREAM_FAILED", "BUDGET_EXHAUSTED", "CANCELLED", "NOT_REQUESTED"] | None = None

    @model_validator(mode="after")
    def validate_execution(self) -> Self:
        if self.status == "NOT_RUN":
            if self.started_at is not None or self.completed_at is not None or self.skip_reason is None:
                raise ValueError("unexecuted stage requires a skip reason and no execution timestamps")
        elif self.started_at is None or self.completed_at is None or self.completed_at < self.started_at or self.skip_reason is not None:
            raise ValueError("executed stage requires an ordered interval and no skip reason")
        if (self.status == "FAILED") != (self.failure_code is not None):
            raise ValueError("stage failure code is required exactly for FAILED")
        return self


class EngineRunTiming(InputModel):
    version: Literal["engine-run-timing-v1"] = "engine-run-timing-v1"
    behavior_as_of: Timestamp
    started_at: Timestamp
    completed_at: Timestamp
    stages: Annotated[Array[EngineStageExecution], Field(min_length=4, max_length=4)]

    def stage(self, name: StageName) -> EngineStageExecution:
        return next(s for s in self.stages if s.stage == name)

    @model_validator(mode="after")
    def validate_timeline(self) -> Self:
        if not self.behavior_as_of <= self.started_at <= self.completed_at:
            raise ValueError("run must follow its fixed behavior cutoff")
        if tuple(s.stage for s in self.stages) != STAGES:
            raise ValueError("execution records require every stage once in canonical order")
        for stage in self.stages:
            if stage.status != "NOT_RUN" and not self.started_at <= stage.started_at <= stage.completed_at <= self.completed_at:
                raise ValueError("stage execution must remain within its containing run")
        extraction, comparison, policy = (self.stage(s) for s in STAGES[1:])
        if comparison.status != "NOT_RUN" and (
            extraction.status != "SUCCESS" or comparison.started_at < extraction.completed_at
        ):
            raise ValueError("comparison requires completed successful extraction")
        if policy.status != "NOT_RUN" and any(
            s.status != "NOT_RUN" and s.completed_at > policy.started_at for s in self.stages[:-1]
        ):
            raise ValueError("policy cannot precede the evidence it combines")
        return self


class EngineEvaluationResult(InputModel):
    """Internal evidence record, not a public BE response or payment authorization."""
    schema_version: Literal["engine-evaluation-result-v1"] = "engine-evaluation-result-v1"
    request: EngineEvaluationRequest = Field(repr=False)
    reference: EngineRequestReference
    timing: EngineRunTiming
    status: Literal["SUCCESS", "INCOMPLETE", "FAILED"]
    behavior_input: PersonalizedScoringInput | None = Field(repr=False)
    behavior: PersonalizedEvaluationResult | None = Field(repr=False)
    analysis: IntentAnalysisRun | None = Field(repr=False)
    policy_input: RiskPolicyInput | None = Field(repr=False)
    policy_result: RiskEvaluationResult | None = Field(repr=False)

    @model_validator(mode="after")
    def validate_run(self) -> Self:
        request = EngineEvaluationRequest.model_validate(self.request.model_dump())
        timing = EngineRunTiming.model_validate(self.timing.model_dump())
        if self.reference != EngineRequestReference.from_request(request) or timing.behavior_as_of != self.reference.behavior_as_of:
            raise ValueError("run must preserve the original request content, versions and behavior cutoff")
        behavior_stage = timing.stage("BEHAVIOR")
        if self.behavior_input is not None:
            validate_engine_behavior_input(request, self.behavior_input)
        if self.behavior is not None:
            if self.behavior_input is None:
                raise ValueError("behavior result requires its exact scoring input")
            result = PersonalizedEvaluationResult.model_validate(self.behavior.model_dump())
            result.result.validate_for_input(self.behavior_input)
            if result.components.policies != request.scoring or behavior_stage.status != result.result.behavior.status:
                raise ValueError("behavior record must preserve configured score policies and execution failures")
            if behavior_stage.status == "FAILED" and behavior_stage.failure_code != "CALCULATION_FAILED":
                raise ValueError("recorded behavior calculation failure requires its matching stage code")
        elif behavior_stage.status == "SUCCESS":
            raise ValueError("successful behavior stage requires its result")
        if behavior_stage.status == "NOT_RUN" and self.behavior_input is not None:
            raise ValueError("unexecuted behavior cannot claim a constructed scoring input")
        self._validate_analysis(request, timing)
        self._validate_policy(request, timing)
        failed = any(s.status == "FAILED" for s in timing.stages)
        expected = ("FAILED" if failed else
                    self.policy_result.decision.status.value if self.policy_result is not None else "INCOMPLETE")
        if self.status != expected:
            raise ValueError("engine status must preserve execution failures and incomplete policy evaluation")
        if failed and self.policy_result is not None and self.policy_result.decision.status != "FAILED":
            raise ValueError("policy result cannot hide a failed upstream execution")
        return self

    def _validate_analysis(self, request: EngineEvaluationRequest, timing: EngineRunTiming) -> None:
        stages = [timing.stage(name) for name in ("EXTRACTION", "COMPARISON")]
        if self.analysis is None:
            if any(s.status == "SUCCESS" for s in stages):
                raise ValueError("successful analysis stage requires original analysis evidence")
            return
        run = IntentAnalysisRun.model_validate(self.analysis.model_dump()).validate_for_request(request.analysis)
        for stage, result, prompt in zip(stages, (run.extraction, run.comparison),
                (request.execution.extraction_prompt_version, request.execution.comparison_prompt_version)):
            metadata = result.metadata
            if stage.status != result.status or metadata.trace_id != request.trace_id or result.telemetry.gateway != request.execution.gateway:
                raise ValueError("analysis must preserve execution status, trace and gateway")
            if metadata.prompt_version is not None and metadata.prompt_version != prompt:
                raise ValueError("analysis prompt must match the pinned execution configuration")
            if result.telemetry.requested_model is not None and result.telemetry.requested_model != request.execution.requested_model:
                raise ValueError("analysis requested model must match the execution configuration")
            if result.status != "NOT_RUN" and not stage.started_at <= metadata.evaluated_at <= stage.completed_at:
                raise ValueError("analysis completion metadata must belong to its actual execution interval")
            if result.failure is not None and stage.failure_code != result.failure.kind:
                raise ValueError("analysis stage must retain the original failure classification")

    def _validate_policy(self, request: EngineEvaluationRequest, timing: EngineRunTiming) -> None:
        stage = timing.stage("POLICY")
        if self.policy_input is not None:
            supplied = RiskPolicyInput.model_validate(self.policy_input.model_dump())
            if self.behavior_input is None or (supplied.request, supplied.scoring_input, supplied.behavior, supplied.policy) != (
                request.feature_input.evaluation.request, self.behavior_input, self.behavior, request.risk_policy,
            ):
                raise ValueError("policy input must preserve original behavior evidence and configured policy")
            if stage.status == "NOT_RUN" or (supplied.policy_evaluated_at or supplied.evaluated_at) != stage.started_at:
                raise ValueError("policy freshness must be checked at the recorded policy start")
            if supplied.intent.telemetry.gateway != request.execution.gateway:
                raise ValueError("policy intent gateway must match the engine execution configuration")
            if self.analysis is None:
                if supplied.intent.status != "NOT_RUN" or any(timing.stage(s).status != "NOT_RUN" for s in ("EXTRACTION", "COMPARISON")):
                    raise ValueError("absent analysis can only support an explicitly unexecuted intent signal")
            elif self.analysis.extraction.status == "SUCCESS":
                if supplied.intent != self.analysis.comparison:
                    raise ValueError("policy must preserve the exact comparison result")
            else:
                source = self.analysis.extraction
                for name in ("status", "metadata", "reasons", "telemetry", "failure"):
                    if getattr(supplied.intent, name) != getattr(source, name):
                        raise ValueError("policy must retain the extraction outcome that prevented comparison")
        if self.policy_result is not None:
            if self.policy_input is None or stage.status != "SUCCESS":
                raise ValueError("policy result requires its input and a completed policy calculation")
            RiskEvaluationResult.model_validate(self.policy_result.model_dump()).validate_for_input(self.policy_input)
        elif stage.status == "SUCCESS":
            raise ValueError("successful policy calculation requires its result")

    def validate_for_request(self, request: EngineEvaluationRequest) -> Self:
        expected = EngineRequestReference.from_request(request)
        if self.reference != expected:
            raise ValueError("recorded engine result belongs to different request content or versions")
        return type(self).model_validate(self.model_dump())
