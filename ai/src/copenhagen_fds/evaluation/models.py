"""Fixed case rubrics and observation envelopes with explicit provenance."""

import hashlib
import json
from typing import Annotated, Literal, Self

from pydantic import Field, JsonValue, model_validator

from copenhagen_fds.analysis import IntentAnalysisRun
from copenhagen_fds.contracts import IntentAnalysisRequest, IntentComparisonOutput, IntentExtractionOutput
from copenhagen_fds.contracts.analysis_results import AnalysisFailureKind
from copenhagen_fds.contracts.results import MatchValue, StatusValue
from copenhagen_fds.contracts.values import Array, InputModel, Text


SafeName = Annotated[str, Field(pattern=r"^[a-zA-Z0-9_.-]{1,100}$")]


class FieldCheck(InputModel):
    path: Annotated[str, Field(pattern=r"^(items|budget|purpose|explicit_conditions|preferences|ambiguities)(/[a-z_0-9]+)*$")]
    operation: Literal["equals", "contains", "nonempty"]
    value: JsonValue = None

    @model_validator(mode="after")
    def validate_operator(self) -> Self:
        if self.operation == "contains" and not isinstance(self.value, str):
            raise ValueError("contains requires reference text")
        if self.operation == "nonempty" and self.value is not None:
            raise ValueError("nonempty does not take a reference value")
        return self


class ExpectedOutcome(InputModel):
    extraction_status: StatusValue = "SUCCESS"
    comparison_status: StatusValue = "SUCCESS"
    comparison: MatchValue | None
    failure_stage: Literal["extraction", "comparison"] | None = None
    failure_kind: AnalysisFailureKind | None = None
    fields: Array[FieldCheck] = ()
    required_findings: Array[SafeName] = ()
    required_confirmations: Array[SafeName] = ()
    uncertainty: Literal["extraction", "comparison", "both"] | None = None

    @model_validator(mode="after")
    def validate_expected(self) -> Self:
        if (self.comparison_status == "SUCCESS") != (self.comparison is not None):
            raise ValueError("expected semantic label requires successful comparison")
        if (self.failure_stage is None) != (self.failure_kind is None):
            raise ValueError("expected failure stage and kind must be paired")
        failed = [stage for stage in ("extraction", "comparison") if getattr(self, stage + "_status") == "FAILED"]
        if failed != ([] if self.failure_stage is None else [self.failure_stage]):
            raise ValueError("expected execution failure must identify its stage")
        if self.extraction_status != "SUCCESS" and self.comparison_status != "NOT_RUN":
            raise ValueError("unsuccessful extraction cannot run comparison")
        if self.extraction_status != "SUCCESS" and self.fields:
            raise ValueError("failed extraction has no payload fields to grade")
        if len({c.path for c in self.fields}) != len(self.fields):
            raise ValueError("rubric field paths must be unique")
        return self


class MockFault(InputModel):
    stage: Literal["extraction", "comparison"]
    kind: Literal["authentication", "rate_limit", "server", "timeout", "refusal", "truncated", "schema", "foreign_reference", "extra_authority"]


class EvaluationCase(InputModel):
    case_id: SafeName
    categories: Annotated[Array[SafeName], Field(min_length=1)]
    rubric: Text = Field(repr=False)
    request: IntentAnalysisRequest = Field(repr=False)
    expected: ExpectedOutcome
    mock_extraction: IntentExtractionOutput = Field(repr=False)
    mock_comparison: IntentComparisonOutput = Field(repr=False)
    fault: MockFault | None = None


class EvaluationDataset(InputModel):
    version: SafeName
    synthetic: Literal[True] = True
    cases: Annotated[Array[EvaluationCase], Field(min_length=20)]

    @model_validator(mode="after")
    def validate_cases(self) -> Self:
        if len({c.case_id for c in self.cases}) != len(self.cases):
            raise ValueError("case IDs must be unique")
        if len({c.request.target for c in self.cases}) != len(self.cases):
            raise ValueError("case targets must be unique")
        return self

    @property
    def sha256(self) -> str:
        encoded = json.dumps(self.model_dump(mode="json"), sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


class Observation(InputModel):
    case_id: SafeName
    run: IntentAnalysisRun = Field(repr=False)


class ObservationBatch(InputModel):
    dataset_version: SafeName
    dataset_sha256: Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
    source: Literal["MOCK", "GMS_IMPORTED"]
    observations: Annotated[Array[Observation], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_sources(self) -> Self:
        if len({o.case_id for o in self.observations}) != len(self.observations):
            raise ValueError("duplicate observations cannot inflate evaluation counts")
        gateway = "MOCK" if self.source == "MOCK" else "GMS"
        if any(stage.telemetry.gateway != gateway for o in self.observations for stage in (o.run.extraction, o.run.comparison)):
            raise ValueError("observation source does not match stage telemetry")
        return self
