"""Protocol-independent errors and explicitly supplied parsing limits."""

from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import Field, model_validator

from copenhagen_fds.contracts.values import InputModel


ProviderErrorCode = Literal[
    "INVALID_JSON", "INVALID_CONTRACT", "UNSUPPORTED_CONTRACT", "INPUT_LIMIT_EXCEEDED",
    "UNAUTHENTICATED", "FORBIDDEN", "CONFIGURATION_UNAVAILABLE", "CONFIGURATION_MISMATCH",
    "VERSION_CONFLICT", "DEADLINE_EXCEEDED", "PROVIDER_UNAVAILABLE", "INTERNAL_ERROR",
]


RETRY_BY_CODE = {"VERSION_CONFLICT": "REFRESH_STATE", "DEADLINE_EXCEEDED": "CHECK_BUDGET_AND_BACKOFF",
                 "PROVIDER_UNAVAILABLE": "CHECK_BUDGET_AND_BACKOFF"}


class ProviderError(InputModel):
    schema_version: Literal["fds-provider-error-v1"] = "fds-provider-error-v1"
    # Generated independently of untrusted input; do not echo even invalid trace IDs.
    error_id: UUID
    code: ProviderErrorCode
    retry: Literal["DO_NOT_RETRY", "REFRESH_STATE", "CHECK_BUDGET_AND_BACKOFF"]

    @model_validator(mode="before")
    @classmethod
    def supply_retry(cls, value):
        if isinstance(value, dict) and "retry" not in value:
            value = {**value, "retry": RETRY_BY_CODE.get(value.get("code"), "DO_NOT_RETRY")}
        return value

    @model_validator(mode="after")
    def validate_retry(self) -> Self:
        if self.retry != RETRY_BY_CODE.get(self.code, "DO_NOT_RETRY"):
            raise ValueError("retry advice must match the fixed error classification")
        return self


class ProviderInputLimits(InputModel):
    """Deployment must supply every limit after agreement; no operational defaults."""

    max_body_bytes: Annotated[int, Field(gt=0)]
    max_depth: Annotated[int, Field(gt=0, le=128)]
    max_nodes: Annotated[int, Field(gt=0)]
    max_collection_items: Annotated[int, Field(gt=0)]
    max_string_chars: Annotated[int, Field(gt=0)]
    max_integer_abs: Annotated[int, Field(gt=0)]
    max_float_abs: Annotated[float, Field(gt=0, allow_inf_nan=False)]
