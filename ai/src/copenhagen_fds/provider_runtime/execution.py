"""Injected async operations with one request budget and owned analysis resources."""

import asyncio
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from math import isfinite
import time
from types import MappingProxyType
from typing import Protocol, get_args

from pydantic import SecretStr

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.transport_models import CompletionPrompt, StructuredCompletion
from copenhagen_fds.contracts.provider_errors import ProviderErrorCode
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateResponse, ProviderProfileUpdateResponse
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.access import OPERATIONS, ServiceIdentity
from copenhagen_fds.provider_runtime.boundary import AuthorizedRequest, ProviderBoundary, ProviderRequest


ProviderResponse = ProviderEvaluationResponse | ProviderProfileCreateResponse | ProviderProfileUpdateResponse
RESPONSE_MODELS = MappingProxyType({
    "EVALUATE": ProviderEvaluationResponse,
    "CREATE_PERSONAL_PROFILE": ProviderProfileCreateResponse,
    "CREATE_POPULATION_PROFILE": ProviderProfileCreateResponse,
    "UPDATE_PROFILE": ProviderProfileUpdateResponse,
})


class AnalysisResource(Protocol):
    settings: OpenAISettings
    async def complete(self, prompt: CompletionPrompt, *, budget: AnalysisBudget) -> StructuredCompletion: ...
    async def aclose(self) -> None: ...


def _positive_seconds(value):
    if type(value) not in (int, float) or not isfinite(value) or value <= 0:
        raise ValueError("explicit positive finite execution limits are required")
    return float(value)


def _error_json(code):
    # Rebuild rather than serialize exceptions supplied by an injected handler.
    if code not in get_args(ProviderErrorCode):
        code = "INTERNAL_ERROR"
    return ProviderContractError(code).error.model_dump_json().encode("utf-8")


@dataclass
class _RequestLifetime:
    active: bool = True


@dataclass(frozen=True)
class ExecutionContext:
    identity: ServiceIdentity
    budget: AnalysisBudget
    _resource: AnalysisResource | None = field(repr=False)
    _lifetime: _RequestLifetime = field(repr=False)

    @property
    def settings(self) -> OpenAISettings:
        """Expose the existing CompletionClient interface without exposing its client."""
        self.checkpoint()
        if self._resource is None or not isinstance(self._resource.settings, OpenAISettings):
            raise ProviderContractError("CONFIGURATION_UNAVAILABLE")
        return OpenAISettings.model_validate(self._resource.settings.model_dump())

    def checkpoint(self):
        if not self._lifetime.active:
            raise ProviderContractError("PROVIDER_UNAVAILABLE")
        task = asyncio.current_task()
        if task is not None and task.cancelling():
            raise asyncio.CancelledError
        if self.budget.remaining_seconds <= 0:
            raise ProviderContractError("DEADLINE_EXCEEDED")

    async def complete(self, prompt: CompletionPrompt, *, budget: AnalysisBudget) -> StructuredCompletion:
        """Adapter-compatible call; a handler cannot replace the shared budget."""
        self.checkpoint()
        if budget is not self.budget:
            raise ProviderContractError("CONFIGURATION_MISMATCH")
        if self._resource is None:
            raise ProviderContractError("PROVIDER_UNAVAILABLE")
        result = await self._resource.complete(prompt, budget=self.budget)
        self.checkpoint()
        return result


async def _release(resource: AnalysisResource, seconds: float) -> tuple[bool, bool]:
    """Drain bounded cooperative cleanup even if the caller is cancelled again."""
    async def close():
        async with asyncio.timeout(seconds):
            await resource.aclose()

    cleanup = asyncio.create_task(close())
    cancelled = False
    while not cleanup.done():
        try:
            await asyncio.shield(cleanup)
        except asyncio.CancelledError:
            caller = asyncio.current_task()
            if caller is not None and caller.cancelling():
                cancelled = True
        except Exception:
            break
    try:
        cleanup.result()
    except (Exception, asyncio.CancelledError):
        return cancelled, True
    return cancelled, False


Handler = Callable[[ProviderRequest, ExecutionContext], Awaitable[ProviderResponse]]
ResourceFactory = Callable[[AuthorizedRequest], AnalysisResource | None]


class ProviderRuntime:
    """One event-loop-owned runtime; no network listener or business defaults.

    Each registered factory must return a fresh resource owned by that request.
    Factories are synchronous constructors, not blocking network/model calls.
    """

    def __init__(self, *, boundary: ProviderBoundary, handlers: Mapping[str, Handler],
                 timeout_seconds: float, cleanup_timeout_seconds: float, max_response_bytes: int,
                 resource_factory: ResourceFactory | None = None,
                 clock: Callable[[], float] = time.monotonic):
        self._timeout = _positive_seconds(timeout_seconds)
        self._cleanup_timeout = _positive_seconds(cleanup_timeout_seconds)
        if type(max_response_bytes) is not int or max_response_bytes <= 0:
            raise ValueError("explicit positive response byte limit is required")
        if any(operation not in OPERATIONS or not callable(handler) for operation, handler in handlers.items()):
            raise ValueError("registered operations require callable handlers")
        self._boundary = boundary
        self._handlers = MappingProxyType(dict(handlers))
        self._factory = resource_factory
        self._max_response_bytes = max_response_bytes
        self._clock = clock
        self._closed = False
        self._active: set[asyncio.Task] = set()

    async def __aenter__(self):
        if self._closed:
            raise ProviderContractError("PROVIDER_UNAVAILABLE")
        return self

    async def __aexit__(self, *args):
        await self.aclose()

    async def aclose(self):
        self._closed = True
        tasks = tuple(task for task in self._active if task is not asyncio.current_task())
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def execute(self, operation: str, raw: bytes, *, credential: SecretStr | None) -> bytes:
        if self._closed:
            return _error_json("PROVIDER_UNAVAILABLE")
        task = asyncio.current_task()
        if task in self._active:
            return _error_json("INTERNAL_ERROR")
        self._active.add(task)
        resource = None
        lifetime = _RequestLifetime()
        budget = AnalysisBudget.start(self._timeout, clock=self._clock)
        deadline = asyncio.timeout(budget.remaining_seconds)
        cancelled, code, encoded = False, None, None
        try:
            async with deadline:
                authorized = self._boundary.prepare(operation, raw, credential=credential)
                if operation == "EVALUATE" and budget.total_seconds > authorized.request.evaluation.execution.total_timeout_seconds:
                    raise ProviderContractError("CONFIGURATION_MISMATCH")
                context = ExecutionContext(authorized.identity, budget, None, lifetime)
                context.checkpoint()
                handler = self._handlers.get(operation)
                if handler is None:
                    raise ProviderContractError("PROVIDER_UNAVAILABLE")
                resource = self._factory(authorized) if self._factory is not None else None
                context = ExecutionContext(authorized.identity, budget, resource, lifetime)
                context.checkpoint()
                response = await handler(authorized.request, context)
                context.checkpoint()
                model = RESPONSE_MODELS[operation]
                if not isinstance(response, model):
                    raise ProviderContractError("INTERNAL_ERROR")
                response = model.model_validate(response.model_dump()).validate_for_request(authorized.request)
                encoded = response.model_dump_json().encode("utf-8")
                if len(encoded) > self._max_response_bytes:
                    raise ProviderContractError("INTERNAL_ERROR")
                context.checkpoint()
        except asyncio.CancelledError:
            cancelled = True
        except ProviderContractError as error:
            code = error.error.code
        except TimeoutError:
            code = "DEADLINE_EXCEEDED" if deadline.expired() or budget.remaining_seconds <= 0 else "PROVIDER_UNAVAILABLE"
        except ConnectionError:
            code = "PROVIDER_UNAVAILABLE"
        except Exception:
            code = "INTERNAL_ERROR"
        finally:
            lifetime.active = False
            try:
                if resource is not None:
                    cleanup_cancelled, cleanup_failed = await _release(resource, self._cleanup_timeout)
                    cancelled = cancelled or cleanup_cancelled
                    if cleanup_failed and code is None:
                        code = "PROVIDER_UNAVAILABLE"
            finally:
                self._active.discard(task)
        if cancelled:
            # Cancellation remains control flow until a transport mapping is agreed.
            raise asyncio.CancelledError
        if code is None and budget.remaining_seconds <= 0:
            code = "DEADLINE_EXCEEDED"
        return _error_json(code) if code is not None else encoded
