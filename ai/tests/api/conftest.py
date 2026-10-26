"""In-process ASGI requests with external sockets disabled by the project fixture."""

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

from pydantic import SecretStr
import pytest

from copenhagen_fds.api.app import create_app
from copenhagen_fds.api.demo import demo_inputs
from copenhagen_fds.api.service import build_service
from copenhagen_fds.api.settings import HttpSettings


@pytest.fixture
def api_case():
    settings, request = demo_inputs(Path(__file__).parents[2] / "examples/personal-replay-settings.json")
    token = "synthetic-http-test-credential-not-a-real-secret"
    services = []

    def make_app(**changes):
        configured = HttpSettings.model_validate({**settings.model_dump(), **changes})
        def factory():
            service = build_service(configured, SecretStr(token))
            services.append(service)
            return service
        return create_app(service_factory=factory)

    return SimpleNamespace(settings=settings, request=request, token=token, services=services,
                           make_app=make_app, raw=request.model_dump_json().encode())


@pytest.fixture
def asgi_call():
    async def call(app, path="/internal/fds/evaluations", *, body=b"", headers=(), method="POST", receive=None):
        messages = []
        sent_body = False
        async def default_receive():
            nonlocal sent_body
            if not sent_body:
                sent_body = True
                return {"type": "http.request", "body": body, "more_body": False}
            await asyncio.Event().wait()
        async def send(message):
            messages.append(message)
        scope = dict(type="http", asgi={"version": "3.0", "spec_version": "2.4"},
            http_version="1.1", method=method, scheme="http", path=path, raw_path=path.encode(),
            query_string=b"", root_path="", headers=list(headers), server=("test", 80), client=("test", 1))
        await app(scope, receive or default_receive, send)
        start = next(row for row in messages if row["type"] == "http.response.start")
        raw = b"".join(row.get("body", b"") for row in messages if row["type"] == "http.response.body")
        return SimpleNamespace(status=start["status"], headers=dict(start["headers"]), raw=raw,
                               payload=json.loads(raw) if raw else None)
    return call


@pytest.fixture
def auth_headers(api_case):
    return [(b"authorization", ("Bearer " + api_case.token).encode()), (b"content-type", b"application/json")]
