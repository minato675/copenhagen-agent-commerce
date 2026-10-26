"""Pre-policy execution evidence, including interrupted analyses and late results."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.analysis.intent_analyzer import IntentAnalysisRun
from copenhagen_fds.contracts.analysis_results import IntentComparisonResult, IntentExtractionResult
from copenhagen_fds.contracts.engine_analysis import EngineAnalysisResult
from copenhagen_fds.contracts.engine_behavior import EngineBehaviorResult
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.engine_results import EngineStageExecution
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, InputModel
from copenhagen_fds.validation_memo import memoized_validation


ExecutionStageName = Literal["BEHAVIOR", "EXTRACTION", "COMPARISON"]
EXECUTION_STAGES = ("BEHAVIOR", "EXTRACTION", "COMPARISON")


class ExecutionStage(EngineStageExecution):
    stage: ExecutionStageName
    # Cancellation has its actual interval, but is not an API/calculation failure.
    status: Literal["SUCCESS", "FAILED", "NOT_RUN", "CANCELLED"]


def validate_execution_evidence(request, stage, result):
    if stage.stage == "BEHAVIOR":
        result = EngineBehaviorResult.model_validate(result.model_dump()).validate_for_request(request)
        status = result.evaluation.result.behavior.status
        failure = "CALCULATION_FAILED" if status == "FAILED" else None
    else:
        model = IntentExtractionResult if stage.stage == "EXTRACTION" else IntentComparisonResult
        result = model.model_validate(result.model_dump())
        metadata, telemetry = result.metadata, result.telemetry
        failure = None if result.failure is None else result.failure.kind
        if stage.status != result.status or stage.failure_code != failure:
            raise ValueError("execution must retain the recorded outcome and failure classification")
        prompt = (request.execution.extraction_prompt_version if stage.stage == "EXTRACTION"
                  else request.execution.comparison_prompt_version)
        if (metadata.target, metadata.trace_id, metadata.prompt_version, telemetry.gateway, telemetry.requested_model) != (
            request.analysis.target, request.trace_id, prompt, request.execution.gateway, request.execution.requested_model,
        ):
            raise ValueError("stage evidence must preserve original target, trace and execution configuration")
        if stage.stage == "EXTRACTION":
            result.validate_for_request(request.analysis)
            if (result.status == "NOT_RUN") != (request.analysis.user_request.text is None):
                raise ValueError("extraction evidence must retain missing input semantics")
            if result.input_source != request.analysis.user_request.source:
                raise ValueError("extraction must preserve the original text source")
        if result.status != "NOT_RUN" and not stage.started_at <= metadata.evaluated_at <= stage.completed_at:
            raise ValueError("stage completion must remain within its actual execution interval")
        status, failure = result.status, None if result.failure is None else result.failure.kind
    if stage.status != status or stage.failure_code != failure:
        raise ValueError("execution must retain the recorded outcome and failure classification")


class EngineExecutionResult(InputModel):
    schema_version: Literal["engine-execution-result-v1"] = "engine-execution-result-v1"
    request: EngineEvaluationRequest = Field(repr=False)
    reference: EngineRequestReference
    started_at: Timestamp
    completed_at: Timestamp
    budget_seconds: Annotated[float, Field(gt=0, allow_inf_nan=False)]
    elapsed_seconds: Annotated[float, Field(ge=0, allow_inf_nan=False)]
    termination: Literal["COMPLETED", "BUDGET_EXHAUSTED", "CANCELLED"]
    status: Literal["SUCCESS", "INCOMPLETE", "FAILED", "CANCELLED"]
    stages: Annotated[Array[ExecutionStage], Field(min_length=3, max_length=3)]
    behavior: EngineBehaviorResult | None = Field(repr=False)
    extraction: IntentExtractionResult | None = Field(repr=False)
    comparison: IntentComparisonResult | None = Field(repr=False)

    def stage(self, name: ExecutionStageName) -> ExecutionStage:
        return next(s for s in self.stages if s.stage == name)

    def expected_status(self) -> str:
        if self.termination == "CANCELLED":
            return "CANCELLED"
        if self.termination == "BUDGET_EXHAUSTED" or any(s.status == "FAILED" for s in self.stages):
            return "FAILED"
        if any(s.status == "NOT_RUN" for s in self.stages) or (
            self.behavior is not None and self.behavior.evaluation.result.behavior.coverage != "COMPLETE"
        ):
            return "INCOMPLETE"
        return "SUCCESS"

    @model_validator(mode="after")
    def validate_run(self) -> Self:
        memoized_validation("engine-execution-result-run-v1", self, self._validate_run)
        return self

    def _validate_run(self) -> bool:
        """Preserve all checks while reusing exact-input successful validation."""
        request = EngineEvaluationRequest.model_validate(self.request.model_dump())
        if self.reference != EngineRequestReference.from_request(request):
            raise ValueError("execution must preserve its exact engine request")
        if not self.reference.behavior_as_of <= self.started_at <= self.completed_at:
            raise ValueError("execution must follow its fixed behavior cutoff")
        if self.budget_seconds > request.execution.total_timeout_seconds:
            raise ValueError("execution cannot expand the configured time budget")
        exhausted = self.elapsed_seconds >= self.budget_seconds
        if self.termination != "CANCELLED" and (self.termination == "BUDGET_EXHAUSTED") != exhausted:
            raise ValueError("termination must preserve monotonic budget exhaustion")
        if tuple(s.stage for s in self.stages) != EXECUTION_STAGES:
            raise ValueError("execution requires every stage once in order")
        previous = self.started_at
        stopped = False
        for stage, result in zip(self.stages, (self.behavior, self.extraction, self.comparison)):
            if stage.skip_reason == "NOT_REQUESTED":
                raise ValueError("completed execution cannot contain undecided stages")
            if stopped and stage.status != "NOT_RUN":
                raise ValueError("interruption must stop every subsequent stage")
            if stage.skip_reason in {"CANCELLED", "BUDGET_EXHAUSTED"}:
                cancelled_after_expiry = self.termination == "CANCELLED" and exhausted and stage.skip_reason == "BUDGET_EXHAUSTED"
                if stage.skip_reason != self.termination and not cancelled_after_expiry:
                    raise ValueError("skipped stage must match execution termination")
                stopped = True
            if stage.status == "CANCELLED":
                stopped = True
            if stage.stage == "BEHAVIOR" and stage.status == "NOT_RUN" and not stopped:
                raise ValueError("behavior can only be skipped by budget exhaustion or cancellation")
            if stage.status != "NOT_RUN":
                if not previous <= stage.started_at <= stage.completed_at <= self.completed_at:
                    raise ValueError("sequential stages require ordered actual execution intervals")
                previous = stage.completed_at
            if stage.status == "CANCELLED" and self.termination != "CANCELLED":
                raise ValueError("cancelled stage requires cancelled execution")
            if result is not None:
                validate_execution_evidence(request, stage, result)
            elif stage.status == "SUCCESS":
                raise ValueError("successful execution requires its original result")
        if self.stage("COMPARISON").status != "NOT_RUN" and (
            self.extraction is None or self.extraction.status != "SUCCESS"
        ):
            raise ValueError("comparison requires successful extraction")
        if self.comparison is not None:
            if self.extraction is None:
                raise ValueError("comparison evidence requires original extraction")
            self.to_analysis_result()
        if self.status != self.expected_status():
            raise ValueError("execution status cannot hide interruption, failure or missing evidence")
        return True

    def to_analysis_result(self) -> EngineAnalysisResult:
        if self.extraction is None or self.comparison is None:
            raise ValueError("interrupted analysis has no complete two-stage envelope")
        stages = self.stages[1:]
        starts = [s.started_at for s in stages if s.started_at is not None]
        ends = [s.completed_at for s in stages if s.completed_at is not None]
        return EngineAnalysisResult(request=self.request, reference=self.reference,
            started_at=min(starts) if starts else self.completed_at,
            completed_at=max(ends) if ends else self.completed_at,
            analysis=IntentAnalysisRun(extraction=self.extraction, comparison=self.comparison))

    def validate_for_request(self, request: EngineEvaluationRequest) -> Self:
        if self.reference != EngineRequestReference.from_request(request):
            raise ValueError("execution belongs to different request content or versions")
        return type(self).model_validate(self.model_dump())
