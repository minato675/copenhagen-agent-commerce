"""Explicit environment loading with typed settings and redacted failures."""

from collections.abc import Mapping
from enum import StrEnum
import os
from typing import Annotated, Any, Self

from pydantic import BeforeValidator, Field, SecretStr, ValidationError, field_validator, model_validator

from copenhagen_fds.contracts.values import InputModel, Text, enum_parser


GMS_OPENAI_BASE_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1"
GMS_CHAT_COMPLETIONS_URL = f"{GMS_OPENAI_BASE_URL}/chat/completions"


class EvaluationMode(StrEnum):
    MOCK = "MOCK"
    LIVE = "LIVE"


class SettingsError(Exception):
    """Contains setting names and a fixed explanation, never input values."""

    def __init__(self, fields: tuple[str, ...], explanation: str = "invalid configuration"):
        self.fields = fields
        super().__init__(f"FDS settings: {explanation}; check {', '.join(fields)}")


Timeout = Annotated[float, Field(gt=0.0, allow_inf_nan=False)]
ModeValue = Annotated[EvaluationMode, BeforeValidator(enum_parser(EvaluationMode))]

ENV_FIELDS = {
    "mode": "FDS_MODE",
    "openai_model": "FDS_OPENAI_MODEL",
    "gms_key": "GMS_KEY",
    "openai_timeout_seconds": "FDS_OPENAI_TIMEOUT_SECONDS",
    "evaluation_timeout_seconds": "FDS_EVALUATION_TIMEOUT_SECONDS",
    "policy_version": "FDS_POLICY_VERSION",
}
REMOVED_SIFT_ENV = (
    "FDS_SIFT_API_KEY", "FDS_SIFT_TIMEOUT_SECONDS", "FDS_SIFT_MEDIUM_THRESHOLD",
    "FDS_SIFT_HIGH_THRESHOLD", "FDS_SIFT_SCORE_UNIT",
)
SECRET_FIELDS = ("gms_key",)
TIMEOUT_FIELDS = ("openai_timeout_seconds", "evaluation_timeout_seconds")


class FdsSettings(InputModel):
    """Connection settings for local FDS and its existing GPT component.

    LIVE validates GMS connectivity settings, not readiness of a risk policy or
    the complete evaluation pipeline. Policy configuration is introduced by
    its own loader when classification is implemented; no cutoffs are assumed.
    Use load_settings for environment parsing and sanitized validation errors.
    """

    mode: ModeValue = EvaluationMode.MOCK
    openai_model: Text = "gpt-5.4-mini"
    gms_key: SecretStr | None = None
    openai_timeout_seconds: Timeout | None = None
    evaluation_timeout_seconds: Timeout | None = None
    policy_version: Text | None = None

    @property
    def openai_base_url(self) -> str:
        """The GMS gateway root; never inherit the SDK's direct OpenAI default."""
        return GMS_OPENAI_BASE_URL

    @property
    def openai_chat_completions_url(self) -> str:
        return GMS_CHAT_COMPLETIONS_URL

    @field_validator("gms_key")
    @classmethod
    def validate_secret(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None:
            secret = value.get_secret_value()
            if not secret or any(character.isspace() for character in secret):
                raise ValueError("API key must be nonempty and contain no whitespace")
        return value

    @model_validator(mode="after")
    def validate_combination(self) -> Self:
        if self.mode == EvaluationMode.LIVE:
            required = (*SECRET_FIELDS, *TIMEOUT_FIELDS)
            missing = tuple(ENV_FIELDS[field] for field in required if getattr(self, field) is None)
            if missing:
                raise SettingsError(missing, "LIVE requires explicit GMS connection and timeout settings")
        if (
            self.openai_timeout_seconds is not None
            and self.evaluation_timeout_seconds is not None
            and self.openai_timeout_seconds > self.evaluation_timeout_seconds
        ):
            raise SettingsError(
                ("FDS_OPENAI_TIMEOUT_SECONDS", "FDS_EVALUATION_TIMEOUT_SECONDS"),
                "provider timeout must not exceed the evaluation timeout",
            )
        return self


def _number(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            pass
    return value


def _error_fields(errors: list[dict[str, Any]]) -> tuple[str, ...]:
    names = set()
    for error in errors:
        location = error["loc"]
        field = location[0] if location else None
        if field in ENV_FIELDS:
            names.add(ENV_FIELDS[field])
        else:
            names.add("FDS_* settings")
    return tuple(sorted(names))


def _environment_data(environ: Mapping[str, str] | None) -> dict[str, Any]:
    """Shared names, types and secret parsing; validation belongs to each scope."""
    environment = os.environ if environ is None else environ
    if "FDS_OPENAI_API_KEY" in environment:
        raise SettingsError(
            ("FDS_OPENAI_API_KEY", "GMS_KEY"),
            "replace the legacy OpenAI key setting with GMS_KEY",
        )
    if any(isinstance(name, str) and name.upper() == "GMS_KEY" and name != "GMS_KEY" for name in environment):
        raise SettingsError(("GMS_KEY",), "use the exact environment variable name GMS_KEY")
    removed = tuple(name for name in REMOVED_SIFT_ENV if name in environment)
    if removed:
        raise SettingsError(removed, "remove legacy Sift settings; local FDS does not use Sift")
    allowed = set(ENV_FIELDS.values())
    if any(isinstance(name, str) and name.upper().startswith("FDS_") and name not in allowed for name in environment):
        raise SettingsError(("FDS_* settings",), "unrecognized environment setting")
    for name in allowed:
        if name in environment and type(environment[name]) is not str:
            raise SettingsError((name,), "environment values must be strings")

    data: dict[str, Any] = {}
    for field, name in ENV_FIELDS.items():
        if name not in environment:
            continue
        value: Any = environment[name]
        if value == "" and field not in ("mode", "openai_model"):
            value = None
        if field in SECRET_FIELDS and value is not None:
            value = SecretStr(value)
        elif field in TIMEOUT_FIELDS:
            value = _number(value)
        data[field] = value

    return data


def load_settings(environ: Mapping[str, str] | None = None) -> FdsSettings:
    """Read the supplied mapping (or os.environ), without files or API calls.

    An explicit empty mapping is isolated from the process environment. Empty
    optional values mean unset, while empty mode/model values are invalid.
    """

    data = _environment_data(environ)

    failure = None
    try:
        return FdsSettings.model_validate(data)
    except ValidationError as error:
        failure = SettingsError(_error_fields(error.errors(include_input=False, include_context=False)))
    # Raise outside the handler so raw ValidationError/input are not chained.
    raise failure
