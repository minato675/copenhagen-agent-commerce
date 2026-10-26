"""New-directory artifact writing and strict inventories for native CLI runs."""

import hashlib
from pathlib import Path

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.openai_settings import load_openai_settings
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.synthetic.storage import canonical, digest
from copenhagen_fds.native_cli.runtime import MockScript, fingerprint, run_request


def check_new_output(output, *sources):
    output = output.resolve()
    if output.exists():
        raise ValueError("output must be a new directory")
    for source in sources:
        source = source.resolve()
        if output == source or source in output.parents or output in source.parents:
            raise ValueError("output must not overlap an input")


def write_artifacts(output, artifacts, metadata):
    """The caller has reserved an empty directory; never overwrite any artifact."""
    hashes = {}
    for name, value in artifacts.items():
        encoded = (canonical(value)+"\n").encode("utf-8")
        with (output / name).open("xb") as stream:
            stream.write(encoded)
        hashes[name] = hashlib.sha256(encoded).hexdigest()
    manifest = {**metadata, "artifact_sha256": hashes}
    with (output / "manifest.json").open("xb") as stream:
        stream.write((canonical(manifest)+"\n").encode("utf-8"))
    return manifest


async def evaluate_file(input_path, output, *, mode="mock", script_path=None, env_file=None):
    if mode not in {"mock", "gms"} or (mode == "mock" and (script_path is None or env_file is not None)) or (
        mode == "gms" and script_path is not None):
        raise ValueError("choose explicit compatible mode options")
    sources = [input_path, *([script_path] if script_path is not None else []), *([env_file] if env_file is not None else [])]
    check_new_output(output, *sources)
    original = EngineEvaluationRequest.model_validate(read_json(input_path))
    settings = script = None
    if mode == "mock":
        script = MockScript.model_validate(read_json(script_path))
        if original.execution.gateway != "MOCK" or script.clock_start < original.feature_input.evaluation.evaluated_at:
            raise ValueError("mock input and fixed clock do not match")
        request = original
    else:
        # Environment/file supplies secrets and limits; only the explicit flag enables LIVE.
        import os
        settings = load_openai_settings({**os.environ, "FDS_MODE": "LIVE"}, env_file=env_file)
        data = original.model_dump()
        data["execution"].update(gateway="GMS", requested_model=settings.openai_model,
            provider_timeout_seconds=settings.openai_timeout_seconds,
            total_timeout_seconds=settings.evaluation_timeout_seconds)
        request = EngineEvaluationRequest.model_validate(data)
    code = fingerprint()
    # Reserve before evaluation or paid calls, including concurrent invocations.
    output.mkdir(parents=True, exist_ok=False)
    result, summary = await run_request(request, script=script, settings=settings)
    artifacts = {"source-request.json": original.model_dump(mode="json"),
        "request.json": request.model_dump(mode="json"), "result.json": result.model_dump(mode="json"), "summary.json": summary}
    if script is not None: artifacts["mock-script.json"] = script.model_dump(mode="json")
    write_artifacts(output, artifacts, dict(schema_version="native-cli-run-v1", mode=request.execution.gateway,
        code=code, expectations_read=False, secrets_exported=False))
    return summary


def read_artifacts(output):
    manifest = read_json(output / "manifest.json")
    names = {"source-request.json", "request.json", "result.json", "summary.json"}
    if manifest["mode"] == "MOCK": names.add("mock-script.json")
    elif manifest["mode"] != "GMS": raise ValueError("unsupported mode")
    if manifest["schema_version"] != "native-cli-run-v1" or set(manifest["artifact_sha256"]) != names:
        raise ValueError("unexpected artifact inventory")
    for name in names:
        if digest(output / name) != manifest["artifact_sha256"][name]:
            raise ValueError("artifact content changed")
    if manifest["code"] != fingerprint() or manifest["expectations_read"] or manifest["secrets_exported"]:
        raise ValueError("reproduction requires the recorded code/runtime and input separation")
    return manifest, {name: read_json(output / name) for name in names}
