"""Execute one wire request without expected outcomes or suite metadata."""

from copenhagen_fds.native_cli.runtime import MockScript
from copenhagen_fds.provider_cli.artifacts import read_bytes, reserve, write_bundle
from copenhagen_fds.provider_cli.comparison import observe
from copenhagen_fds.provider_cli.config import DemoServer
from copenhagen_fds.provider_cli.runtime import DemoCall, run_mock


async def execute_file(request_path, call_path, server_path, output, *, script_path=None):
    sources = [request_path, call_path, server_path, *([script_path] if script_path else [])]
    raw = read_bytes(request_path)
    call = DemoCall.model_validate_json(read_bytes(call_path))
    server = DemoServer.model_validate_json(read_bytes(server_path))
    script = MockScript.model_validate_json(read_bytes(script_path)) if script_path else None
    reserve(output, *sources)
    response, execution = await run_mock(raw, call, server, script)
    artifacts = {"request.json": raw, "call.json": call.model_dump(mode="json"),
        "server.json": server.model_dump(mode="json"), "response.json": response.model_dump(mode="json"),
        "execution.json": execution, "observed.json": observe(response, execution)}
    if script is not None:
        artifacts["script.json"] = script.model_dump(mode="json")
    write_bundle(output, artifacts, kind="single", expectations_read=False)
    return dict(**artifacts["observed.json"], mode="MOCK", external_api="NOT_RUN")
