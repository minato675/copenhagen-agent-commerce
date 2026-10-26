"""Run actual provider authorization/handlers with offline scripted completions."""

from contextlib import ExitStack
from hashlib import sha256
import json
from typing import Literal
from unittest.mock import patch

from pydantic import SecretStr

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import InputModel
from copenhagen_fds.native_cli.runtime import MockScript, ScriptClient, ScriptClock, offline_clock
from copenhagen_fds.provider_cli.config import DEMO_CREDENTIAL, DemoServer, Operation
from copenhagen_fds.provider_runtime import evaluation, profile_creation, profile_update
from copenhagen_fds.provider_runtime.execution import ProviderRuntime, RESPONSE_MODELS


class DemoCall(InputModel):
    operation: Operation
    clock_start: Timestamp
    credential_mode: Literal["EXAMPLE", "MISSING", "INVALID"] = "EXAMPLE"


class DemoResource(ScriptClient):
    def __init__(self, request, script, clock, calls):
        super().__init__(request, script, clock)
        self.calls, self.closed = calls, False

    async def complete(self, prompt, *, budget):
        self.calls.append(prompt.stage)
        return await super().complete(prompt, budget=budget)

    async def aclose(self):
        self.closed = True


async def run_mock(raw: bytes, call: DemoCall, server: DemoServer, script: MockScript | None = None):
    """No expected results, environment, files, DB or paid adapter are accepted.

    Clock/network patches are process-wide: use only in a dedicated sequential
    CLI process. Fixed budget clock measures control flow, not latency or SLA.
    """
    call = DemoCall.model_validate(call.model_dump())
    server = DemoServer.model_validate(server.model_dump())
    script = None if script is None else MockScript.model_validate(script.model_dump())
    if (call.operation == "EVALUATE") != (script is not None):
        raise ValueError("evaluation requires a script; profiles must not have one")
    if script is not None and script.clock_start != call.clock_start:
        raise ValueError("script and call clocks differ")
    clock = ScriptClock(call.clock_start)
    handlers, calls, resources = [], [], []

    def counted(handler):
        async def invoke(request, context):
            handlers.append(request.operation)
            return await handler(request, context)
        return invoke

    def resource(authorized):
        if authorized.request.operation != "EVALUATE":
            return None
        if authorized.request.evaluation.execution.gateway != "MOCK":
            raise ValueError("this harness supports only MOCK")
        client = DemoResource(authorized.request.evaluation, script, clock, calls)
        resources.append(client)
        return client

    credential = {"EXAMPLE": SecretStr(DEMO_CREDENTIAL), "MISSING": None,
                  "INVALID": SecretStr("EXAMPLE_ONLY_INVALID")}[call.credential_mode]
    with ExitStack() as stack:
        stack.enter_context(offline_clock(clock, behavior_fault="NONE" if script is None else script.behavior_fault))
        for module in (evaluation, profile_creation, profile_update):
            stack.enter_context(patch.object(module, "utc_now", clock.now))
        async with ProviderRuntime(boundary=server.boundary(), handlers={
            "EVALUATE": counted(evaluation.evaluate_handler),
            "CREATE_PERSONAL_PROFILE": counted(profile_creation.create_profile_handler),
            "CREATE_POPULATION_PROFILE": counted(profile_creation.create_profile_handler),
            "UPDATE_PROFILE": counted(profile_update.update_profile_handler),
        }, timeout_seconds=server.timeout_seconds, cleanup_timeout_seconds=server.cleanup_timeout_seconds,
            max_response_bytes=server.max_response_bytes, resource_factory=resource, clock=lambda: 0.0) as provider:
            encoded = await provider.execute(call.operation, raw, credential=credential)
    data = json.loads(encoded)
    model = ProviderError if data.get("schema_version") == "fds-provider-error-v1" else RESPONSE_MODELS[call.operation]
    response = model.model_validate_json(encoded)
    return response, dict(mode="MOCK", external_api="NOT_RUN", clock="SYNTHETIC_FIXED",
        wall_duration_ms=None, handler_invocations=handlers, completion_invocations=calls,
        resources_created=len(resources), resources_closed=sum(r.closed for r in resources),
        request_sha256=sha256(raw).hexdigest(), persistence="NOT_RUN", service_integrated=False)
