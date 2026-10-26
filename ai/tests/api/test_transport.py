import asyncio

import pytest

from copenhagen_fds.api import service as service_module


@pytest.mark.parametrize("extra", [
    [(b"content-type", b"text/plain")],
    [(b"content-type", b"application/json"), (b"content-encoding", b"gzip")],
])
def test_unsupported_body_format(run_async, asgi_call, api_case, auth_headers, extra):
    async def scenario():
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=auth_headers[:1] + extra, body=api_case.raw)
            assert response.status == 415
    run_async(scenario())


def test_duplicate_authentication_is_rejected(run_async, asgi_call, api_case, auth_headers):
    async def scenario():
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=auth_headers + auth_headers[:1], body=api_case.raw)
            assert response.status == 401
    run_async(scenario())


@pytest.mark.parametrize("declared", [True, False])
def test_size_limit_before_or_during_stream(run_async, asgi_call, api_case, auth_headers, declared):
    async def scenario():
        app = api_case.make_app(limits={**api_case.settings.limits.model_dump(), "max_body_bytes": 8})
        chunks = iter((b"12345", b"67890"))
        async def receive():
            if declared:
                pytest.fail("declared oversized body must not be read")
            return {"type": "http.request", "body": next(chunks), "more_body": True}
        headers = auth_headers + ([(b"content-length", b"10")] if declared else [])
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=headers, receive=receive)
            assert (response.status, response.payload["code"]) == (413, "INPUT_LIMIT_EXCEEDED")
            assert api_case.services[-1].active == 0
    run_async(scenario())


@pytest.mark.parametrize("lengths", [[b"-1"], [b"1", b"1"], [b"1"]])
def test_invalid_or_mismatched_content_length(run_async, asgi_call, api_case, auth_headers, lengths):
    async def scenario():
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=auth_headers + [(b"content-length", v) for v in lengths], body=b"{}")
            assert response.status == 422
    run_async(scenario())


def test_slow_body_has_its_own_deadline(run_async, asgi_call, api_case, auth_headers):
    async def scenario():
        app = api_case.make_app(body_read_timeout_seconds=0.01)
        async def stalled():
            await asyncio.Event().wait()
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=auth_headers, receive=stalled)
            assert (response.status, response.payload["code"]) == (408, "DEADLINE_EXCEEDED")
            assert api_case.services[-1].active == 0
    run_async(scenario())


def test_runtime_timeout_closes_analysis_resource(run_async, asgi_call, api_case, auth_headers, monkeypatch):
    async def stalled(request, context):
        await asyncio.Event().wait()
    monkeypatch.setattr(service_module, "evaluate_handler", stalled)
    async def scenario():
        app = api_case.make_app(timeout_seconds=1.0)
        resources = []
        async with app.router.lifespan_context(app):
            runtime = api_case.services[-1].runtime
            original = runtime._factory
            def factory(authorized):
                resource = original(authorized)
                resources.append(resource)
                return resource
            runtime._factory = factory
            response = await asgi_call(app, headers=auth_headers, body=api_case.raw)
            assert (response.status, response.payload["code"]) == (504, "DEADLINE_EXCEEDED")
            assert resources and resources[0]._closed
    run_async(scenario())


def test_disconnect_cancels_execution_and_releases_capacity(run_async, asgi_call, api_case, auth_headers, monkeypatch):
    async def scenario():
        started, cancelled = asyncio.Event(), asyncio.Event()
        async def stalled(request, context):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        monkeypatch.setattr(service_module, "evaluate_handler", stalled)
        app = api_case.make_app()
        sent = False
        async def receive():
            nonlocal sent
            if not sent:
                sent = True
                return {"type": "http.request", "body": api_case.raw, "more_body": False}
            await started.wait()
            return {"type": "http.disconnect"}
        async with app.router.lifespan_context(app):
            response = await asgi_call(app, headers=auth_headers, receive=receive)
            assert response.status == 499
            assert cancelled.is_set()
            assert api_case.services[-1].active == 0
            assert not api_case.services[-1].runtime._active
    run_async(scenario())


def test_admission_limit_rejects_without_queuing_and_then_recovers(run_async, asgi_call, api_case, auth_headers, monkeypatch):
    async def scenario():
        started, release = asyncio.Event(), asyncio.Event()
        original = service_module.evaluate_handler
        async def delayed(request, context):
            started.set()
            await release.wait()
            return await original(request, context)
        monkeypatch.setattr(service_module, "evaluate_handler", delayed)
        app = api_case.make_app(max_concurrent_evaluations=1)
        async with app.router.lifespan_context(app):
            first = asyncio.create_task(asgi_call(app, headers=auth_headers, body=api_case.raw))
            try:
                await asyncio.wait_for(started.wait(), timeout=5)
                rejected = await asgi_call(app, headers=auth_headers, body=api_case.raw)
                assert rejected.status == 503
            finally:
                release.set()
            assert (await first).status == 200
            assert (await asgi_call(app, headers=auth_headers, body=api_case.raw)).status == 200
            assert api_case.services[-1].active == 0
    run_async(scenario())


def test_shutdown_cancels_active_runtime(run_async, asgi_call, api_case, auth_headers, monkeypatch):
    async def scenario():
        started, cancelled = asyncio.Event(), asyncio.Event()
        async def stalled(request, context):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        monkeypatch.setattr(service_module, "evaluate_handler", stalled)
        app = api_case.make_app()
        async with app.router.lifespan_context(app):
            pending = asyncio.create_task(asgi_call(app, headers=auth_headers, body=api_case.raw))
            await asyncio.wait_for(started.wait(), timeout=5)
        with pytest.raises(asyncio.CancelledError):
            await pending
        assert cancelled.is_set()
        assert api_case.services[-1].active == 0
    run_async(scenario())
