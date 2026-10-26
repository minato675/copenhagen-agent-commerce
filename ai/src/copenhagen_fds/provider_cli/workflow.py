"""Execute and reproduce complete suites before reading their expected outcomes."""

import json

from copenhagen_fds.native_cli.experiments import case_ids
from copenhagen_fds.native_cli.runtime import MockScript
from copenhagen_fds.provider_cli.artifacts import read_bundle, read_verified, reserve, write_bundle
from copenhagen_fds.provider_cli.comparison import compare, observe
from copenhagen_fds.provider_cli.config import DemoServer
from copenhagen_fds.provider_cli.runtime import DemoCall, run_mock


def suite_ids(values):
    suite = json.loads(values["suite.json"])
    if set(suite) != {"schema_version", "cases"} or suite["schema_version"] != "fds-provider-demo-suite-v1":
        raise ValueError("unsupported suite")
    return case_ids(suite["cases"])


async def execute_cases(values):
    artifacts, observed = {}, {}
    for name in suite_ids(values):
        call = DemoCall.model_validate_json(values[name + ".call.json"])
        server = DemoServer.model_validate_json(values[name + ".server.json"])
        script_name = name + ".script.json"
        script = MockScript.model_validate_json(values[script_name]) if script_name in values else None
        response, execution = await run_mock(values[name + ".request.json"], call, server, script)
        artifacts[name + ".response.json"] = response.model_dump(mode="json")
        artifacts[name + ".execution.json"] = execution
        observed[name] = observe(response, execution)
    return artifacts, observed


async def run_suite(source, output):
    manifest, values = read_bundle(source, kind="prepared", defer_expected=True)
    reserve(output, source)
    artifacts, observed = await execute_cases(values)
    # Input mutation during a run invalidates it; no updated source is silently accepted.
    for name in values:
        read_verified(source, name, manifest)
    expected_raw = read_verified(source, "expected.json", manifest)
    report = compare(observed, json.loads(expected_raw))
    write_bundle(output, {**values, **artifacts, "expected.json": expected_raw, "comparisons.json": report},
        kind="suite-run", expectations_read_after_execution=True, evaluated=True)
    return dict(status=report["status"], cases=len(observed), mode="MOCK", external_api="NOT_RUN",
                contract_failures=sum(not row["passed"] for row in report["rows"]))


def comparable_response(value):
    value = dict(value)
    if value.get("schema_version") == "fds-provider-error-v1":
        value.pop("error_id", None)  # A fresh server-generated UUID is intentionally nondeterministic.
    return value


async def verify_suite(source):
    manifest, values = read_bundle(source, kind="suite-run", defer_expected=True)
    if manifest.get("expectations_read_after_execution") is not True or manifest.get("evaluated") is not True:
        raise ValueError("result does not declare completed, separated execution")
    artifacts, observed = await execute_cases(values)
    for name, actual in artifacts.items():
        recorded = json.loads(values[name])
        if name.endswith(".response.json"):
            actual, recorded = comparable_response(actual), comparable_response(recorded)
        if actual != recorded:
            raise ValueError("provider execution did not reproduce recorded output")
    expected = json.loads(read_verified(source, "expected.json", manifest))
    report = compare(observed, expected)
    if report != json.loads(values["comparisons.json"]):
        raise ValueError("comparison report did not reproduce")
    return dict(status="REPRODUCED", scenario_status=report["status"], cases=len(observed), external_api="NOT_RUN")
