"""Transport outcomes contain validated payloads, never raw SDK exceptions."""

from typing import Literal, Self

from pydantic import AwareDatetime, Field, model_validator

from copenhagen_fds.contracts import (
    AnalysisFailure, AnalysisTelemetry, IntentComparisonOutput, IntentExtractionOutput,
)
from copenhagen_fds.contracts.values import InputModel, Text


AnalysisStage = Literal["extraction", "comparison"]


class CompletionPrompt(InputModel):
    stage: AnalysisStage
    developer_message: Text = Field(repr=False)
    user_message: Text = Field(repr=False)
    version: Text


class StructuredCompletion(InputModel):
    stage: AnalysisStage
    status: Literal["SUCCESS", "FAILED"]
    payload: IntentExtractionOutput | IntentComparisonOutput | None = Field(default=None, repr=False)
    failure: AnalysisFailure | None = None
    telemetry: AnalysisTelemetry
    response_model: Text | None = None
    prompt_version: Text
    completed_at: AwareDatetime

    @model_validator(mode="after")
    def validate_outcome(self) -> Self:
        if self.status == "FAILED":
            if self.failure is None or self.payload is not None:
                raise ValueError("failed completion requires a failure and no payload")
        else:
            model = IntentExtractionOutput if self.stage == "extraction" else IntentComparisonOutput
            if not isinstance(self.payload, model) or self.failure is not None or self.response_model is None:
                raise ValueError("successful completion requires its stage payload and actual model")
        return self
