"""Request-owned Mock/GMS resources without CLI clock or network monkeypatches."""

from dataclasses import dataclass, field
from datetime import datetime, timezone
import os

from copenhagen_fds.analysis.transport_models import StructuredCompletion
from copenhagen_fds.api.settings import HttpSettings, HttpSettingsError, MockResponses, load_http_settings
from copenhagen_fds.contracts import AnalysisFailure
from copenhagen_fds.openai_settings import OpenAISettings, load_openai_settings
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.access import ServiceTokenAuthenticator
from copenhagen_fds.provider_runtime.evaluation import evaluate_handler
from copenhagen_fds.provider_runtime.execution import ProviderRuntime
from copenhagen_fds.provider_runtime.resources import GmsResourceFactory
from copenhagen_fds.settings import SettingsError


class MockResource:
    def __init__(self, execution, responses: MockResponses):
        self.settings = OpenAISettings(mode="MOCK", openai_model=execution.requested_model,
            openai_timeout_seconds=execution.provider_timeout_seconds,
            evaluation_timeout_seconds=execution.total_timeout_seconds)
        self._responses = responses
        self._closed = False

    async def complete(self, prompt, *, budget):
        if self._closed:
            raise ProviderContractError("PROVIDER_UNAVAILABLE")
        value = getattr(self._responses, prompt.stage)
        failed = isinstance(value, AnalysisFailure)
        return StructuredCompletion(stage=prompt.stage, status="FAILED" if failed else "SUCCESS",
            payload=None if failed else value, failure=value if failed else None,
            telemetry=dict(gateway="MOCK", requested_model=self.settings.openai_model),
            response_model=None if failed else "http-mock-v1", prompt_version=prompt.version,
            completed_at=datetime.now(timezone.utc))

    async def aclose(self):
        self._closed = True


@dataclass
class HttpService:
    settings: HttpSettings
    authenticator: ServiceTokenAuthenticator = field(repr=False)
    runtime: ProviderRuntime = field(repr=False)
    active: int = 0


def build_service(settings: HttpSettings, token, *, environ=None) -> HttpService:
    authenticator, boundary = settings.boundary(token)
    if settings.mode == "GMS":
        gms = load_openai_settings(os.environ if environ is None else environ)
        if settings.timeout_seconds > gms.evaluation_timeout_seconds:
            raise HttpSettingsError("HTTP runtime budget exceeds the GMS evaluation budget")
        factory = GmsResourceFactory(gms)
    else:
        def factory(authorized):
            execution = authorized.request.evaluation.execution
            if execution.gateway != "MOCK":
                raise ProviderContractError("CONFIGURATION_MISMATCH")
            return MockResource(execution, settings.mock)
    runtime = ProviderRuntime(boundary=boundary, handlers={"EVALUATE": evaluate_handler},
        timeout_seconds=settings.timeout_seconds, cleanup_timeout_seconds=settings.cleanup_timeout_seconds,
        max_response_bytes=settings.max_response_bytes, resource_factory=factory)
    return HttpService(settings, authenticator, runtime)


def load_service(environ=None) -> HttpService | None:
    loaded = load_http_settings(environ)
    if loaded is None:
        return None
    try:
        return build_service(*loaded, environ=environ)
    except (ValueError, SettingsError, TypeError):
        pass
    raise HttpSettingsError("Invalid AI HTTP runtime or GMS settings") from None
