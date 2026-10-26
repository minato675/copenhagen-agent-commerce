import json

import pytest

from copenhagen_fds.api.app import create_app
from copenhagen_fds.api import service as service_module
from copenhagen_fds.api.service import load_service
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse


def test_health_without_configuration_and_after_shutdown(run_async, asgi_call, api_case):
    async def scenario():
        disabled = create_app(service_factory=lambda: None)
        async with disabled.router.lifespan_context(disabled):
            assert (await asgi_call(disabled, "/health/live", method="GET")).status == 200
            assert (await asgi_call(disabled, "/health/ready", method="GET")).status == 503
            assert (await asgi_call(disabled)).status == 503
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            assert (await asgi_call(app, "/health/ready", method="GET")).payload == {"status": "READY", "mode": "MOCK"}
        assert (await asgi_call(app, "/health/ready", method="GET")).status == 503
        assert api_case.services[-1].runtime._closed
    run_async(scenario())


def test_real_fds_evaluation_through_http(run_async, asgi_call, api_case, auth_headers):
    async def scenario():
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, body=api_case.raw, headers=auth_headers)
            assert response.status == 200, response.payload
            result = ProviderEvaluationResponse.model_validate_json(response.raw).validate_for_request(api_case.request)
            assert result.outcome.status == "SUCCESS"
            assert result.storage_status == "NOT_PERSISTED"
            assert len(result.features) == 9
            assert response.headers[b"x-fds-mode"] == b"MOCK"
            assert response.headers[b"cache-control"] == b"no-store"
            assert api_case.request.evaluation.analysis.user_request.text.encode() not in response.raw
    run_async(scenario())


@pytest.mark.parametrize("authorization", [None, b"Bearer invalid-private-token", b"Basic invalid-private-token"])
def test_authentication_precedes_body_read(run_async, asgi_call, api_case, authorization):
    async def scenario():
        async def must_not_read():
            pytest.fail("unauthorized requests must not read bodies")
        app = api_case.make_app()
        headers = [] if authorization is None else [(b"authorization", authorization)]
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=headers, receive=must_not_read)
            assert response.status == 401
            assert response.payload["code"] == "UNAUTHENTICATED"
            assert response.headers[b"www-authenticate"] == b"Bearer"
            assert b"private-token" not in response.raw
    run_async(scenario())


@pytest.mark.parametrize("raw, code, status", [
    (b'{"private-text":', "INVALID_JSON", 400),
    (b'{"schema_version":"fds-provider-v1","operation":"EVALUATE","private":"input"}', "INVALID_CONTRACT", 422),
    (b'{"schema_version":"unknown"}', "UNSUPPORTED_CONTRACT", 422),
    (b'{"schema_version":"fds-provider-v1","schema_version":"fds-provider-v1"}', "INVALID_JSON", 400),
])
def test_sanitized_input_errors(run_async, asgi_call, api_case, auth_headers, raw, code, status):
    async def scenario():
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, body=raw, headers=auth_headers)
            assert (response.status, response.payload["code"]) == (status, code)
            assert b"private" not in response.raw
    run_async(scenario())


@pytest.mark.parametrize("change, status, code", [
    ({"grants": [{"user_ids": [], "profile_ids": [], "cohort_ids": []}]}, 403, "FORBIDDEN"),
    ({"configurations": [{"configuration_id": "http-demo", "version": "v1", "content_sha256": "0" * 64}]}, 409, "CONFIGURATION_MISMATCH"),
])
def test_trusted_scope_and_configuration(run_async, asgi_call, api_case, auth_headers, change, status, code):
    async def scenario():
        app = api_case.make_app(**change)
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, body=api_case.raw, headers=auth_headers)
            assert (response.status, response.payload["code"]) == (status, code)
    run_async(scenario())


def test_openapi_request_refs_resolve_without_runtime_credentials(api_case):
    schema = api_case.make_app().openapi()
    encoded = json.dumps(schema)
    assert api_case.token not in encoded
    def walk(value):
        if isinstance(value, dict):
            if "$ref" in value:
                ref = schema
                for part in value["$ref"].removeprefix("#/").split("/"):
                    ref = ref[part]
            for item in value.values():
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(schema)
    route = schema["paths"]["/internal/fds/evaluations"]["post"]
    assert route["requestBody"]["required"]
    assert route["security"] == [{"HTTPBearer": []}]


def test_gms_mode_rejects_mock_execution_before_network(run_async, asgi_call, api_case, auth_headers, tmp_path):
    data = api_case.settings.model_dump(mode="json")
    data.update(mode="GMS", mock=None)
    path = tmp_path / "server.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    environment = {"AI_HTTP_CONFIG": str(path), "AI_HTTP_SERVICE_TOKEN": api_case.token,
        "FDS_MODE": "LIVE", "GMS_KEY": "synthetic-not-a-real-gms-key",
        "FDS_OPENAI_TIMEOUT_SECONDS": "10", "FDS_EVALUATION_TIMEOUT_SECONDS": "60"}
    async def scenario():
        app = create_app(service_factory=lambda: load_service(environment))
        async with app.router.lifespan_context(app):
            assert (await asgi_call(app, "/health/ready", method="GET")).payload["mode"] == "GMS"
            response = await asgi_call(app, body=api_case.raw, headers=auth_headers)
            assert (response.status, response.payload["code"]) == (409, "CONFIGURATION_MISMATCH")
    run_async(scenario())


def test_internal_exception_is_not_reflected(run_async, asgi_call, api_case, auth_headers, monkeypatch):
    async def broken(request, context):
        raise RuntimeError(api_case.token + " private-source-text")
    monkeypatch.setattr(service_module, "evaluate_handler", broken)
    async def scenario():
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, body=api_case.raw, headers=auth_headers)
            assert (response.status, response.payload["code"]) == (500, "INTERNAL_ERROR")
            assert api_case.token.encode() not in response.raw
            assert b"private-source-text" not in response.raw
    run_async(scenario())
