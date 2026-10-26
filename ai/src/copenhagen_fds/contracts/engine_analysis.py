"""Bind both existing GPT stages to one original engine request and run interval."""

from typing import Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.analysis.intent_analyzer import IntentAnalysisRun
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import InputModel


class EngineAnalysisResult(InputModel):
    schema_version: Literal["engine-analysis-result-v1"] = "engine-analysis-result-v1"
    request: EngineEvaluationRequest = Field(repr=False)
    reference: EngineRequestReference
    started_at: Timestamp
    completed_at: Timestamp
    analysis: IntentAnalysisRun = Field(repr=False)

    @model_validator(mode="after")
    def validate_binding(self) -> Self:
        request = EngineEvaluationRequest.model_validate(self.request.model_dump())
        if self.reference != EngineRequestReference.from_request(request):
            raise ValueError("analysis execution must preserve its original engine request")
        if not self.reference.behavior_as_of <= self.started_at <= self.completed_at:
            raise ValueError("analysis interval must follow the fixed behavior cutoff")
        run = IntentAnalysisRun.model_validate(self.analysis.model_dump()).validate_for_request(request.analysis)
        if run.extraction.input_source != request.analysis.user_request.source:
            raise ValueError("analysis execution must retain the original text source")
        if (run.extraction.status == "NOT_RUN") != (request.analysis.user_request.text is None):
            raise ValueError("uncollected text requires unexecuted extraction and comparison")
        previous = self.started_at
        for result, prompt in zip((run.extraction, run.comparison), (
            request.execution.extraction_prompt_version, request.execution.comparison_prompt_version,
        )):
            metadata, telemetry = result.metadata, result.telemetry
            if (metadata.trace_id, telemetry.gateway, telemetry.requested_model, metadata.prompt_version) != (
                request.trace_id, request.execution.gateway, request.execution.requested_model, prompt,
            ):
                raise ValueError("analysis must preserve trace, gateway, model and prompt configuration")
            if result.status == "NOT_RUN":
                if metadata.model_version is not None:
                    raise ValueError("unexecuted analysis cannot claim a response model")
            else:
                if not previous <= metadata.evaluated_at <= self.completed_at:
                    raise ValueError("analysis completion must follow its upstream evidence within the run")
                previous = metadata.evaluated_at
        return self

    def validate_for_request(self, request: EngineEvaluationRequest) -> Self:
        if self.reference != EngineRequestReference.from_request(request):
            raise ValueError("analysis belongs to different request content or versions")
        return type(self).model_validate(self.model_dump())
