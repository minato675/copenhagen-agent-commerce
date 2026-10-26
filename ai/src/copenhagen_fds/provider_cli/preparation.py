"""Generate synthetic provider requests, schemas and separate assertions."""

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.native_cli.runtime import MockScript
from copenhagen_fds.native_cli.scenarios import demo_settings
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.provider_contracts import CONTRACT_MODELS
from copenhagen_fds.provider_cli.artifacts import read_bytes, reserve, write_bundle
from copenhagen_fds.provider_cli.config import DemoServer
from copenhagen_fds.provider_cli.profile_scenarios import profile_cases
from copenhagen_fds.provider_cli.runtime import DemoCall
from copenhagen_fds.provider_cli.scenarios import evaluation_cases


def prepare(settings_path, output):
    # Validate and protect the original before deriving demonstrative readiness settings.
    read_bytes(settings_path)
    original = PersonalReplaySettings.model_validate(read_json(settings_path))
    settings = demo_settings(original)
    evaluations, eval_expected = evaluation_cases(settings)
    profiles, profile_expected = profile_cases(settings)
    cases = {**evaluations, **profiles}
    artifacts = {"suite.json": dict(schema_version="fds-provider-demo-suite-v1", cases=list(cases)),
        "expected.json": {**eval_expected, **profile_expected}, "source-settings.json": original.model_dump(mode="json"),
        "demo-settings.json": settings.model_dump(mode="json")}
    for name, case in cases.items():
        artifacts[name + ".request.json"] = case.request
        artifacts[name + ".call.json"] = case.call.model_dump(mode="json")
        artifacts[name + ".server.json"] = case.server.model_dump(mode="json")
        if case.script is not None:
            artifacts[name + ".script.json"] = case.script.model_dump(mode="json")
    for name, model in {**CONTRACT_MODELS, "DemoServer": DemoServer, "DemoCall": DemoCall, "MockScript": MockScript}.items():
        artifacts[name + ".schema.json"] = model.model_json_schema()
    reserve(output, settings_path)
    write_bundle(output, artifacts, kind="prepared", origin="SYNTHETIC", evaluated=False)
    return dict(status="PREPARED", cases=len(cases), evaluated=False, external_api="NOT_RUN")
