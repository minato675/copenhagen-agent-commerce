"""Bounded HTTP input and request cancellation around a transport-neutral runtime."""

import asyncio
import json

from fastapi import Request, Response
from starlette.requests import ClientDisconnect

from copenhagen_fds.provider_contracts import ProviderContractError


HTTP_STATUS = {
    "INVALID_JSON": 400, "INVALID_CONTRACT": 422, "UNSUPPORTED_CONTRACT": 422,
    "INPUT_LIMIT_EXCEEDED": 413, "UNAUTHENTICATED": 401, "FORBIDDEN": 403,
    "CONFIGURATION_UNAVAILABLE": 503, "CONFIGURATION_MISMATCH": 409,
    "VERSION_CONFLICT": 409, "DEADLINE_EXCEEDED": 504,
    "PROVIDER_UNAVAILABLE": 503, "INTERNAL_ERROR": 500,
}


def error_response(code, *, status=None):
    encoded = ProviderContractError(code).error.model_dump_json().encode()
    return provider_response(encoded, status=status)


def provider_response(encoded: bytes, *, status=None, mode=None):
    payload = json.loads(encoded)
    headers = {"Cache-Control": "no-store"}
    if mode is not None:
        headers["X-FDS-Mode"] = mode
    code = payload.get("code") if payload.get("schema_version") == "fds-provider-error-v1" else None
    if code == "UNAUTHENTICATED":
        headers["WWW-Authenticate"] = "Bearer"
    return Response(encoded, status_code=status or HTTP_STATUS.get(code, 200),
                    media_type="application/json", headers=headers)


async def read_body(request: Request, *, maximum: int, seconds: float) -> bytes:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/json" or request.headers.get("content-encoding", "identity").lower() != "identity":
        from fastapi import HTTPException
        raise HTTPException(status_code=415)
    lengths = request.headers.getlist("content-length")
    declared = None
    if lengths:
        if len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit() or len(lengths[0]) > 20:
            raise ProviderContractError("INVALID_CONTRACT")
        declared = int(lengths[0])
        if declared > maximum:
            raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
    raw = bytearray()
    async with asyncio.timeout(seconds):
        async for chunk in request.stream():
            if len(raw) + len(chunk) > maximum:
                raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
            raw.extend(chunk)
    if declared is not None and declared != len(raw):
        raise ProviderContractError("INVALID_CONTRACT")
    return bytes(raw)


async def execute_connected(request: Request, runtime, raw, credential):
    async def disconnected():
        while True:
            message = await request.receive()
            if message["type"] == "http.disconnect":
                return

    execution = asyncio.create_task(runtime.execute("EVALUATE", raw, credential=credential))
    connection = asyncio.create_task(disconnected())
    try:
        done, _ = await asyncio.wait((execution, connection), return_when=asyncio.FIRST_COMPLETED)
        if connection in done:
            connection.result()
            raise ClientDisconnect
        return execution.result()
    finally:
        for task in (execution, connection):
            if not task.done():
                task.cancel()
        await asyncio.gather(execution, connection, return_exceptions=True)
