"""Execution outcomes remain distinct from semantic uncertainty and caller IDs."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts import (
    AnalysisFailure, AnalysisTelemetry, IntentAnalysisRequest, IntentComparisonResult,
    IntentExtractionResult, TokenUsage,
)


def test_extraction_envelope_retains_measurements_and_maps_to_common_input(extraction_result_payload, analysis_request):
    result = IntentExtractionResult.model_validate(extraction_result_payload)
    assert result.validate_for_request(analysis_request) is result
    restored = IntentExtractionResult.model_validate_json(result.model_dump_json())
    assert restored == result
    request = restored.to_evaluation_request(analysis_request)
    assert request.intent.source == analysis_request.user_request.source
    assert request.intent.version == result.metadata.target.intent_version
    assert request.intent.purpose == result.payload.purpose
    assert request.intent.items[0].brand == result.payload.items[0].brand
    assert result.telemetry.duration_ms == 12.5
    assert result.telemetry.usage is None
    assert result.metadata.model_version == "gpt-5.4-mini"
    assert result.metadata.prompt_version == "synthetic-prompt-v1"


def test_comparison_projection_keeps_common_status_and_full_envelope_details(comparison_result_payload, evaluation_request):
    result = IntentComparisonResult.model_validate(comparison_result_payload)
    restored = IntentComparisonResult.model_validate_json(result.model_dump_json())
    assert restored == result
    common = restored.to_common_result(evaluation_request)
    assert common.status == "SUCCESS"
    assert common.comparison == "UNKNOWN"
    assert common.metadata == result.metadata
    assert common.reasons == result.reasons
    assert len(restored.payload.findings[0].related_items) == 2
    assert restored.payload.findings[0].dimension == "QUANTITY"
    assert restored.payload.confirmation_requests[0].code == "CLARIFY_INTENT"
    assert restored.telemetry.response_id == "synthetic-response"
    assert restored.telemetry.duration_ms is None


@pytest.mark.parametrize("comparison", ["MATCH", "MISMATCH"])
def test_known_comparison_states_preserve_the_common_result(comparison_result_payload, evaluation_request, comparison):
    payload = comparison_result_payload["payload"]
    payload["comparison"] = comparison
    if comparison == "MATCH":
        payload.update(findings=[], confirmation_requests=[])
        comparison_result_payload["reasons"] = []
    else:
        payload["findings"][0]["code"] = "INTENT_MISMATCH"
        comparison_result_payload["reasons"][0]["code"] = "INTENT_MISMATCH"
    result = IntentComparisonResult.model_validate(comparison_result_payload)
    common = result.to_common_result(evaluation_request)
    assert common.status == "SUCCESS"
    assert common.comparison == comparison
    assert common.metadata == result.metadata
    assert common.reasons == result.reasons


@pytest.mark.parametrize("kind,reason", [
    ("API_ERROR", "EVALUATION_FAILED"), ("TIMEOUT", "TIMEOUT"),
    ("REFUSAL", "INVALID_RESPONSE"), ("INCOMPLETE_RESPONSE", "INVALID_RESPONSE"),
    ("SCHEMA_ERROR", "INVALID_RESPONSE"),
])
@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_failed_calls_never_become_semantic_unknown_or_success(
    extraction_result_payload, comparison_result_payload, analysis_request, evaluation_request, stage, kind, reason,
):
    payload, model = (
        (extraction_result_payload, IntentExtractionResult) if stage == "extraction"
        else (comparison_result_payload, IntentComparisonResult)
    )
    payload.update(status="FAILED", payload=None, failure={"kind": kind}, reasons=[{"code": reason, "source": "MOCK"}])
    payload["metadata"]["valid_until"] = None
    result = model.model_validate(payload)
    assert model.model_validate_json(result.model_dump_json()) == result
    assert result.failure.kind == kind
    assert result.failure.reason_code == reason
    if stage == "comparison":
        common = result.to_common_result(evaluation_request)
        assert common.status == "FAILED"
        assert common.comparison is None
        assert common.reasons[0].code == reason
    else:
        with pytest.raises(ValueError, match="only successful"):
            result.to_evaluation_request(analysis_request)


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_not_run_stays_distinct_from_failed_call(
    extraction_result_payload, comparison_result_payload, analysis_request, evaluation_request, stage,
):
    payload, model = (
        (extraction_result_payload, IntentExtractionResult) if stage == "extraction"
        else (comparison_result_payload, IntentComparisonResult)
    )
    payload.update(status="NOT_RUN", payload=None, reasons=[{"code": "NOT_EXECUTED", "source": "MOCK"}], telemetry={"gateway": "MOCK"})
    payload["metadata"].update(evaluated_at=None, valid_until=None, model_version=None, prompt_version=None)
    result = model.model_validate(payload)
    assert result.failure is None
    assert result.telemetry.usage is None
    assert result.telemetry.requested_model is None
    if stage == "comparison":
        assert result.to_common_result(evaluation_request).comparison is None
    else:
        assert result.validate_for_request(analysis_request) is result


@pytest.mark.parametrize("field", ["evaluation_id", "user_id", "purchase_request_id", "intent_version"])
@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_unrelated_target_cannot_be_bound(
    extraction_result_payload, comparison_result_payload, analysis_request, evaluation_request, stage, field,
):
    payload, model, request = (
        (extraction_result_payload, IntentExtractionResult, analysis_request) if stage == "extraction"
        else (comparison_result_payload, IntentComparisonResult, evaluation_request)
    )
    payload["metadata"]["target"][field] = "other-target"
    with pytest.raises(ValueError, match="target must match"):
        model.model_validate(payload).validate_for_request(request)


@pytest.mark.parametrize("field,value", [("snapshot_id", "other-order"), ("version", "3"), ("snapshot_hash", "b" * 64)])
@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_other_snapshot_cannot_be_bound(
    extraction_result_payload, comparison_result_payload, analysis_request, evaluation_request, stage, field, value,
):
    payload, model, request = (
        (extraction_result_payload, IntentExtractionResult, analysis_request) if stage == "extraction"
        else (comparison_result_payload, IntentComparisonResult, evaluation_request)
    )
    payload["metadata"]["target"]["order"][field] = value
    with pytest.raises(ValueError, match="target must match"):
        model.model_validate(payload).validate_for_request(request)


def test_missing_input_version_cannot_be_claimed_by_the_result(analysis_input_payload, extraction_result_payload):
    analysis_input_payload["user_request"]["version"] = None
    request = IntentAnalysisRequest.model_validate(analysis_input_payload)
    with pytest.raises(ValueError, match="target must match"):
        IntentExtractionResult.model_validate(extraction_result_payload).to_evaluation_request(request)
    extraction_result_payload["metadata"]["target"]["intent_version"] = None
    result = IntentExtractionResult.model_validate(extraction_result_payload)
    assert result.to_evaluation_request(request).intent.version is None


def test_missing_text_cannot_be_bound_to_success(analysis_input_payload, extraction_result_payload):
    analysis_input_payload["user_request"]["text"] = None
    request = IntentAnalysisRequest.model_validate(analysis_input_payload)
    with pytest.raises(ValueError, match="supplied request text"):
        IntentExtractionResult.model_validate(extraction_result_payload).validate_for_request(request)


def test_full_comparison_result_rechecks_product_references(comparison_result_payload, evaluation_request):
    comparison_result_payload["payload"]["confirmation_requests"][0]["related_items"] = [{"order_id": "absent-order", "item_id": "line-1"}]
    result = IntentComparisonResult.model_validate(comparison_result_payload)
    with pytest.raises(ValueError, match="outside"):
        result.to_common_result(evaluation_request)


@pytest.mark.parametrize("reasons", [[], [{"code": "INTENT_MISMATCH", "source": "MOCK"}], [{"code": "INTENT_UNCERTAIN", "source": "OPENAI"}]])
def test_common_reasons_cannot_disagree_with_payload(comparison_result_payload, reasons):
    comparison_result_payload["reasons"] = reasons
    with pytest.raises(ValidationError):
        IntentComparisonResult.model_validate(comparison_result_payload)


def test_failed_status_requires_classification(comparison_result_payload):
    comparison_result_payload.update(status="FAILED", payload=None, reasons=[{"code": "TIMEOUT", "source": "MOCK"}])
    comparison_result_payload["metadata"]["valid_until"] = None
    with pytest.raises(ValidationError, match="failure classification"):
        IntentComparisonResult.model_validate(comparison_result_payload)
    comparison_result_payload["failure"] = {"kind": "REFUSAL"}
    with pytest.raises(ValidationError, match="failure classification"):
        IntentComparisonResult.model_validate(comparison_result_payload)


def test_success_cannot_carry_failure_or_omit_payload(extraction_result_payload):
    extraction_result_payload["failure"] = {"kind": "SCHEMA_ERROR"}
    with pytest.raises(ValidationError):
        IntentExtractionResult.model_validate(extraction_result_payload)
    extraction_result_payload.pop("failure")
    extraction_result_payload["payload"] = None
    with pytest.raises(ValidationError):
        IntentExtractionResult.model_validate(extraction_result_payload)


@pytest.mark.parametrize("field", ["model_version", "prompt_version"])
def test_success_requires_applied_versions(extraction_result_payload, field):
    extraction_result_payload["metadata"][field] = None
    with pytest.raises(ValidationError):
        IntentExtractionResult.model_validate(extraction_result_payload)


@pytest.mark.parametrize("measurement", [{"duration_ms": 0}, {"usage": {}}, {"response_id": "synthetic-response"}])
def test_not_run_cannot_acquire_call_measurements(extraction_result_payload, measurement):
    extraction_result_payload.update(status="NOT_RUN", payload=None, reasons=[{"code": "NOT_EXECUTED", "source": "MOCK"}])
    extraction_result_payload["metadata"].update(evaluated_at=None, valid_until=None)
    extraction_result_payload["telemetry"] = {"gateway": "MOCK", **measurement}
    with pytest.raises(ValidationError, match="call measurements"):
        IntentExtractionResult.model_validate(extraction_result_payload)


def test_gateway_source_is_verified_without_contacting_gms(comparison_result_payload, evaluation_request):
    comparison_result_payload["telemetry"]["gateway"] = "GMS"
    with pytest.raises(ValidationError, match="source"):
        IntentComparisonResult.model_validate(comparison_result_payload)
    comparison_result_payload["metadata"]["sources"] = ["OPENAI"]
    comparison_result_payload["reasons"][0]["source"] = "OPENAI"
    result = IntentComparisonResult.model_validate(comparison_result_payload)
    assert result.to_common_result(evaluation_request).metadata.sources == ("OPENAI",)
    comparison_result_payload["metadata"]["sources"].append("MOCK")
    with pytest.raises(ValidationError, match="both mocked and real"):
        IntentComparisonResult.model_validate(comparison_result_payload)


@pytest.mark.parametrize("payload", [
    {"prompt_tokens": -1}, {"completion_tokens": True}, {"total_tokens": "3"},
    {"prompt_tokens": 3, "total_tokens": 2},
    {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 4},
])
def test_invalid_token_counts_are_rejected(payload):
    with pytest.raises(ValidationError):
        TokenUsage.model_validate(payload)


def test_reported_zero_and_unknown_token_counts_remain_distinct():
    usage = TokenUsage.model_validate({"prompt_tokens": 0, "total_tokens": 7})
    assert usage.prompt_tokens == 0
    assert usage.completion_tokens is None
    assert usage.total_tokens == 7
    assert TokenUsage.model_validate({}).total_tokens is None
    assert TokenUsage.model_validate_json(usage.model_dump_json()) == usage


@pytest.mark.parametrize("value", [-1, float("nan"), float("inf"), True, "3"])
def test_duration_is_finite_nonnegative_and_not_coerced(value):
    with pytest.raises(ValidationError):
        AnalysisTelemetry.model_validate({"gateway": "MOCK", "duration_ms": value})


@pytest.mark.parametrize("field,value", [("kind", "private-marker"), ("http_status", 99), ("retryable", "true"), ("raw_response", "private-marker")])
def test_failure_contract_rejects_raw_responses_and_invalid_values(field, value):
    with pytest.raises(ValidationError) as caught:
        AnalysisFailure.model_validate({"kind": "API_ERROR", field: value})
    assert "private-marker" not in str(caught.value)


def test_refusal_can_preserve_http_success_without_becoming_analysis_success():
    failure = AnalysisFailure.model_validate({"kind": "REFUSAL", "http_status": 200})
    assert failure.http_status == 200
    assert failure.reason_code == "INVALID_RESPONSE"
    assert AnalysisFailure.model_validate_json(failure.model_dump_json()) == failure


def test_original_request_text_is_hidden_from_repr(analysis_request):
    assert analysis_request.user_request.text not in repr(analysis_request)
    assert analysis_request.user_request.text not in str(analysis_request.user_request)
    assert analysis_request.model_dump()["user_request"]["text"] == analysis_request.user_request.text


def test_binding_does_not_rewrite_request_or_results(comparison_result_payload, evaluation_request):
    original = deepcopy(comparison_result_payload)
    result = IntentComparisonResult.model_validate(comparison_result_payload)
    before = evaluation_request.model_dump_json()
    result.to_common_result(evaluation_request)
    assert comparison_result_payload == original
    assert evaluation_request.model_dump_json() == before
