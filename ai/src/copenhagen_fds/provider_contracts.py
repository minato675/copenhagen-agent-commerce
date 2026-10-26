"""Pure bounded JSON validation; no transport, authentication, model call or storage."""

import json
import math
from typing import TypeVar
from uuid import uuid4

from pydantic import ValidationError

from copenhagen_fds.contracts.provider_errors import ProviderError, ProviderInputLimits
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, ProviderEvaluationResponse
from copenhagen_fds.contracts.provider_profiles import (
    ProviderProfileCreateRequest, ProviderProfileCreateResponse,
    ProviderProfileUpdateRequest, ProviderProfileUpdateResponse,
)
from copenhagen_fds.contracts.values import InputModel


CONTRACT_MODELS = {
    cls.__name__: cls for cls in (
        ProviderEvaluationRequest, ProviderEvaluationResponse, ProviderProfileCreateRequest,
        ProviderProfileCreateResponse, ProviderProfileUpdateRequest, ProviderProfileUpdateResponse,
        ProviderError,
    )
}
T = TypeVar("T", bound=InputModel)


class ProviderContractError(ValueError):
    """Only the fixed code and a server-generated ID cross the error boundary."""

    def __init__(self, code):
        self.error = ProviderError(error_id=uuid4(), code=code)
        super().__init__(code)


def _depth(text, maximum):
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > maximum:
                raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
        elif char in "]}":
            depth -= 1


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProviderContractError("INVALID_JSON")
        result[key] = value
    return result


def _reject_constant(_value):
    raise ProviderContractError("INVALID_JSON")


def _check_values(value, limits):
    stack, count = [value], 0
    while stack:
        value = stack.pop()
        count += 1
        if count > limits.max_nodes:
            raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
        if isinstance(value, (dict, list)):
            if len(value) > limits.max_collection_items:
                raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
            stack.extend(value.values() if isinstance(value, dict) else value)
            if isinstance(value, dict):
                stack.extend(value.keys())
        elif isinstance(value, str):
            if len(value) > limits.max_string_chars:
                raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
            # Escaped lone surrogates are accepted by json.loads but are not valid UTF-8 text.
            value.encode("utf-8")
        elif type(value) is int and abs(value) > limits.max_integer_abs:
            raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
        elif type(value) is float:
            if not math.isfinite(value):
                raise ProviderContractError("INVALID_JSON")
            if abs(value) > limits.max_float_abs:
                raise ProviderContractError("INPUT_LIMIT_EXCEEDED")


def validate_provider_json(model: type[T], raw: bytes, *, limits: ProviderInputLimits) -> T:
    """Parse and validate supplied bytes, without reflecting values or field paths.

    Limits must come from trusted deployment configuration, never this JSON body.
    Successful validation does not authenticate configuration/state or bind a response
    to its request. Call response.validate_for_request separately after decoding.
    """
    limits = ProviderInputLimits.model_validate(limits.model_dump())
    if not isinstance(raw, bytes):
        raise ProviderContractError("INVALID_JSON")
    if len(raw) > limits.max_body_bytes:
        raise ProviderContractError("INPUT_LIMIT_EXCEEDED")
    try:
        text = raw.decode("utf-8")
        _depth(text, limits.max_depth)
        value = json.loads(text, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
        _check_values(value, limits)
    except ProviderContractError:
        raise
    except (ValueError, UnicodeError, RecursionError, OverflowError):
        raise ProviderContractError("INVALID_JSON") from None
    if not isinstance(value, dict):
        raise ProviderContractError("INVALID_CONTRACT")
    expected_version = "fds-provider-error-v1" if model is ProviderError else "fds-provider-v1"
    if value.get("schema_version") != expected_version:
        raise ProviderContractError("UNSUPPORTED_CONTRACT")
    try:
        # Use JSON mode for JSON-native UUID strings while keeping strict Python inputs.
        result = model.model_validate_json(text)
    except (ValidationError, ValueError, TypeError, OverflowError):
        raise ProviderContractError("INVALID_CONTRACT") from None
    return result
