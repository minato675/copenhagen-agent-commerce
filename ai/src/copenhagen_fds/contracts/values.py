"""Strict value types shared by FDS input contracts."""

from collections.abc import Callable
from enum import StrEnum
from typing import Annotated, Any, TypeVar

from iso4217 import Currency
from pydantic import (
    AfterValidator,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    StringConstraints,
)


def _non_blank(value: str) -> str:
    if not value.strip():
        raise ValueError("must not be blank")
    return value


def _currency_code(value: str) -> str:
    try:
        currency = Currency(value)
    except ValueError as exc:
        raise ValueError("must be an ISO 4217 currency code") from exc
    if currency.exponent is None:
        raise ValueError("currency must define a minor unit")
    return value


def _array_to_tuple(value: Any) -> Any:
    # Accept JSON-style arrays in Python dictionaries without coercing their items.
    return tuple(value) if isinstance(value, list) else value


E = TypeVar("E", bound=StrEnum)


def enum_parser(enum_type: type[E]) -> Callable[[Any], E]:
    """Accept enum members or exact wire strings without echoing invalid input."""

    def parse(value: Any) -> E:
        if isinstance(value, enum_type):
            return value
        if type(value) is str:
            try:
                return enum_type(value)
            except ValueError:
                pass
        raise ValueError(f"must be a valid {enum_type.__name__} value")

    return parse


Text = Annotated[str, StringConstraints(min_length=1), AfterValidator(_non_blank)]
Identifier = Text
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-fA-F]{64}$")]
PositiveQuantity = Annotated[int, Field(gt=0)]
CurrencyCode = Annotated[
    str,
    StringConstraints(pattern=r"^[A-Z]{3}$"),
    AfterValidator(_currency_code),
]
T = TypeVar("T")
Array = Annotated[tuple[T, ...], BeforeValidator(_array_to_tuple)]


class InputModel(BaseModel):
    """Validated snapshots: reject coercion, unknown keys and later assignment."""

    model_config = ConfigDict(
        strict=True,
        extra="forbid",
        frozen=True,
        validate_default=True,
        hide_input_in_errors=True,
    )


class Money(InputModel):
    """Exact nonnegative amount in ISO 4217 minor units; no conversion."""

    amount_minor: Annotated[int, Field(ge=0)]
    currency: CurrencyCode
