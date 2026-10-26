"""Exercise input/result/settings together across a standard JSON boundary."""

from copy import deepcopy
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts import FraudDecision, FraudEvaluationRequest
from copenhagen_fds.settings import EvaluationMode


def decode_exchange(payload, decoder="json"):
    # The wrapper is a test transport fixture, not a new public API envelope.
    wire = json.dumps(payload, ensure_ascii=False, allow_nan=False)
    decoded = json.loads(wire)
    if decoder == "json":
        request = FraudEvaluationRequest.model_validate_json(json.dumps(decoded["request"]))
        decision = FraudDecision.model_validate_json(json.dumps(decoded["decision"]))
    else:
        request = FraudEvaluationRequest.model_validate(decoded["request"])
        decision = FraudDecision.model_validate(decoded["decision"])
    decision.validate_for_request(request)
    return request, decision


@pytest.mark.parametrize("scenario_payload", [
    "normal", "multi_merchant", "semantic_unknown", "provider_failed", "not_collected", "not_run",
], indirect=True)
@pytest.mark.parametrize("decoder", ["json", "dict"])
def test_exchange_round_trip_preserves_all_supplied_information(scenario_payload, contract_settings, decoder):
    request, decision = decode_exchange(scenario_payload, decoder)
    assert contract_settings.mode == EvaluationMode.MOCK
    assert contract_settings.gms_key is None
    assert "sift_api_key" not in contract_settings.model_dump()

    supplied = {
        "request": request.model_dump(mode="json", exclude_unset=True),
        "decision": decision.model_dump(mode="json", exclude_unset=True),
    }
    assert supplied == scenario_payload
    round_tripped = {
        "request": request.model_dump(mode="json"),
        "decision": decision.model_dump(mode="json"),
    }
    restored_request, restored_decision = decode_exchange(round_tripped, decoder)
    assert restored_request == request
    assert restored_decision == decision
    assert restored_decision.metadata.target.order == restored_request.order.reference
    assert restored_decision.metadata.target.intent_version == restored_request.intent.version
    if decision.status != "NOT_RUN":
        assert decision.metadata.policy_version == contract_settings.policy_version
        assert decision.intent.metadata.model_version == contract_settings.openai_model


@pytest.mark.parametrize("scenario_payload", ["semantic_unknown", "provider_failed", "not_collected", "not_run"], indirect=True)
def test_unavailable_states_remain_explicit_after_exchange(scenario_payload):
    request, decision = decode_exchange(scenario_payload)
    assert decision.risk_level == "UNKNOWN"
    assert decision.recommendation is None
    assert decision.metadata.valid_until is None
    assert decision.reasons
    original_intent = scenario_payload["decision"]["intent"]
    assert decision.intent.comparison == original_intent["comparison"]
    if decision.sift.status != "SUCCESS":
        assert decision.sift.score is None
        assert decision.sift.status == scenario_payload["decision"]["sift"]["status"]
    if request.intent.source == "unknown":
        assert request.intent.budget is None
        assert request.intent.items is None
        assert request.intent.explicit_conditions is None
        assert request.context.approval is None
        assert decision.metadata.target.intent_version is None


@pytest.mark.parametrize("scenario_payload", ["multi_merchant"], indirect=True)
def test_multiple_merchants_and_overall_adjustments_are_not_flattened(scenario_payload):
    request, decision = decode_exchange(scenario_payload)
    assert [(order.order_id, order.merchant_id) for order in request.order.orders] == [
        ("order-1", "seller-a"), ("order-2", "seller-b"),
    ]
    assert request.order.total.amount_minor == 162000
    assert request.order.shipping.amount_minor == 3000
    assert request.order.tax.amount_minor == 0
    assert request.order.discount.amount_minor == 1000
    assert all(order.shipping is None for order in request.order.orders)
    assert decision.metadata.target.purchase_request_id == request.context.purchase_request_id


@pytest.mark.parametrize("amount", [0, 9007199254740993, 9223372036854775807])
def test_nested_money_does_not_pass_through_floating_point(contract_payload, amount):
    order = contract_payload["request"]["order"]
    order["total"]["amount_minor"] = amount
    order["orders"][0]["total"]["amount_minor"] = amount
    order["orders"][0]["items"][0]["unit_price"]["amount_minor"] = amount
    order["orders"][0]["items"][0]["line_total"]["amount_minor"] = amount
    contract_payload["request"]["intent"]["budget"]["amount"]["amount_minor"] = amount
    request, _ = decode_exchange(contract_payload)
    assert request.order.total.amount_minor == amount
    assert type(request.order.total.amount_minor) is int
    assert request.order.orders[0].items[0].line_total.amount_minor == amount
    assert request.intent.budget.amount.amount_minor == amount


@pytest.mark.parametrize("currency", ["KRW", "USD", "JPY", "KWD"])
def test_consistent_currency_survives_the_whole_exchange(contract_payload, currency):
    def replace_currency(node):
        if isinstance(node, dict):
            if "currency" in node:
                node["currency"] = currency
            for child in node.values():
                replace_currency(child)
        elif isinstance(node, list):
            for child in node:
                replace_currency(child)

    replace_currency(contract_payload["request"])
    request, _ = decode_exchange(contract_payload)
    assert request.order.total.currency == currency
    assert request.intent.budget.amount.currency == currency
    assert request.order.orders[0].items[0].unit_price.currency == currency


@pytest.mark.parametrize("field", ["evaluation_id", "user_id", "purchase_request_id", "intent_version"])
def test_internally_consistent_result_for_another_request_is_rejected(contract_payload, field):
    result = contract_payload["decision"]
    for component in (result, result["intent"], result["sift"], *result["rules"]):
        component["metadata"]["target"][field] = "different-target"
    # The result is valid on its own; the request/result binding is the missing constraint.
    FraudDecision.model_validate(result)
    with pytest.raises(ValueError, match="supplied evaluation request"):
        decode_exchange(contract_payload)


@pytest.mark.parametrize("field,value", [("snapshot_id", "checkout-2"), ("version", "3"), ("snapshot_hash", "b" * 64)])
def test_result_cannot_be_attached_to_another_snapshot(contract_payload, field, value):
    result = contract_payload["decision"]
    for component in (result, result["intent"], result["sift"], *result["rules"]):
        component["metadata"]["target"]["order"][field] = value
    FraudDecision.model_validate(result)
    with pytest.raises(ValueError, match="supplied evaluation request"):
        decode_exchange(contract_payload)


def test_unknown_input_version_cannot_silently_acquire_a_result_version(contract_payload):
    contract_payload["request"]["intent"].pop("version")
    with pytest.raises(ValueError, match="supplied evaluation request"):
        decode_exchange(contract_payload)


def test_binding_uses_current_order_not_a_prior_approval_snapshot(contract_payload):
    prior = contract_payload["request"]["context"]["approval"]["snapshot"]
    prior.update(version="1", snapshot_hash="b" * 64)
    request, decision = decode_exchange(contract_payload)
    assert request.context.approval.snapshot.version == "1"
    assert decision.metadata.target.order.version == "2"
    assert decision.metadata.target.order != request.context.approval.snapshot


def test_calling_binding_check_does_not_rewrite_either_contract(contract_payload):
    request, decision = decode_exchange(contract_payload)
    request_before = request.model_dump_json()
    result_before = decision.model_dump_json()
    assert decision.validate_for_request(request) is decision
    assert request.model_dump_json() == request_before
    assert decision.model_dump_json() == result_before


@pytest.mark.parametrize("scenario_payload", ["semantic_unknown"], indirect=True)
def test_boolean_semantic_conversion_is_rejected_at_json_boundary(scenario_payload):
    scenario_payload["decision"]["intent"]["comparison"] = False
    with pytest.raises(ValidationError):
        decode_exchange(scenario_payload)


@pytest.mark.parametrize("scenario_payload", ["provider_failed"], indirect=True)
def test_filling_a_failed_score_with_zero_is_rejected_at_json_boundary(scenario_payload):
    scenario_payload["decision"]["sift"]["score"] = {"value": 0, "unit": "SIFT_PAYMENT_ABUSE_0_1"}
    with pytest.raises(ValidationError):
        decode_exchange(scenario_payload)


def test_timezone_representations_preserve_the_same_evaluation_instant(contract_payload):
    payload = deepcopy(contract_payload)
    payload["decision"]["metadata"]["evaluated_at"] = "2026-09-09T01:01:00Z"
    original_request, original_decision = decode_exchange(contract_payload)
    restored_request, restored_decision = decode_exchange(payload)
    assert original_request == restored_request
    assert original_decision.metadata.evaluated_at == restored_decision.metadata.evaluated_at
    assert original_decision.metadata.valid_until == restored_decision.metadata.valid_until
