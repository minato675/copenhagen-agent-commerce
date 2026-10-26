"""FastAPI factory; full engine DTOs are internal and not a finalized Core API."""

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import SecretStr
from starlette.requests import ClientDisconnect

from copenhagen_fds.api.service import load_service
from copenhagen_fds.api.transport import HTTP_STATUS, error_response, execute_connected, provider_response, read_body
from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, ProviderEvaluationResponse
from copenhagen_fds.provider_contracts import ProviderContractError


def create_app(*, service_factory=load_service) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app):
        service = service_factory()
        app.state.service = service
        try:
            yield
        finally:
            app.state.service = None
            if service is not None:
                await service.runtime.aclose()

    app = FastAPI(title="FDS internal evaluation API", version="0.1.0", lifespan=lifespan,
        description="Standalone adapter. HTTP success is not payment approval; results are not persisted.")
    app.state.service = None
    bearer = HTTPBearer(auto_error=False)

    @app.get("/health/live", tags=["health"])
    async def live():
        return {"status": "UP"}

    @app.get("/health/ready", tags=["health"], responses={503: {"description": "Not configured or shutting down"}})
    async def ready():
        service = app.state.service
        if service is None:
            return error_response("PROVIDER_UNAVAILABLE")
        return {"status": "READY", "mode": service.settings.mode}

    @app.post("/internal/fds/evaluations", tags=["evaluation"], response_model=ProviderEvaluationResponse,
        responses={status: {"model": ProviderError} for status in {*HTTP_STATUS.values(), 408, 415}},
        openapi_extra={"requestBody": {"required": True, "content": {"application/json": {
            "schema": {"$ref": "#/components/schemas/ProviderEvaluationRequest"}}}}})
    async def evaluate(request: Request, auth: HTTPAuthorizationCredentials | None = Depends(bearer)):
        service = app.state.service
        if service is None:
            return error_response("PROVIDER_UNAVAILABLE")
        admitted = False
        try:
            if auth is None or len(request.headers.getlist("authorization")) != 1:
                raise ProviderContractError("UNAUTHENTICATED")
            credential = SecretStr(auth.credentials)
            service.authenticator.authenticate(credential)
            if service.active >= service.settings.max_concurrent_evaluations:
                return error_response("PROVIDER_UNAVAILABLE")
            service.active += 1
            admitted = True
            raw = await read_body(request, maximum=service.settings.limits.max_body_bytes,
                                  seconds=service.settings.body_read_timeout_seconds)
            encoded = await execute_connected(request, service.runtime, raw, credential)
            return provider_response(encoded, mode=service.settings.mode)
        except ProviderContractError as error:
            return error_response(error.error.code)
        except TimeoutError:
            return error_response("DEADLINE_EXCEEDED", status=408)
        except HTTPException:
            return error_response("INVALID_CONTRACT", status=415)
        except ClientDisconnect:
            # The peer is gone; cancellation and owned resource cleanup have completed.
            return Response(status_code=499)
        except Exception:
            return error_response("INTERNAL_ERROR")
        finally:
            if admitted:
                service.active -= 1

    original_openapi = app.openapi

    def openapi():
        if app.openapi_schema is None:
            schema = original_openapi()
            request_schema = ProviderEvaluationRequest.model_json_schema(
                ref_template="#/components/schemas/Request_{model}")
            definitions = request_schema.pop("$defs", {})
            components = schema.setdefault("components", {}).setdefault("schemas", {})
            components.update({"Request_" + key: value for key, value in definitions.items()})
            components["ProviderEvaluationRequest"] = request_schema
            app.openapi_schema = schema
        return app.openapi_schema

    app.openapi = openapi
    return app
