"""S15P21A207-299: bounded decoding and stable errors with no reflected private input."""

import json
import traceback
from typing import get_args
from uuid import uuid4

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.provider_errors import ProviderError, ProviderErrorCode, ProviderInputLimits
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest
from copenhagen_fds.provider_contracts import CONTRACT_MODELS, ProviderContractError, validate_provider_json


@pytest.fixture
def limits():
    # These are test limits, not agreed deployment defaults.
    return ProviderInputLimits(max_body_bytes=2_000_000, max_depth=64, max_nodes=100_000,
        max_collection_items=10_000, max_string_chars=10_000, max_integer_abs=10**15, max_float_abs=1e15)


@pytest.mark.parametrize("code", get_args(ProviderErrorCode))
def test_error_json_roundtrip_and_retry_is_fixed(limits, code):
    error = ProviderError(error_id=uuid4(), code=code)
    encoded = error.model_dump_json().encode()
    assert ProviderError.model_validate_json(encoded) == error
    assert validate_provider_json(ProviderError, encoded, limits=limits) == error
    data = error.model_dump()
    data["retry"] = "REFRESH_STATE" if error.retry != "REFRESH_STATE" else "DO_NOT_RETRY"
    with pytest.raises(ValidationError):
        ProviderError.model_validate(data)


@pytest.mark.parametrize("raw,code", [
    (b'{"x": NaN}', "INVALID_JSON"), (b'{"x": Infinity}', "INVALID_JSON"),
    (b'{"x": 1e999}', "INVALID_JSON"), (b'{"x":1,"x":2}', "INVALID_JSON"),
    (b'{"x":"\\ud800"}', "INVALID_JSON"), (b'\xff', "INVALID_JSON"),
    (b'{', "INVALID_JSON"), (b'[]', "INVALID_CONTRACT"),
    (b'{"schema_version":"fds-provider-v0"}', "UNSUPPORTED_CONTRACT"),
    (b'{"schema_version":"fds-provider-v1","secret-sentinel":"private-original-text"}', "INVALID_CONTRACT"),
])
def test_invalid_input_never_leaks_values_or_paths(limits, raw, code):
    with pytest.raises(ProviderContractError) as caught:
        validate_provider_json(ProviderEvaluationRequest, raw, limits=limits)
    error = caught.value
    assert error.error.code == code
    output = str(error) + repr(error) + error.error.model_dump_json() + "".join(traceback.format_exception(error))
    assert "secret-sentinel" not in output and "private-original-text" not in output
    assert set(error.error.model_dump()) == {"schema_version", "error_id", "code", "retry"}


@pytest.mark.parametrize("field,maximum,value", [
    ("max_body_bytes", 4, {"x": 1}), ("max_depth", 1, {"x": {"y": 1}}),
    ("max_nodes", 2, {"x": 1}), ("max_collection_items", 1, [1, 2]),
    ("max_string_chars", 3, "four"), ("max_integer_abs", 10, 11),
    ("max_float_abs", 10.0, -11.0),
])
def test_every_input_limit_rejects_excess_before_model_validation(limits, field, maximum, value):
    configured = ProviderInputLimits.model_validate({**limits.model_dump(), field: maximum})
    with pytest.raises(ProviderContractError) as caught:
        validate_provider_json(ProviderEvaluationRequest, json.dumps(value).encode(), limits=configured)
    assert caught.value.error.code == "INPUT_LIMIT_EXCEEDED"


def test_valid_request_respects_exact_byte_boundary_and_escaped_brackets(limits, evaluation_case):
    request = evaluation_case().request
    data = request.model_dump(mode="json")
    data["evaluation"]["analysis"]["user_request"]["text"] = 'text [ { " \\ } ]'
    raw = json.dumps(data).encode()
    exact = ProviderInputLimits.model_validate({**limits.model_dump(), "max_body_bytes": len(raw)})
    assert validate_provider_json(ProviderEvaluationRequest, raw, limits=exact).call == request.call
    smaller = ProviderInputLimits.model_validate({**exact.model_dump(), "max_body_bytes": len(raw) - 1})
    with pytest.raises(ProviderContractError):
        validate_provider_json(ProviderEvaluationRequest, raw, limits=smaller)


def test_limits_require_explicit_configuration_and_no_nonfinite_values():
    with pytest.raises(ValidationError):
        ProviderInputLimits()
    for field in ProviderInputLimits.model_fields:
        assert ProviderInputLimits.model_fields[field].is_required()


@pytest.mark.parametrize("name", list(CONTRACT_MODELS))
def test_every_message_has_json_schema(name):
    schema = CONTRACT_MODELS[name].model_json_schema()
    assert schema["type"] == "object" and schema["additionalProperties"] is False
    assert "schema_version" in schema["properties"]
    assert json.loads(json.dumps(schema)) == schema


def test_independent_error_ids_and_no_input_trace_echo(limits):
    errors = []
    for _ in range(2):
        try:
            validate_provider_json(ProviderEvaluationRequest,
                b'{"schema_version":"fds-provider-v1","call":{"trace_id":"secret-sentinel"}}', limits=limits)
        except ProviderContractError as exc:
            errors.append(exc.error)
    assert errors[0].error_id != errors[1].error_id
    assert all("secret-sentinel" not in error.model_dump_json() for error in errors)
