"""Execute real analysis stages with scripted completions or an explicit GMS adapter."""

from contextlib import ExitStack, contextmanager
from datetime import datetime, timedelta
from importlib.metadata import version
import time
from typing import Literal
from unittest.mock import patch

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.openai_adapter import OpenAIAnalysisAdapter
from copenhagen_fds.analysis.transport_models import StructuredCompletion
from copenhagen_fds.contracts import AnalysisFailure, IntentComparisonOutput, IntentExtractionOutput
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import InputModel
from copenhagen_fds.evaluation.offline import deny_network
from copenhagen_fds.native_evaluation import evaluate_fraud
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.personal_replay_export import code_fingerprint
from copenhagen_fds.validation_memo import scoped_validation_memo


class MockScript(InputModel):
    """Provider payloads are simulation inputs, never expected final FDS decisions."""
    clock_start: Timestamp
    extraction: IntentExtractionOutput | AnalysisFailure
    comparison: IntentComparisonOutput | AnalysisFailure
    behavior_fault: Literal["NONE", "INTERNAL_ERROR"] = "NONE"


class ScriptClock:
    def __init__(self, start):
        self.value = start

    def now(self):
        self.value += timedelta(microseconds=1)
        return self.value


@contextmanager
def offline_clock(clock, *, behavior_fault="NONE"):
    """Process-scoped replay harness, like the existing offline network guard.

    Only for a dedicated, sequential CLI process, never a concurrent server.
    Patches restore on every exit; no production clock or provider code changes.
    """
    from copenhagen_fds import engine, engine_stages, engine_finalization
    from copenhagen_fds.analysis import intent_extractor, intent_comparator

    class ScriptDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            value = clock.now()
            return value.astimezone(tz) if tz is not None else value.replace(tzinfo=None)

    with ExitStack() as stack:
        stack.enter_context(deny_network())
        for module in (engine, engine_stages, engine_finalization):
            stack.enter_context(patch.object(module, "utc_now", clock.now))
        for module in (intent_extractor, intent_comparator):
            stack.enter_context(patch.object(module, "datetime", ScriptDatetime))
        if behavior_fault == "INTERNAL_ERROR":
            stack.enter_context(patch.object(engine, "evaluate_engine_behavior", side_effect=RuntimeError("scripted behavior failure")))
        yield


class ScriptClient:
    def __init__(self, request, script, clock):
        config = request.execution
        self.settings = OpenAISettings(mode="MOCK", openai_model=config.requested_model,
            openai_timeout_seconds=config.provider_timeout_seconds,
            evaluation_timeout_seconds=config.total_timeout_seconds)
        self.script, self.clock = script, clock

    async def complete(self, prompt, *, budget):
        value = getattr(self.script, prompt.stage)
        failed = isinstance(value, AnalysisFailure)
        return StructuredCompletion(stage=prompt.stage, status="FAILED" if failed else "SUCCESS",
            payload=None if failed else value, failure=value if failed else None,
            telemetry=dict(gateway="MOCK", requested_model=self.settings.openai_model),
            response_model=None if failed else "native-script-model-v1", prompt_version=prompt.version,
            completed_at=self.clock.now())


class CountedClient:
    def __init__(self, client):
        self.client, self.settings, self.calls = client, client.settings, []

    async def complete(self, prompt, *, budget):
        self.calls.append(prompt.stage)
        return await self.client.complete(prompt, budget=budget)


def fingerprint():
    return {**code_fingerprint(), "dependencies": {name: version(name) for name in
        ("openai", "httpx2", "iso4217", "python-dotenv")}}


def summarize(result):
    execution, policy = result.execution, result.policy_result
    behavior = execution.behavior
    features = [] if behavior is None else [dict(feature_id=f.combined.feature_id,
        stage=f.stage, status=f.combined.status.value,
        score=None if f.combined.score is None else f.combined.score.value,
        personal_weight=f.personal_weight, reasons=list(f.stage_reasons))
        for f in behavior.evaluation.result.features]
    return dict(outcome=result.outcome.model_dump(mode="json"), termination=result.termination,
        behavior_score=None if policy is None or policy.decision.behavior_score is None else policy.decision.behavior_score.value,
        features=features, stages=[s.model_dump(mode="json") for s in (*execution.stages, result.policy_stage)],
        reasons=[] if policy is None else [r.model_dump(mode="json") for r in policy.decision.reasons],
        analysis={name: None if getattr(execution,name) is None else dict(
            status=getattr(execution,name).status.value,
            signal=(getattr(execution,name).payload.comparison.value if name == "comparison" and
                getattr(execution,name).payload is not None else None),
            telemetry=getattr(execution,name).telemetry.model_dump(mode="json"),
            failure=None if getattr(execution,name).failure is None else getattr(execution,name).failure.model_dump(mode="json"))
            for name in ("extraction", "comparison")})


@scoped_validation_memo
async def run_request(request, *, script=None, settings=None):
    """Caller chooses exactly one mode. This is a sequential standalone harness."""
    request = EngineEvaluationRequest.model_validate(request.model_dump())
    before = request.model_dump_json()
    if (script is None) == (settings is None):
        raise ValueError("choose exactly one execution mode")
    if script is not None:
        script = MockScript.model_validate(script.model_dump())
        if request.execution.gateway != "MOCK" or script.clock_start < request.feature_input.evaluation.evaluated_at:
            raise ValueError("mock execution requires its own gateway and a valid fixed clock")
        clock = ScriptClock(script.clock_start)
        client = CountedClient(ScriptClient(request, script, clock))
        with offline_clock(clock, behavior_fault=script.behavior_fault):
            result = await evaluate_fraud(request, client,
                budget=AnalysisBudget.start(request.execution.total_timeout_seconds, clock=lambda: 0.0))
        measurement = dict(clock="SYNTHETIC_FIXED", wall_duration_ms=None, external_api="NOT_RUN")
    else:
        if request.execution.gateway != "GMS" or settings.mode != "LIVE":
            raise ValueError("live execution requires explicit GMS/LIVE settings")
        started = time.perf_counter()
        async with OpenAIAnalysisAdapter(settings) as adapter:
            client = CountedClient(adapter)
            result = await evaluate_fraud(request, client)
        measurement = dict(clock="REAL", wall_duration_ms=(time.perf_counter()-started)*1000,
            external_api="ATTEMPTED" if client.calls else "NOT_RUN")
    if before != request.model_dump_json():
        raise ValueError("evaluation modified the supplied request or published profiles")
    summary = summarize(result)
    summary.update(measurement, mode=request.execution.gateway, completion_invocations=list(client.calls),
        profile_unchanged=True, request_sha256=personalization_hash(request),
        result_sha256=personalization_hash(result), policy_sha256=personalization_hash(request.risk_policy),
        learning_update="NOT_RUN", service_integrated=False)
    return result, summary
