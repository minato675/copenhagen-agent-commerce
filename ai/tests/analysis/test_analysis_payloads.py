"""Input binding and the untrusted, strictly structured model response boundary."""

from copy import deepcopy
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts import (
    IntentAnalysisRequest, IntentComparisonOutput, IntentExtractionOutput, analysis_response_format,
)


@pytest.mark.parametrize("decoder", ["dict", "json"])
def test_analysis_input_and_outputs_round_trip(analysis_input_payload, extraction_payload, comparison_payload, decoder):
    for model, payload in (
        (IntentAnalysisRequest, analysis_input_payload),
        (IntentExtractionOutput, extraction_payload),
        (IntentComparisonOutput, comparison_payload),
    ):
        parsed = model.model_validate(payload) if decoder == "dict" else model.model_validate_json(json.dumps(payload))
        assert parsed.model_dump(mode="json", exclude_unset=True) == payload
        assert model.model_validate_json(parsed.model_dump_json()) == parsed


def test_mapping_preserves_intent_and_supplied_provenance(analysis_request, extraction_payload):
    output = IntentExtractionOutput.model_validate(extraction_payload)
    request = analysis_request.with_extracted_intent(output)
    assert request.context is analysis_request.context
    assert request.order is analysis_request.order
    assert request.intent.purchase_request_id == analysis_request.user_request.purchase_request_id
    assert request.intent.version == analysis_request.user_request.version
    assert request.intent.source == "agent_summary"
    assert request.intent.items[0].quantity is None
    assert request.intent.items[0].product_id is None
    assert request.intent.items[0].merchant_id is None
    assert request.intent.items[0].brand == "테스트 브랜드"
    assert request.intent.items[0].category == "키보드"
    assert request.intent.purpose == output.purpose
    assert request.intent.budget == output.budget
    assert request.intent.budget.kind == "approximate"
    for field in ("explicit_conditions", "preferences", "ambiguities"):
        assert getattr(request.intent, field) == getattr(output, field)


def test_unknowns_are_not_filled_from_the_order(analysis_input_payload):
    analysis_input_payload["user_request"].pop("version")
    request = IntentAnalysisRequest.model_validate(analysis_input_payload)
    output = IntentExtractionOutput.model_validate({
        "items": None, "budget": None, "purpose": None,
        "explicit_conditions": None, "preferences": [], "ambiguities": ["요청 조건 미확정"],
    })
    mapped = request.with_extracted_intent(output)
    assert mapped.intent.version is None
    assert mapped.intent.items is None
    assert mapped.intent.budget is None
    assert mapped.intent.explicit_conditions is None
    assert mapped.intent.preferences == ()


@pytest.mark.parametrize("part", ["user_request", "order", "context"])
def test_cross_request_input_is_rejected(analysis_input_payload, part):
    analysis_input_payload[part]["purchase_request_id"] = "another-purchase"
    with pytest.raises(ValidationError):
        IntentAnalysisRequest.model_validate(analysis_input_payload)


@pytest.mark.parametrize("value", ["", "   ", 123, False])
def test_request_text_must_be_provided_text_or_explicitly_missing(analysis_input_payload, value):
    analysis_input_payload["user_request"]["text"] = value
    with pytest.raises(ValidationError):
        IntentAnalysisRequest.model_validate(analysis_input_payload)


def test_missing_text_cannot_become_model_generated_intent(analysis_input_payload, extraction_payload):
    analysis_input_payload["user_request"].pop("text")
    request = IntentAnalysisRequest.model_validate(analysis_input_payload)
    assert request.user_request.text is None
    with pytest.raises(ValueError, match="supplied request text"):
        request.with_extracted_intent(IntentExtractionOutput.model_validate(extraction_payload))


@pytest.mark.parametrize("field", [
    "user_id", "purchase_request_id", "evaluation_id", "source", "version", "metadata", "risk_level",
])
@pytest.mark.parametrize("model_name", ["extraction", "comparison"])
def test_model_cannot_supply_provenance_or_risk(extraction_payload, comparison_payload, field, model_name):
    payload, model = (
        (extraction_payload, IntentExtractionOutput) if model_name == "extraction"
        else (comparison_payload, IntentComparisonOutput)
    )
    payload[field] = "private-marker"
    with pytest.raises(ValidationError) as caught:
        model.model_validate_json(json.dumps(payload))
    assert "private-marker" not in str(caught.value)


@pytest.mark.parametrize("stage", ["extraction", "comparison"])
def test_wire_schema_requires_every_field_and_forbids_extra_properties(stage):
    response_format = analysis_response_format(stage)
    assert response_format["type"] == "json_schema"
    assert response_format["json_schema"]["strict"] is True
    assert response_format["json_schema"]["name"] == f"fds_intent_{stage}_v1"
    def inspect(node):
        if isinstance(node, dict):
            if node.get("type") == "object":
                assert node["additionalProperties"] is False
                assert set(node["required"]) == set(node["properties"])
            assert "default" not in node
            for value in node.values():
                inspect(value)
        elif isinstance(node, list):
            for value in node:
                inspect(value)
    inspect(response_format["json_schema"]["schema"])
    response_format["json_schema"]["schema"].clear()
    assert analysis_response_format(stage)["json_schema"]["schema"]["type"] == "object"


@pytest.mark.parametrize("field", ["items", "budget", "purpose", "explicit_conditions", "preferences", "ambiguities"])
def test_extraction_wire_requires_nullable_fields(extraction_payload, field):
    extraction_payload.pop(field)
    with pytest.raises(ValidationError):
        IntentExtractionOutput.model_validate(extraction_payload)


def test_mapping_retains_currency_validation(analysis_request, extraction_payload):
    extraction_payload["budget"]["amount"]["currency"] = "USD"
    with pytest.raises(ValidationError, match="currencies"):
        analysis_request.with_extracted_intent(IntentExtractionOutput.model_validate(extraction_payload))


def test_references_keep_equal_item_ids_in_different_orders(evaluation_request, comparison_payload):
    result = IntentComparisonOutput.model_validate(comparison_payload)
    assert result.validate_for_request(evaluation_request) is result
    refs = result.findings[0].related_items
    assert len(set(refs)) == 2
    assert refs[0].item_id == refs[1].item_id
    assert refs[0].order_id != refs[1].order_id


@pytest.mark.parametrize("entry", ["findings", "confirmation_requests"])
@pytest.mark.parametrize("reference", [
    {"order_id": "absent-order", "item_id": "line-1"},
    {"order_id": "order-1", "item_id": "absent-item"},
])
def test_absent_order_or_item_is_rejected(evaluation_request, comparison_payload, entry, reference):
    comparison_payload[entry][0]["related_items"] = [reference]
    with pytest.raises(ValueError, match="outside"):
        IntentComparisonOutput.model_validate(comparison_payload).validate_for_request(evaluation_request)


def test_item_cannot_be_borrowed_from_another_order(analysis_input_payload, extraction_payload, comparison_payload):
    analysis_input_payload["order"]["orders"][1]["items"][0]["item_id"] = "line-2"
    request = IntentAnalysisRequest.model_validate(analysis_input_payload).with_extracted_intent(
        IntentExtractionOutput.model_validate(extraction_payload)
    )
    comparison_payload["findings"][0]["related_items"] = [{"order_id": "order-1", "item_id": "line-2"}]
    with pytest.raises(ValueError, match="outside"):
        IntentComparisonOutput.model_validate(comparison_payload).validate_for_request(request)


def test_duplicate_reference_within_finding_is_rejected(comparison_payload):
    comparison_payload["findings"][0]["related_items"] *= 2
    with pytest.raises(ValidationError, match="unique"):
        IntentComparisonOutput.model_validate(comparison_payload)


@pytest.mark.parametrize("comparison", ["MATCH", "MISMATCH", False, None])
def test_uncertain_findings_cannot_be_relabelled(comparison_payload, comparison):
    comparison_payload["comparison"] = comparison
    with pytest.raises(ValidationError):
        IntentComparisonOutput.model_validate(comparison_payload)


def test_explicit_mismatch_and_clean_match_are_representable(comparison_payload):
    mismatch = deepcopy(comparison_payload)
    mismatch["comparison"] = "MISMATCH"
    mismatch["findings"][0]["code"] = "INTENT_MISMATCH"
    assert IntentComparisonOutput.model_validate(mismatch).comparison == "MISMATCH"
    match = {"comparison": "MATCH", "findings": [], "confirmation_requests": []}
    assert IntentComparisonOutput.model_validate(match).comparison == "MATCH"
