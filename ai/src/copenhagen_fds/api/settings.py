"""Explicit deployment settings; credentials are supplied only through environment."""

import os
from pathlib import Path
from typing import Annotated, Literal, Self

from pydantic import Field, SecretStr, model_validator

from copenhagen_fds.contracts import AnalysisFailure, IntentComparisonOutput, IntentExtractionOutput
from copenhagen_fds.contracts.provider_errors import ProviderInputLimits
from copenhagen_fds.contracts.provider_evaluation import ConfigurationReference
from copenhagen_fds.contracts.values import Array, Identifier, InputModel
from copenhagen_fds.provider_runtime.access import (
    OperationGrant, ResourceScope, ServiceIdentity, ServiceTokenAuthenticator, TokenBinding,
)
from copenhagen_fds.provider_runtime.boundary import ProviderBoundary, TrustedConfiguration


Seconds = Annotated[float, Field(gt=0, allow_inf_nan=False)]
Count = Annotated[int, Field(gt=0)]


class MockResponses(InputModel):
    extraction: IntentExtractionOutput | AnalysisFailure = Field(repr=False)
    comparison: IntentComparisonOutput | AnalysisFailure = Field(repr=False)


class EvaluationGrant(InputModel):
    user_ids: Array[Identifier]
    profile_ids: Array[Identifier]
    cohort_ids: Array[Identifier]


class HttpSettings(InputModel):
    schema_version: Literal["fds-http-server-v1"] = "fds-http-server-v1"
    mode: Literal["MOCK", "GMS"]
    service_id: Identifier
    grants: Annotated[Array[EvaluationGrant], Field(min_length=1)]
    configurations: Annotated[Array[ConfigurationReference], Field(min_length=1)]
    limits: ProviderInputLimits
    timeout_seconds: Seconds
    cleanup_timeout_seconds: Seconds
    max_response_bytes: Count
    body_read_timeout_seconds: Seconds
    max_concurrent_evaluations: Count
    mock: MockResponses | None = Field(default=None, repr=False)

    @model_validator(mode="after")
    def validate_mode(self) -> Self:
        if (self.mode == "MOCK") != (self.mock is not None):
            raise ValueError("only MOCK requires explicit scripted completions")
        return self

    def boundary(self, token: SecretStr):
        # Never infer permissions or trusted configuration from submitted requests.
        grants = tuple(OperationGrant("EVALUATE", ResourceScope(
            frozenset(g.user_ids), frozenset(g.profile_ids), frozenset(g.cohort_ids))) for g in self.grants)
        authenticator = ServiceTokenAuthenticator((TokenBinding(token, ServiceIdentity(self.service_id, grants)),))
        boundary = ProviderBoundary(authenticator=authenticator, limits=self.limits,
            configurations=tuple(TrustedConfiguration("EVALUATE", ref) for ref in self.configurations))
        return authenticator, boundary


class HttpSettingsError(ValueError):
    """No input, credential, path or underlying exception is included in the message."""


def load_http_settings(environ=None):
    environment = os.environ if environ is None else environ
    path = environment.get("AI_HTTP_CONFIG")
    if path is None:
        return None  # Liveness only; readiness and evaluation remain unavailable.
    try:
        if not path:
            raise ValueError
        with Path(path).open("rb") as stream:
            raw = stream.read(2_000_001)
        if len(raw) > 2_000_000:
            raise ValueError
        settings = HttpSettings.model_validate_json(raw)
        token = environment.get("AI_HTTP_SERVICE_TOKEN", "")
        if len(token) < 32 or any(char.isspace() for char in token):
            raise ValueError
        return settings, SecretStr(token)
    except (OSError, ValueError, TypeError):
        pass
    raise HttpSettingsError("Invalid AI HTTP settings; check AI_HTTP_CONFIG and AI_HTTP_SERVICE_TOKEN") from None
