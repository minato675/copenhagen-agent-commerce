"""Connection settings for OpenAI-only analysis; full FDS policy stays separate."""

from collections.abc import Mapping
import os
from pathlib import Path
from typing import Self

from dotenv import dotenv_values
from pydantic import SecretStr, ValidationError, field_validator, model_validator

from copenhagen_fds.contracts.values import InputModel, Text
from copenhagen_fds.settings import (
    EvaluationMode, FdsSettings, GMS_OPENAI_BASE_URL, ModeValue,
    SettingsError, Timeout, _environment_data, _error_fields,
)


class OpenAISettings(InputModel):
    """Both modes require explicit time limits. Only LIVE requires a real key."""

    mode: ModeValue = EvaluationMode.MOCK
    openai_model: Text = "gpt-5.4-mini"
    gms_key: SecretStr | None = None
    openai_timeout_seconds: Timeout
    evaluation_timeout_seconds: Timeout

    @property
    def openai_base_url(self) -> str:
        return GMS_OPENAI_BASE_URL

    @field_validator("gms_key")
    @classmethod
    def validate_secret(cls, value: SecretStr | None) -> SecretStr | None:
        return FdsSettings.validate_secret(value)

    @model_validator(mode="after")
    def validate_connection(self) -> Self:
        if self.mode == EvaluationMode.LIVE and self.gms_key is None:
            raise SettingsError(("GMS_KEY",), "LIVE analysis requires GMS authentication")
        if self.openai_timeout_seconds > self.evaluation_timeout_seconds:
            raise SettingsError(
                ("FDS_OPENAI_TIMEOUT_SECONDS", "FDS_EVALUATION_TIMEOUT_SECONDS"),
                "provider timeout must not exceed the evaluation timeout",
            )
        return self


def load_openai_settings(
    environ: Mapping[str, str] | None = None, *, env_file: str | Path | None = None,
) -> OpenAISettings:
    """Optionally read one explicit dotenv file; never search or mutate os.environ.

    The explicit mapping, or process environment if omitted, overrides the file.
    Dotenv interpolation is disabled. Policy metadata is outside this scope.
    Removed Sift environment variables fail explicitly in both settings loaders.
    """

    environment = os.environ if environ is None else environ
    if env_file is not None:
        file_values = None
        try:
            with Path(env_file).open(encoding="utf-8-sig") as stream:
                file_values = dotenv_values(stream=stream, interpolate=False)
        except (OSError, UnicodeError):
            pass
        if file_values is None:
            raise SettingsError(("env_file",), "cannot read the supplied environment file")
        environment = {**file_values, **environment}
    data = _environment_data(environment)
    scoped = {name: value for name, value in data.items() if name in OpenAISettings.model_fields}
    failure = None
    try:
        return OpenAISettings.model_validate(scoped)
    except ValidationError as error:
        failure = SettingsError(_error_fields(error.errors(include_input=False, include_context=False)))
    raise failure
