"""Bounded diagnostics contain schema locations, never provider input values."""

from typing import Annotated, Literal

from pydantic import Field

from copenhagen_fds.contracts.values import Array, InputModel


DiagnosticPhase = Literal[
    "RESPONSE_JSON", "RESPONSE_ENVELOPE", "USAGE", "PAYLOAD_JSON", "PAYLOAD_SCHEMA",
    "COMPARISON_STATE", "ITEM_REFERENCE", "SEMANTIC_SCOPE", "UNRESOLVED_INPUT", "COMPLETION_CONTEXT",
]
DiagnosticCode = Literal[
    "INVALID_JSON", "DUPLICATE_FIELD", "INVALID_ENVELOPE", "INVALID_USAGE", "MISSING_FIELD",
    "EXTRA_FIELD", "INVALID_TYPE", "INVALID_VALUE", "STATE_CONFLICT", "DUPLICATE_REFERENCE",
    "UNKNOWN_REFERENCE", "OUT_OF_SCOPE", "UNRESOLVED_INPUT", "INVALID_CONTEXT",
]
PathField = Literal[
    "*", "items", "name", "quantity", "brand", "category", "budget", "kind", "amount",
    "amount_minor", "currency", "purpose", "explicit_conditions", "preferences", "ambiguities",
    "comparison", "findings", "confirmation_requests", "code", "dimension", "related_items",
    "order_id", "item_id", "prompt_tokens", "completion_tokens", "total_tokens",
]
PathPart = PathField | Annotated[int, Field(ge=0, le=100000)]


class ValidationDiagnostic(InputModel):
    phase: DiagnosticPhase
    code: DiagnosticCode
    path: Annotated[Array[PathPart], Field(max_length=12)] = ()
