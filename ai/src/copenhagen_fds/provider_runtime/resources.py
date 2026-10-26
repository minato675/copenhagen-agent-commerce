"""Construct a request-owned GMS adapter from trusted service settings only."""

from copenhagen_fds.analysis.openai_adapter import OpenAIAnalysisAdapter
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.boundary import AuthorizedRequest
from copenhagen_fds.settings import EvaluationMode


class GmsResourceFactory:
    """No key/environment discovery and no GMS request during construction."""

    def __init__(self, settings: OpenAISettings):
        self._settings = OpenAISettings.model_validate(settings.model_dump())
        if self._settings.mode != EvaluationMode.LIVE:
            raise ProviderContractError("CONFIGURATION_MISMATCH")

    def __call__(self, authorized: AuthorizedRequest) -> OpenAIAnalysisAdapter | None:
        if authorized.request.operation != "EVALUATE":
            return None
        execution, settings = authorized.request.evaluation.execution, self._settings
        if (execution.gateway, execution.requested_model, execution.provider_timeout_seconds,
            execution.total_timeout_seconds) != ("GMS", settings.openai_model,
                settings.openai_timeout_seconds, settings.evaluation_timeout_seconds):
            raise ProviderContractError("CONFIGURATION_MISMATCH")
        return OpenAIAnalysisAdapter(settings)
