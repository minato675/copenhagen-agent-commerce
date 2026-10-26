"""Synthetic provider fixtures; expected outcomes are separate from execution inputs."""

from copy import deepcopy
from dataclasses import dataclass

from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, evaluation_configuration_hash
from copenhagen_fds.native_cli.scenarios import demo_request, demo_script, observable_demo
from copenhagen_fds.provider_cli.config import DemoServer, demo_server
from copenhagen_fds.provider_cli.runtime import DemoCall
from copenhagen_fds.provider_runtime.boundary import request_scope


@dataclass(frozen=True)
class PreparedCase:
    request: dict
    call: DemoCall
    server: DemoServer
    script: object = None


def prepare_case(request, *, script=None):
    return PreparedCase(request.model_dump(mode="json"), DemoCall(operation=request.operation,
        clock_start=script.clock_start if script is not None else request.call.sent_at),
        demo_server(request.operation, request.configuration, request_scope(request)), script)


def evaluation_cases(settings):
    specs = [
        ("cold-normal", {}, {}, dict(status="SUCCESS", risk="LOW", amount_stage="COLD")),
        ("cold-amount", dict(amount=1_000_000), {}, dict(status="SUCCESS", risk="MEDIUM", amount_stage="COLD")),
        ("cold-combined", dict(amount=1_000_000, burst=True), {}, dict(status="SUCCESS", risk="HIGH", amount_stage="COLD")),
        ("transition", dict(profile="transition", amount=30_000), {}, dict(status="SUCCESS", amount_stage="TRANSITION")),
        ("personalized", dict(profile="full"), {}, dict(status="SUCCESS", amount_stage="PERSONALIZED")),
        ("expired-profile", dict(profile="expired"), {}, dict(status="SUCCESS", amount_stage="COLD")),
        ("gpt-mismatch", {}, dict(signal="MISMATCH"), dict(status="SUCCESS", reasons_contain=["INTENT_MISMATCH"])),
        ("gpt-unknown", {}, dict(signal="UNKNOWN"), dict(status="INCOMPLETE", reasons_contain=["INTENT_UNKNOWN"])),
        ("missing-text", dict(text=False), {}, dict(status="INCOMPLETE", calls=0)),
        ("missing-history", dict(missing=True), {}, dict(status="INCOMPLETE")),
        ("expired-baseline", dict(baseline="expired"), {}, dict(status="INCOMPLETE")),
        ("extraction-error", {}, dict(failure="extraction"), dict(status="FAILED", calls=1)),
        ("comparison-error", {}, dict(failure="comparison"), dict(status="FAILED", calls=2)),
    ]
    cases, expected = {}, {}
    for name, options, script_options, criteria in specs:
        native = demo_request(name, observable_demo(), settings, **options)
        request = ProviderEvaluationRequest(operation="EVALUATE", evaluation=native,
            call=dict(request_id="provider-" + name, trace_id=native.trace_id,
                      sent_at=native.feature_input.evaluation.evaluated_at),
            configuration=dict(configuration_id="demo-" + name, version="v1",
                               content_sha256=evaluation_configuration_hash(native)))
        cases[name] = prepare_case(request, script=demo_script(native, **script_options))
        expected[name] = dict(handlers=1, calls=2, **{k: v for k, v in criteria.items() if k != "calls"})
        expected[name]["calls"] = criteria.get("calls", 2)
    base = cases["cold-normal"]
    for mode in ("MISSING", "INVALID"):
        name = "auth-" + mode.lower()
        cases[name] = PreparedCase(deepcopy(base.request), base.call.model_copy(update=dict(credential_mode=mode)),
                                   base.server, base.script)
        expected[name] = dict(status="UNAUTHENTICATED", handlers=0, calls=0)
    cases["forbidden"] = PreparedCase(deepcopy(base.request), base.call,
        DemoServer.model_validate({**base.server.model_dump(), "grants": []}), base.script)
    expected["forbidden"] = dict(status="FORBIDDEN", handlers=0, calls=0)
    changed = base.server.model_dump()
    changed["configurations"][0]["reference"]["content_sha256"] = "0" * 64
    cases["configuration-mismatch"] = PreparedCase(deepcopy(base.request), base.call,
        DemoServer.model_validate(changed), base.script)
    expected["configuration-mismatch"] = dict(status="CONFIGURATION_MISMATCH", handlers=0, calls=0)
    return cases, expected
