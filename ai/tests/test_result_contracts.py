"""Component states must preserve unavailable data and unknown comparisons."""

import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts import PurchaseIntent
from copenhagen_fds.contracts.results import (
    EvaluationMetadata,
    IntentAnalysisResult,
    RuleResult,
    SiftFraudResult,
    SiftScore,
)
from copenhagen_fds.contracts.statuses import EvaluationStatus, IntentMatch

RESULT_TYPES = {"intent": IntentAnalysisResult, "sift": SiftFraudResult, "rule": RuleResult}
PAYLOAD_FIELDS = {"intent": "comparison", "sift": "score", "rule": "risk_level"}


def set_execution(payload, kind, status):
    payload["status"] = status
    if status != "SUCCESS":
        payload[PAYLOAD_FIELDS[kind]] = None
        payload["metadata"]["valid_until"] = None
        payload["reasons"] = [{
            "code": "TIMEOUT" if status == "FAILED" else "NOT_EXECUTED",
            "source": "MOCK",
        }]
    if status == "NOT_RUN":
        payload["metadata"]["evaluated_at"] = None


@pytest.mark.parametrize("kind", RESULT_TYPES)
@pytest.mark.parametrize("status", ["SUCCESS", "FAILED", "NOT_RUN"])
def test_execution_states_round_trip_without_fallback(component_payloads, kind, status):
    payload = component_payloads[kind]
    set_execution(payload, kind, status)
    model = RESULT_TYPES[kind]
    result = model.model_validate(payload)
    assert result.status == EvaluationStatus(status)
    assert model.model_validate_json(json.dumps(payload)) == result
    assert model.model_validate_json(result.model_dump_json()) == result
    assert result.model_dump(mode="json")["status"] == status
    if status != "SUCCESS":
        assert getattr(result, PAYLOAD_FIELDS[kind]) is None


@pytest.mark.parametrize("comparison", ["MATCH", "MISMATCH", "UNKNOWN"])
def test_semantic_comparisons_remain_distinct(component_payloads, comparison):
    payload = component_payloads["intent"]
    payload["comparison"] = comparison
    if comparison == "UNKNOWN":
        payload["reasons"] = [{"code": "INTENT_UNCERTAIN", "source": "MOCK"}]
    result = IntentAnalysisResult.model_validate(payload)
    assert result.comparison == IntentMatch(comparison)
    assert "score" not in result.model_dump()


@pytest.mark.parametrize("kind", RESULT_TYPES)
@pytest.mark.parametrize("status", ["FAILED", "NOT_RUN"])
def test_unsuccessful_evaluation_cannot_keep_a_normal_payload(component_payloads, kind, status):
    payload = component_payloads[kind]
    original = payload[PAYLOAD_FIELDS[kind]]
    set_execution(payload, kind, status)
    payload[PAYLOAD_FIELDS[kind]] = original
    with pytest.raises(ValidationError, match="payload"):
        RESULT_TYPES[kind].model_validate(payload)


@pytest.mark.parametrize("kind", RESULT_TYPES)
def test_success_requires_a_payload(component_payloads, kind):
    payload = component_payloads[kind]
    payload.pop(PAYLOAD_FIELDS[kind])
    with pytest.raises(ValidationError, match="payload"):
        RESULT_TYPES[kind].model_validate(payload)


@pytest.mark.parametrize("value", [0, 0.0, 0.5, 1, 1.0])
def test_sift_score_preserves_confirmed_api_scale(value):
    score = SiftScore(value=value, unit="SIFT_PAYMENT_ABUSE_0_1")
    assert score.value == value


@pytest.mark.parametrize("value", [-0.01, 1.01, 50, "0.5", True, False, None, float("nan"), float("inf")])
def test_sift_score_rejects_percentages_non_numbers_and_non_finite_values(value):
    with pytest.raises(ValidationError):
        SiftScore(value=value, unit="SIFT_PAYMENT_ABUSE_0_1")


@pytest.mark.parametrize("unit", [None, "PERCENT", "PROBABILITY", "SEMANTIC_RISK"])
def test_sift_score_unit_is_not_implicit_or_relabelled(unit):
    with pytest.raises(ValidationError):
        SiftScore(value=0.2, unit=unit)


@pytest.mark.parametrize("field", ["model_version", "prompt_version"])
def test_successful_intent_requires_analysis_versions(component_payloads, field):
    payload = component_payloads["intent"]
    payload["metadata"].pop(field)
    with pytest.raises(ValidationError, match="versions"):
        IntentAnalysisResult.model_validate(payload)


def test_successful_rule_requires_policy_version(component_payloads):
    payload = component_payloads["rule"]
    payload["metadata"].pop("policy_version")
    with pytest.raises(ValidationError, match="policy version"):
        RuleResult.model_validate(payload)


def test_unknown_provider_and_intent_versions_are_not_invented(component_payloads):
    payload = component_payloads["sift"]
    payload["metadata"]["target"]["intent_version"] = None
    result = SiftFraudResult.model_validate(payload)
    assert result.metadata.model_version is None
    assert result.metadata.target.intent_version is None


@pytest.mark.parametrize("value", ["v1", None])
def test_input_intent_preserves_optional_version(value):
    intent = PurchaseIntent(purchase_request_id="p-1", source="unknown", version=value)
    assert intent.version == value


@pytest.mark.parametrize("value", [1, "", "  "])
def test_input_intent_rejects_invalid_version(value):
    with pytest.raises(ValidationError):
        PurchaseIntent(purchase_request_id="p-1", source="unknown", version=value)


@pytest.mark.parametrize("kind,field", [("intent", "comparison"), ("rule", "risk_level")])
def test_unknown_payload_requires_an_explanation(component_payloads, kind, field):
    payload = component_payloads[kind]
    payload[field] = "UNKNOWN"
    with pytest.raises(ValidationError, match="reason"):
        RESULT_TYPES[kind].model_validate(payload)


@pytest.mark.parametrize("status,code", [
    ("FAILED", "INTENT_MISMATCH"), ("FAILED", "NOT_EXECUTED"),
    ("NOT_RUN", "TIMEOUT"), ("SUCCESS", "EVALUATION_FAILED"),
    ("SUCCESS", "NOT_EXECUTED"),
])
def test_reason_code_must_agree_with_execution_status(component_payloads, status, code):
    payload = component_payloads["sift"]
    set_execution(payload, "sift", status)
    payload["reasons"] = [{"code": code, "source": "MOCK"}]
    with pytest.raises(ValidationError):
        SiftFraudResult.model_validate(payload)


@pytest.mark.parametrize("status", ["FAILED", "NOT_RUN"])
def test_unsuccessful_result_requires_reason_and_no_validity_window(component_payloads, status):
    payload = component_payloads["sift"]
    set_execution(payload, "sift", status)
    payload["reasons"] = []
    with pytest.raises(ValidationError, match="reason"):
        SiftFraudResult.model_validate(payload)
    payload["reasons"] = [{"code": "TIMEOUT" if status == "FAILED" else "NOT_EXECUTED", "source": "MOCK"}]
    payload["metadata"]["evaluated_at"] = "2026-09-09T10:00:00+09:00"
    payload["metadata"]["valid_until"] = "2026-09-09T10:10:00+09:00"
    with pytest.raises(ValidationError):
        SiftFraudResult.model_validate(payload)


@pytest.mark.parametrize("status,evaluated_at", [("SUCCESS", None), ("FAILED", None), ("NOT_RUN", "2026-09-09T10:00:00Z")])
def test_execution_timestamp_must_agree_with_status(component_payloads, status, evaluated_at):
    payload = component_payloads["sift"]
    set_execution(payload, "sift", status)
    payload["metadata"]["valid_until"] = None
    payload["metadata"]["evaluated_at"] = evaluated_at
    with pytest.raises(ValidationError, match="evaluated_at"):
        SiftFraudResult.model_validate(payload)


@pytest.mark.parametrize("field,value", [
    ("evaluated_at", "2026-09-09T10:00:00"), ("evaluated_at", 1788915600),
    ("evaluated_at", "not-a-date"), ("valid_until", "2026-09-09T10:00:00+09:00"),
    ("valid_until", "2026-09-09T09:59:59+09:00"), ("sources", []),
    ("sources", ["invented-provider"]), ("trace_id", ""),
])
def test_invalid_metadata_is_rejected(result_metadata, field, value):
    result_metadata[field] = value
    with pytest.raises(ValidationError):
        EvaluationMetadata.model_validate(result_metadata)


@pytest.mark.parametrize("value", [True, False, "false", "match", 0, "UNKNOWN_STATUS"])
def test_comparison_does_not_coerce_boolean_or_unknown_wire_values(component_payloads, value):
    payload = component_payloads["intent"]
    payload["comparison"] = value
    with pytest.raises(ValidationError):
        IntentAnalysisResult.model_validate(payload)


def test_semantic_result_cannot_claim_fraud_probability(component_payloads):
    payload = component_payloads["intent"]
    payload["fraud_probability"] = 0.7
    with pytest.raises(ValidationError):
        IntentAnalysisResult.model_validate(payload)


@pytest.mark.parametrize("field", ["status", "comparison", "evaluated_at"])
def test_validation_error_text_does_not_echo_invalid_input(component_payloads, field):
    payload = component_payloads["intent"]
    marker = "synthetic-private-value"
    target = payload["metadata"] if field == "evaluated_at" else payload
    target[field] = marker
    with pytest.raises(ValidationError) as caught:
        IntentAnalysisResult.model_validate(payload)
    assert marker not in str(caught.value)
