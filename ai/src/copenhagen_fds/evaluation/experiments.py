"""Frozen experiment plans and repeated observations, separate from legacy batches."""

from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from typing import Annotated, Literal, Self

from pydantic import AwareDatetime, Field, JsonValue, model_validator

from copenhagen_fds.analysis import IntentAnalysisRun
from copenhagen_fds.analysis.comparison_prompt import build_comparison_prompt
from copenhagen_fds.analysis.extraction_prompt import build_extraction_prompt
from copenhagen_fds.contracts import FraudEvaluationRequest, IntentComparisonResult, analysis_response_format
from copenhagen_fds.contracts.analysis_inputs import evaluation_target
from copenhagen_fds.contracts.values import Array, InputModel, Text
from copenhagen_fds.evaluation.models import EvaluationDataset, ObservationBatch, SafeName


Digest = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Milliseconds = Annotated[float, Field(ge=0, allow_inf_nan=False)]
Stage = Literal["extraction", "comparison"]


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":"), allow_nan=False).encode("utf-8")).hexdigest()


def prompt_body(prompt, model="gpt-5.4-mini") -> dict:
    return {"model": model, "messages": [
        {"role": "developer", "content": prompt.developer_message},
        {"role": "user", "content": prompt.user_message},
    ], "response_format": analysis_response_format(prompt.stage), "stream": False}


class PromptPin(InputModel):
    version: SafeName
    developer_sha256: Digest
    schema_sha256: Digest
    developer_message: Annotated[str, Field(min_length=1, max_length=100000)] = Field(repr=False)
    response_format: dict[str, JsonValue] = Field(repr=False)

    @model_validator(mode="after")
    def validate_hashes(self) -> Self:
        if digest(self.developer_message) != self.developer_sha256 or digest(self.response_format) != self.schema_sha256:
            raise ValueError("prompt pin content differs from its hash")
        return self

    @classmethod
    def from_prompt(cls, prompt):
        return cls(version=prompt.version, developer_sha256=digest(prompt.developer_message),
                   schema_sha256=digest(analysis_response_format(prompt.stage)), developer_message=prompt.developer_message,
                   response_format=analysis_response_format(prompt.stage))


def pinned_prompt_body(prompt, plan):
    body = prompt_body(prompt, plan.requested_model)
    body["messages"][0]["content"] = plan.prompts[prompt.stage].developer_message
    body["response_format"] = plan.prompts[prompt.stage].response_format
    return body


class HumanReview(InputModel):
    dataset_sha256: Digest
    reviewed_case_ids: Array[SafeName]
    reviewer: SafeName
    reviewed_at: AwareDatetime
    decision: Literal["APPROVED"]


class FrozenComparisonInput(InputModel):
    request: FraudEvaluationRequest = Field(repr=False)
    clarifications: Array[Text] = ()
    source: Literal["REFERENCE_INTENT", "GMS_EXTRACTED_INTENT"]


class ExperimentPlan(InputModel):
    plan_version: Literal["fds-experiment-plan-v1"] = "fds-experiment-plan-v1"
    experiment_id: SafeName
    dataset: EvaluationDataset = Field(repr=False)
    dataset_role: Literal["REGRESSION", "STABILITY", "HOLDOUT"]
    mode: Literal["PIPELINE", "COMPARISON_REPEAT"]
    case_ids: Annotated[Array[SafeName], Field(min_length=1)]
    repetitions: Annotated[int, Field(ge=1, le=5)] = 1
    requested_model: Literal["gpt-5.4-mini"] = "gpt-5.4-mini"
    prompts: dict[Stage, PromptPin]
    extraction_input_hashes: dict[SafeName, Digest] = Field(default_factory=dict)
    comparison_input_hashes: dict[SafeName, Digest] = Field(default_factory=dict)
    frozen_inputs: dict[SafeName, FrozenComparisonInput] = Field(default_factory=dict, repr=False)
    frozen_source_sha256: Digest | None = None
    human_review: HumanReview | None = None
    max_http_requests: Annotated[int, Field(ge=1, le=1000)]
    max_retries: Literal[0] = 0
    provider_timeout_seconds: Annotated[float, Field(gt=0, le=120, allow_inf_nan=False)] = 60.0
    evaluation_timeout_seconds: Annotated[float, Field(gt=0, le=240, allow_inf_nan=False)] = 120.0
    # GMS does not expose billed prices here. A call cap is not a monetary cap.
    budget_basis: Literal["CALL_COUNT_ONLY_UNPRICED"] = "CALL_COUNT_ONLY_UNPRICED"

    @model_validator(mode="after")
    def validate_plan(self) -> Self:
        cases = {c.case_id: c for c in self.dataset.cases}
        chosen = set(self.case_ids)
        if len(chosen) != len(self.case_ids) or not chosen <= set(cases):
            raise ValueError("invalid experiment case selection")
        if any(len(key) > 95 for key in chosen):
            raise ValueError("case identity cannot fit a run identity")
        if any(cases[key].fault is not None for key in chosen):
            raise ValueError("fault fixtures cannot be model evaluation inputs")
        if self.dataset_role != "REGRESSION" and any(c.case_id.startswith("fds-") for c in self.dataset.cases):
            raise ValueError("new suites cannot reuse regression case identities")
        if self.human_review is not None and self.dataset_role != "HOLDOUT":
            raise ValueError("human holdout review cannot be attached to another suite")
        if set(self.prompts) != {"extraction", "comparison"}:
            raise ValueError("both prompt versions must be pinned")
        if self.provider_timeout_seconds > self.evaluation_timeout_seconds:
            raise ValueError("provider timeout exceeds experiment timeout")
        if self.mode == "PIPELINE":
            expected = {key for key in chosen if cases[key].request.user_request.text is not None}
            if self.repetitions != 1 or set(self.extraction_input_hashes) != expected:
                raise ValueError("pipeline requires one run and frozen extraction inputs")
            if self.frozen_inputs or self.comparison_input_hashes or self.frozen_source_sha256:
                raise ValueError("pipeline cannot reuse extraction")
        else:
            if set(self.frozen_inputs) != chosen or set(self.comparison_input_hashes) != chosen:
                raise ValueError("repeated comparison requires every frozen input")
            if self.extraction_input_hashes or self.frozen_source_sha256 is None:
                raise ValueError("repeated comparison requires source provenance")
            if len(set(self.comparison_input_hashes.values())) != len(chosen):
                raise ValueError("repeated comparison cases must have distinct input messages")
            for key in chosen:
                # Binding is validated by the existing comparison output boundary.
                if evaluation_target(self.frozen_inputs[key].request) != cases[key].request.target:
                    raise ValueError("frozen comparison belongs to another evaluation target")
                cases[key].mock_comparison.validate_for_request(self.frozen_inputs[key].request)
        if self.dataset_role == "HOLDOUT":
            counts = Counter(c.expected.comparison for c in self.dataset.cases)
            if counts != {"MATCH": 100, "MISMATCH": 100, "UNKNOWN": 100}:
                raise ValueError("holdout must contain 100 cases per label")
            if any(c.case_id.startswith("fds-") or c.fault for c in self.dataset.cases):
                raise ValueError("holdout identities must be separate from regression")
            if self.human_review is not None:
                review = self.human_review
                if review.dataset_sha256 != self.dataset.sha256 or set(review.reviewed_case_ids) != set(cases):
                    raise ValueError("human review must cover the exact holdout")
                if len(review.reviewed_case_ids) != len(cases):
                    raise ValueError("duplicate review case identity")
        return self

    @property
    def sha256(self) -> str:
        return digest(self.model_dump(mode="json"))

    @property
    def run_ids(self) -> tuple[tuple[str, int], ...]:
        # Round-robin reduces confounding a case with only one time segment.
        return tuple((key, index) for index in range(1, self.repetitions + 1) for key in self.case_ids)


class HttpAttempt(InputModel):
    sequence: Annotated[int, Field(ge=1)]
    case_id: SafeName
    repetition: Annotated[int, Field(ge=1, le=5)]
    stage: Stage
    input_sha256: Digest
    http_status: Annotated[int, Field(ge=100, le=599)] | None = None


class ExperimentObservation(InputModel):
    case_id: SafeName
    repetition: Annotated[int, Field(ge=1, le=5)]
    run_id: SafeName
    run: IntentAnalysisRun | None = Field(default=None, repr=False)
    comparison: IntentComparisonResult | None = Field(default=None, repr=False)
    wall_duration_ms: Milliseconds | None = None
    extraction_input_sha256: Digest | None = None
    comparison_input_sha256: Digest | None = None

    @model_validator(mode="after")
    def validate_identity(self) -> Self:
        if self.run_id != f"{self.case_id}.r{self.repetition}":
            raise ValueError("run identity does not match case and repetition")
        if (self.run is None) == (self.comparison is None):
            raise ValueError("observation requires exactly one execution shape")
        return self


class ExperimentBatch(InputModel):
    format_version: Literal["fds-experiment-observations-v1"] = "fds-experiment-observations-v1"
    plan_sha256: Digest
    source: Literal["MOCK", "GMS_IMPORTED"]
    status: Literal["RUNNING", "COMPLETE", "STOPPED", "INTERRUPTED"]
    stop_reason: Literal["CALL_LIMIT", "PROVIDER_ACCESS", "REPEATED_FAILURE", "LOCAL_ERROR", "CANCELLED"] | None = None
    started_at: AwareDatetime
    completed_at: AwareDatetime | None = None
    requests: Array[HttpAttempt] = ()
    observations: Array[ExperimentObservation] = ()

    @model_validator(mode="after")
    def validate_unique(self) -> Self:
        keys = [(o.case_id, o.repetition) for o in self.observations]
        if len(set(keys)) != len(keys):
            raise ValueError("duplicate experiment observation")
        request_keys = [(r.case_id, r.repetition, r.stage) for r in self.requests]
        if len(set(request_keys)) != len(request_keys):
            raise ValueError("retried experiment request")
        if [r.sequence for r in self.requests] != list(range(1, len(self.requests) + 1)):
            raise ValueError("request sequence is not contiguous")
        if (self.status == "RUNNING") != (self.completed_at is None):
            raise ValueError("completed time does not match collection status")
        if self.completed_at is not None and self.completed_at < self.started_at:
            raise ValueError("negative collection interval")
        gateway = "MOCK" if self.source == "MOCK" else "GMS"
        if any(stage.telemetry.gateway != gateway for o in self.observations
               for stage in ((o.run.extraction, o.run.comparison) if o.run else (o.comparison,))):
            raise ValueError("experiment source differs from observation gateway")
        return self


def make_plan(dataset, *, experiment_id, mode="PIPELINE", case_ids=None, repetitions=1,
              max_http_requests, frozen_batch: ObservationBatch | None = None,
              dataset_role="REGRESSION", human_review=None):
    cases = {c.case_id: c for c in dataset.cases}
    chosen = tuple(case_ids) if case_ids is not None else tuple(c.case_id for c in dataset.cases if c.fault is None)
    sample = next(c for c in dataset.cases if c.request.user_request.text is not None)
    pins = {
        "extraction": PromptPin.from_prompt(build_extraction_prompt(sample.request.user_request)),
        "comparison": PromptPin.from_prompt(build_comparison_prompt(sample.request.with_extracted_intent(sample.mock_extraction))),
    }
    first, second, frozen = {}, {}, {}
    if mode == "PIPELINE":
        for key in chosen:
            if cases[key].request.user_request.text is not None:
                first[key] = digest(prompt_body(build_extraction_prompt(cases[key].request.user_request)))
    else:
        if frozen_batch is not None and frozen_batch.dataset_sha256 != dataset.sha256:
            raise ValueError("frozen observations must use the same dataset")
        saved = {} if frozen_batch is None else {o.case_id: o for o in frozen_batch.observations}
        for key in chosen:
            if frozen_batch is None:
                mapped = cases[key].request.with_extracted_intent(cases[key].mock_extraction)
                clarifications = tuple(cases[key].mock_extraction.ambiguities or ())
                source = "REFERENCE_INTENT"
            else:
                extraction = saved[key].run.extraction.validate_for_request(cases[key].request)
                mapped = extraction.to_evaluation_request(cases[key].request)
                clarifications, source = extraction.clarifications, "GMS_EXTRACTED_INTENT"
            frozen[key] = FrozenComparisonInput(request=mapped, clarifications=clarifications, source=source)
            second[key] = digest(prompt_body(build_comparison_prompt(mapped, clarifications=clarifications)))
    return ExperimentPlan(
        experiment_id=experiment_id, dataset=dataset, dataset_role=dataset_role, mode=mode,
        case_ids=chosen, repetitions=repetitions, max_http_requests=max_http_requests,
        prompts=pins, extraction_input_hashes=first, comparison_input_hashes=second,
        frozen_inputs=frozen, human_review=human_review,
        frozen_source_sha256=(None if mode == "PIPELINE" else dataset.sha256 if frozen_batch is None
                              else digest(frozen_batch.model_dump(mode="json"))),
    )


def utc_now():
    return datetime.now(timezone.utc)
