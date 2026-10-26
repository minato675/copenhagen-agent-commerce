"""Sequential stage recording; no scheduler, environment loader or policy execution."""

from datetime import datetime, timezone

from copenhagen_fds.contracts.engine_execution import EXECUTION_STAGES, EngineExecutionResult, ExecutionStage, ExecutionStageName, validate_execution_evidence
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class EngineStageRecorder:
    def __init__(self, request: EngineEvaluationRequest):
        self.request = request
        self.started_at = utc_now()
        self.reference = EngineRequestReference.from_request(request)
        self.stages = {name: ExecutionStage(stage=name, status="NOT_RUN", skip_reason="NOT_REQUESTED")
                       for name in EXECUTION_STAGES}
        self.results = dict.fromkeys(EXECUTION_STAGES)
        self.active = None
        self.active_started_at = None

    def start(self, name: ExecutionStageName) -> None:
        self.active, self.active_started_at = name, utc_now()

    def finish(self, result):
        name = self.active
        status = result.evaluation.result.behavior.status if name == "BEHAVIOR" else result.status
        failure = ("CALCULATION_FAILED" if status == "FAILED" else None) if name == "BEHAVIOR" else (
            None if result.failure is None else result.failure.kind.value)
        stage = ExecutionStage(stage=name, status=status.value,
            started_at=None if status == "NOT_RUN" else self.active_started_at,
            completed_at=None if status == "NOT_RUN" else utc_now(), failure_code=failure,
            skip_reason=("MISSING_INPUT" if name == "EXTRACTION" else "UPSTREAM_FAILED") if status == "NOT_RUN" else None)
        validate_execution_evidence(self.request, stage, result)
        self.stages[name], self.results[name] = stage, result
        self.active = None

    def fail(self, code):
        self.stages[self.active] = ExecutionStage(stage=self.active, status="FAILED",
            started_at=self.active_started_at, completed_at=utc_now(), failure_code=code)
        self.active = None

    def cancel(self):
        if self.active is not None:
            self.stages[self.active] = ExecutionStage(stage=self.active, status="CANCELLED",
                started_at=self.active_started_at, completed_at=utc_now())
            self.active = None
        self.skip_remaining("CANCELLED")

    def skip_remaining(self, reason):
        for name, stage in self.stages.items():
            if stage.skip_reason == "NOT_REQUESTED":
                self.stages[name] = ExecutionStage(stage=name, status="NOT_RUN", skip_reason=reason)

    def snapshot(self, *, budget_seconds: float, elapsed_seconds: float, termination: str) -> EngineExecutionResult:
        data = dict(request=self.request, reference=self.reference, started_at=self.started_at,
            completed_at=utc_now(), budget_seconds=budget_seconds, elapsed_seconds=elapsed_seconds,
            termination=termination, stages=tuple(self.stages.values()), behavior=self.results["BEHAVIOR"],
            extraction=self.results["EXTRACTION"], comparison=self.results["COMPARISON"])
        draft = EngineExecutionResult.model_construct(**data)
        return EngineExecutionResult(**data, status=draft.expected_status())
