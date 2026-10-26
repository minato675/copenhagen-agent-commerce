"""Bounded, non-streaming Chat Completions through the fixed GMS gateway."""

import asyncio
from datetime import datetime, timezone
import json
import re
import time
from typing import Any, Self

import httpx2
from openai import APIConnectionError, APIError, APIStatusError, APITimeoutError, AsyncOpenAI
from pydantic import ValidationError

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.diagnostics import schema_failure, validation_failure
from copenhagen_fds.analysis.sdk_logging import private_provider_call
from copenhagen_fds.analysis.transport_models import CompletionPrompt, StructuredCompletion
from copenhagen_fds.contracts import (
    AnalysisFailure, AnalysisTelemetry, IntentComparisonOutput, IntentExtractionOutput,
    TokenUsage, analysis_response_format,
)
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.settings import EvaluationMode, GMS_OPENAI_BASE_URL, SettingsError


class _GmsClient(AsyncOpenAI):
    @property
    def default_headers(self) -> dict[str, str]:
        # Do not inherit OPENAI_CUSTOM_HEADERS, organization or project credentials.
        return {
            "Accept": "application/json", "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        }


def _identifier(value: Any) -> str | None:
    # Provider metadata is bounded and excludes control characters / free prose.
    if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,200}", value):
        return value
    return None


class _DuplicateField(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateField("duplicate JSON field")
        result[key] = value
    return result


def _json(value: str | bytes) -> Any:
    return json.loads(value, object_pairs_hook=_unique_object)


def _api_failure(status: int | None) -> AnalysisFailure:
    if status == 401:
        category = "AUTHENTICATION"
    elif status == 403:
        category = "PERMISSION"
    elif status == 429:
        category = "RATE_LIMIT"
    elif status is not None and status >= 500:
        category = "SERVER"
    elif status is not None:
        category = "REQUEST"
    else:
        category = "UNKNOWN"
    return AnalysisFailure(
        kind="API_ERROR", http_status=status, api_error=category,
        retryable=status in {408, 409, 429} or (status is not None and status >= 500),
    )


class OpenAIAnalysisAdapter:
    """Inject an async SDK client only in MOCK mode; close that client yourself.

    LIVE owns a GMS-only SDK client and must be closed (prefer async with).
    No model, API, JSON mode, retry or mock fallback is performed.
    """

    def __init__(self, settings: OpenAISettings, *, client: AsyncOpenAI | None = None):
        self.settings = settings
        self._owns_client = client is None
        self._closed = False
        if settings.mode == EvaluationMode.MOCK:
            if client is None:
                raise SettingsError(("FDS_MODE",), "MOCK requires an injected offline SDK client")
            self._client = client
        else:
            if client is not None:
                raise SettingsError(("FDS_MODE",), "injected clients must be labelled MOCK")
            self._client = _GmsClient(
                api_key=settings.gms_key.get_secret_value(), base_url=GMS_OPENAI_BASE_URL,
                max_retries=0, timeout=settings.openai_timeout_seconds,
                http_client=httpx2.AsyncClient(follow_redirects=False, trust_env=False),
            )

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(self, *args: Any) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_client and not self._closed:
            await self._client.close()
        self._closed = True

    def new_budget(self) -> AnalysisBudget:
        """Create once per evaluation, then pass the same budget to both stages."""
        return AnalysisBudget.start(self.settings.evaluation_timeout_seconds)

    async def complete(self, prompt: CompletionPrompt, *, budget: AnalysisBudget) -> StructuredCompletion:
        if self._closed:
            raise ValueError("analysis adapter is closed")
        if budget.total_seconds > self.settings.evaluation_timeout_seconds:
            raise ValueError("analysis budget exceeds the configured total timeout")
        start = time.monotonic()
        timeout = min(self.settings.openai_timeout_seconds, budget.remaining_seconds)
        failure = None
        payload = None
        response_model = None
        telemetry: dict[str, Any] = {
            "gateway": "MOCK" if self.settings.mode == EvaluationMode.MOCK else "GMS",
            "requested_model": self.settings.openai_model,
        }
        if timeout <= 0:
            failure = AnalysisFailure(kind="TIMEOUT", retryable=False)
        else:
            with private_provider_call():
                try:
                    # SDK timeouts bound each HTTP operation. asyncio also bounds
                    # total wall time; no background thread survives a timeout.
                    async with asyncio.timeout(timeout):
                        raw = await self._client.with_options(
                            base_url=GMS_OPENAI_BASE_URL, max_retries=0, timeout=timeout,
                        ).chat.completions.with_raw_response.create(
                            model=self.settings.openai_model,
                            messages=[
                                {"role": "developer", "content": prompt.developer_message},
                                {"role": "user", "content": prompt.user_message},
                            ],
                            response_format=analysis_response_format(prompt.stage),
                            stream=False,
                        )
                        telemetry["request_id"] = _identifier(raw.headers.get("x-request-id"))
                        if raw.status_code != 200:
                            failure = _api_failure(raw.status_code)
                        else:
                            payload, response_model, failure = self._parse(raw.content, prompt, telemetry)
                    # Account for synchronous decoding/validation time too.
                    if budget.remaining_seconds <= 0 or time.monotonic() - start >= timeout:
                        payload = None
                        failure = AnalysisFailure(kind="TIMEOUT", retryable=False)
                except (APITimeoutError, TimeoutError):
                    failure = AnalysisFailure(kind="TIMEOUT", retryable=budget.remaining_seconds > 0)
                except APIStatusError as error:
                    failure = _api_failure(error.status_code)
                    telemetry["request_id"] = _identifier(error.request_id)
                except APIConnectionError:
                    failure = AnalysisFailure(kind="API_ERROR", api_error="CONNECTION", retryable=True)
                except APIError:
                    failure = _api_failure(None)
                except Exception:
                    # SDK/local transport errors can carry credentials in their
                    # cause, request and body. Return only the fixed category.
                    failure = _api_failure(None)
        telemetry["duration_ms"] = (time.monotonic() - start) * 1000
        return StructuredCompletion(
            stage=prompt.stage, status="FAILED" if failure else "SUCCESS",
            payload=None if failure else payload, failure=failure,
            telemetry=AnalysisTelemetry(**telemetry), response_model=response_model,
            prompt_version=prompt.version, completed_at=datetime.now(timezone.utc),
        )

    @staticmethod
    def _parse(
        content: bytes, prompt: CompletionPrompt, telemetry: dict[str, Any],
    ) -> tuple[IntentExtractionOutput | IntentComparisonOutput | None, str | None, AnalysisFailure | None]:
        model = None
        phase, code = "RESPONSE_JSON", "INVALID_JSON"
        try:
            body = _json(content)
            phase, code = "RESPONSE_ENVELOPE", "INVALID_ENVELOPE"
            if not isinstance(body, dict):
                raise ValueError("expected completion object")
            model = _identifier(body.get("model"))
            telemetry["response_id"] = _identifier(body.get("id"))
            usage = body.get("usage")
            if usage is not None:
                phase, code = "USAGE", "INVALID_USAGE"
                if not isinstance(usage, dict):
                    raise ValueError("expected usage object")
                telemetry["usage"] = TokenUsage.model_validate({
                    key: value for key, value in usage.items() if key in TokenUsage.model_fields
                })
            phase, code = "RESPONSE_ENVELOPE", "INVALID_ENVELOPE"
            choices = body.get("choices")
            if not isinstance(choices, list) or len(choices) != 1:
                return None, model, AnalysisFailure(kind="INCOMPLETE_RESPONSE", http_status=200)
            choice = choices[0]
            message = choice["message"]
            if message.get("refusal") or choice.get("finish_reason") == "content_filter":
                return None, model, AnalysisFailure(kind="REFUSAL", http_status=200, retryable=False)
            if choice.get("finish_reason") != "stop" or not message.get("content"):
                return None, model, AnalysisFailure(kind="INCOMPLETE_RESPONSE", http_status=200)
            if model is None or message.get("role") != "assistant":
                raise ValueError("missing model or invalid role")
            if message.get("tool_calls") or message.get("function_call"):
                raise ValueError("unexpected tool call")
            output_model = IntentExtractionOutput if prompt.stage == "extraction" else IntentComparisonOutput
            phase, code = "PAYLOAD_JSON", "INVALID_JSON"
            decoded = _json(message["content"])
            phase, code = "PAYLOAD_SCHEMA", "INVALID_VALUE"
            payload = output_model.model_validate(decoded)
            return payload, model, None
        except ValidationError as error:
            return None, model, validation_failure(error, phase, http_status=200)
        except _DuplicateField:
            return None, model, schema_failure(phase, "DUPLICATE_FIELD", http_status=200)
        except (ValueError, TypeError, KeyError, AttributeError, RecursionError):
            return None, model, schema_failure(phase, code, http_status=200)
