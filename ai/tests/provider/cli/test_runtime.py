"""304: standalone execution actually crosses the provider boundary."""

from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, evaluation_configuration_hash
from copenhagen_fds.native_cli.scenarios import demo_request, demo_script, demo_settings, observable_demo
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.provider_cli.config import demo_server
from copenhagen_fds.provider_cli.runtime import DemoCall, run_mock
from copenhagen_fds.provider_runtime.boundary import request_scope


def evaluation_case():
    settings = demo_settings(PersonalReplaySettings.model_validate(read_json(Path('examples/personal-replay-settings.json'))))
    native = demo_request("cli", observable_demo(), settings)
    request = ProviderEvaluationRequest(operation="EVALUATE", evaluation=native,
        call=dict(request_id="demo", trace_id=native.trace_id, sent_at=native.feature_input.evaluation.evaluated_at),
        configuration=dict(configuration_id="demo", version="v1", content_sha256=evaluation_configuration_hash(native)))
    script = demo_script(native)
    return request, DemoCall(operation="EVALUATE", clock_start=script.clock_start), demo_server(
        request.operation, request.configuration, request_scope(request)), script


def test_actual_handler_response_and_cleanup(run_async):
    request, call, server, script = evaluation_case()
    raw = request.model_dump_json().encode()
    response, execution = run_async(run_mock(raw, call, server, script))
    response.validate_for_request(request)
    assert response.outcome.status == "SUCCESS"
    assert execution["handler_invocations"] == ["EVALUATE"]
    assert execution["completion_invocations"] == ["extraction", "comparison"]
    assert execution["resources_created"] == execution["resources_closed"] == 1
    assert execution["external_api"] == "NOT_RUN"
    assert raw == request.model_dump_json().encode()


@pytest.mark.parametrize("mode", ["MISSING", "INVALID"])
def test_authentication_precedes_json_and_engine(run_async, mode):
    _, call, server, script = evaluation_case()
    response, execution = run_async(run_mock(b"invalid private input", call.model_copy(
        update=dict(credential_mode=mode)), server, script))
    assert response.code == "UNAUTHENTICATED"
    assert execution["handler_invocations"] == execution["completion_invocations"] == []
    assert execution["resources_created"] == 0
    assert "private" not in response.model_dump_json()


def test_config_not_auto_trusted_from_request(run_async):
    request, call, server, script = evaluation_case()
    raw = request.model_dump_json().replace('"configuration_id":"demo"', '"configuration_id":"other"').encode()
    response, execution = run_async(run_mock(raw, call, server, script))
    assert response.code == "CONFIGURATION_UNAVAILABLE"
    assert execution["handler_invocations"] == []


def test_offline_guard_and_clock_restore(run_async, monkeypatch):
    import socket
    from copenhagen_fds.evaluation.offline import OfflineViolation
    from copenhagen_fds.provider_runtime import evaluation
    request, call, server, script = evaluation_case()
    original_clock, original_connect = evaluation.utc_now, socket.create_connection
    async def unsafe(*args):
        socket.create_connection(("example.com", 443))
    monkeypatch.setattr(evaluation, "evaluate_handler", unsafe)
    with pytest.raises(OfflineViolation):
        run_async(run_mock(request.model_dump_json().encode(), call, server, script))
    assert evaluation.utc_now is original_clock
    assert socket.create_connection is original_connect
