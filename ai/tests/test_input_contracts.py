"""Input validation uses synthetic data only; no external service is required."""

from copy import deepcopy
from decimal import Decimal
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts import FraudEvaluationRequest, Money, PurchaseIntent


def money(amount: int, currency: str = "KRW") -> dict:
    return {"amount_minor": amount, "currency": currency}


@pytest.fixture
def payload() -> dict:
    def merchant_order(order_id: str, merchant_id: str, amount: int) -> dict:
        return {
            "order_id": order_id,
            "merchant_id": merchant_id,
            "items": [{
                "item_id": "line-1",
                "product_id": "sample-product",
                "name": "테스트 상품",
                "quantity": 1,
                "unit_price": money(amount),
                "line_total": money(amount),
            }],
            "total": money(amount),
        }

    return {
        "intent": {
            "purchase_request_id": "purchase-1",
            "source": "user_direct",
            "items": [{"name": "테스트 상품", "quantity": 2}],
            "budget": {"amount": money(50000), "kind": "maximum"},
            "explicit_conditions": ["두 개 구매"],
            "preferences": ["빠른 배송 선호"],
            "ambiguities": ["판매처 미지정"],
        },
        "order": {
            "purchase_request_id": "purchase-1",
            "reference": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
            "orders": [merchant_order("order-1", "seller-a", 20000), merchant_order("order-2", "seller-b", 25000)],
            "total": money(45000),
        },
        "context": {
            "evaluation_id": "evaluation-1",
            "user_id": "user-1",
            "purchase_request_id": "purchase-1",
            "approval": {
                "approval_id": "approval-1",
                "snapshot": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
            },
        },
    }


def at_path(data: dict, path: tuple):
    current = data
    for key in path:
        current = current[key]
    return current


def test_multi_merchant_request_preserves_order_and_be_references(payload):
    request = FraudEvaluationRequest.model_validate(payload)
    assert request.context.user_id == "user-1"
    assert request.context.purchase_request_id == "purchase-1"
    assert request.order.reference.version == "2"
    assert request.order.reference.snapshot_hash == "a" * 64
    assert request.context.approval.approval_id == "approval-1"
    assert [order.merchant_id for order in request.order.orders] == ["seller-a", "seller-b"]
    assert [order.order_id for order in request.order.orders] == ["order-1", "order-2"]
    assert request.intent.explicit_conditions == ("두 개 구매",)
    assert request.intent.preferences == ("빠른 배송 선호",)
    assert request.intent.ambiguities == ("판매처 미지정",)


def test_json_and_dictionary_accept_the_same_input(payload):
    request = FraudEvaluationRequest.model_validate_json(json.dumps(payload))
    assert request == FraudEvaluationRequest.model_validate(payload)
    assert FraudEvaluationRequest.model_validate_json(request.model_dump_json()) == request
    assert request.model_dump(mode="json", exclude_unset=True) == payload


def test_unspecified_values_remain_unknown(payload):
    payload["intent"] = {"purchase_request_id": "purchase-1", "source": "unknown"}
    payload["context"].pop("approval")
    request = FraudEvaluationRequest.model_validate(payload)
    assert request.intent.budget is None
    assert request.intent.items is None
    assert request.intent.explicit_conditions is None
    assert request.intent.preferences is None
    assert request.intent.ambiguities is None
    assert request.context.approval is None
    assert request.order.orders[0].shipping is None
    assert request.order.orders[0].tax is None
    assert request.order.orders[0].discount is None


def test_explicit_zero_and_empty_are_distinct_from_unknown(payload):
    payload["intent"]["budget"]["amount"] = money(0)
    payload["intent"]["explicit_conditions"] = []
    payload["order"]["orders"][0]["shipping"] = money(0)
    request = FraudEvaluationRequest.model_validate(payload)
    assert request.intent.budget.amount.amount_minor == 0
    assert request.intent.explicit_conditions == ()
    assert request.order.orders[0].shipping.amount_minor == 0


@pytest.mark.parametrize("currency", ["KRW", "USD", "JPY", "KWD"])
@pytest.mark.parametrize("amount", [0, 1, 9007199254740993])
def test_money_keeps_exact_minor_units(currency, amount):
    result = Money.model_validate_json(json.dumps(money(amount, currency)))
    assert result.amount_minor == amount
    assert type(result.amount_minor) is int
    assert result.currency == currency


@pytest.mark.parametrize("amount", [-1, 1.0, 0.1, "1000", True, False, None, Decimal("1")])
def test_money_rejects_negative_or_non_integer_values(amount):
    with pytest.raises(ValidationError):
        Money.model_validate(money(amount))


@pytest.mark.parametrize("currency", ["AAA", "krw", "KR", "KRWW", " KRW", "KRW ", "XXX", "XTS", "XAU", "", None, 410])
def test_invalid_or_unitless_currency_is_rejected(currency):
    with pytest.raises(ValidationError):
        Money.model_validate(money(1, currency))


@pytest.mark.parametrize("path", [
    ("intent",), ("order",), ("context",),
    ("intent", "purchase_request_id"), ("intent", "source"),
    ("order", "purchase_request_id"), ("order", "reference"),
    ("order", "reference", "snapshot_id"), ("order", "reference", "version"),
    ("order", "reference", "snapshot_hash"), ("order", "total"),
    ("order", "orders", 0, "merchant_id"), ("order", "orders", 0, "items"),
    ("order", "orders", 0, "items", 0, "quantity"),
    ("order", "total", "currency"), ("order", "total", "amount_minor"),
    ("context", "user_id"), ("context", "evaluation_id"),
    ("context", "purchase_request_id"), ("intent", "budget", "kind"),
])
def test_required_fields_cannot_be_omitted(payload, path):
    del at_path(payload, path[:-1])[path[-1]]
    with pytest.raises(ValidationError):
        FraudEvaluationRequest.model_validate(payload)


@pytest.mark.parametrize("quantity", [0, -1, 1.5, "1", True, None])
def test_order_quantity_requires_a_positive_integer(payload, quantity):
    payload["order"]["orders"][0]["items"][0]["quantity"] = quantity
    with pytest.raises(ValidationError):
        FraudEvaluationRequest.model_validate(payload)


@pytest.mark.parametrize("quantity", [0, -1, 1.5, "1", True])
def test_known_intent_quantity_requires_a_positive_integer(payload, quantity):
    payload["intent"]["items"][0]["quantity"] = quantity
    with pytest.raises(ValidationError):
        FraudEvaluationRequest.model_validate(payload)


def test_unknown_intent_quantity_is_preserved(payload):
    payload["intent"]["items"][0].pop("quantity")
    assert FraudEvaluationRequest.model_validate(payload).intent.items[0].quantity is None


@pytest.mark.parametrize("path,value", [
    (("context", "user_id"), "  "),
    (("context", "purchase_request_id"), ""),
    (("order", "reference", "version"), 2),
    (("order", "reference", "snapshot_hash"), "not-a-sha256"),
    (("order", "reference", "snapshot_hash"), "g" * 64),
    (("order", "orders"), []),
    (("order", "orders", 0, "items"), []),
    (("intent", "source"), "approved"),
    (("intent", "budget", "kind"), "unlimited"),
    (("intent", "preferences"), "not an array"),
    (("intent", "preferences"), [" "]),
    (("intent", "preferences"), [1]),
])
def test_invalid_shapes_are_rejected(payload, path, value):
    at_path(payload, path[:-1])[path[-1]] = value
    with pytest.raises(ValidationError):
        FraudEvaluationRequest.model_validate(payload)


@pytest.mark.parametrize("path", [(), ("intent",), ("context",), ("order", "orders", 0)])
def test_unrecognized_approval_flags_are_rejected(payload, path):
    at_path(payload, path)["approved"] = True
    with pytest.raises(ValidationError):
        FraudEvaluationRequest.model_validate(payload)


@pytest.mark.parametrize("section", ["intent", "order", "context"])
def test_cross_purchase_request_mix_is_rejected(payload, section):
    payload[section]["purchase_request_id"] = "another-purchase"
    with pytest.raises(ValidationError, match="same purchase request"):
        FraudEvaluationRequest.model_validate(payload)


@pytest.mark.parametrize("path", [
    ("intent", "budget", "amount"), ("order", "total"),
    ("order", "orders", 0, "total"),
    ("order", "orders", 0, "items", 0, "unit_price"),
    ("order", "orders", 0, "items", 0, "line_total"),
])
def test_mixed_currencies_are_rejected_without_conversion(payload, path):
    at_path(payload, path)["currency"] = "USD"
    with pytest.raises(ValidationError, match="mixed currencies"):
        FraudEvaluationRequest.model_validate(payload)


@pytest.mark.parametrize("charge", ["shipping", "tax", "discount"])
@pytest.mark.parametrize("path", [("order",), ("order", "orders", 0)])
def test_optional_charges_require_the_order_currency(payload, charge, path):
    at_path(payload, path)[charge] = money(100, "USD")
    with pytest.raises(ValidationError, match="mixed currencies"):
        FraudEvaluationRequest.model_validate(payload)


def test_whole_purchase_adjustments_are_preserved_without_distribution(payload):
    payload["order"]["shipping"] = money(3000)
    payload["order"]["tax"] = money(2000)
    payload["order"]["discount"] = money(1000)
    payload["order"]["total"] = money(49000)
    request = FraudEvaluationRequest.model_validate(payload)
    assert request.order.shipping.amount_minor == 3000
    assert request.order.tax.amount_minor == 2000
    assert request.order.discount.amount_minor == 1000
    assert request.order.orders[0].shipping is None
    assert request.order.orders[0].tax is None
    assert request.order.orders[0].discount is None


def test_duplicate_order_references_are_rejected(payload):
    payload["order"]["orders"][1]["order_id"] = "order-1"
    with pytest.raises(ValidationError, match="order IDs"):
        FraudEvaluationRequest.model_validate(payload)


def test_duplicate_item_references_within_order_are_rejected(payload):
    items = payload["order"]["orders"][0]["items"]
    items.append(deepcopy(items[0]))
    with pytest.raises(ValidationError, match="item IDs"):
        FraudEvaluationRequest.model_validate(payload)


def test_same_seller_can_have_multiple_distinct_orders(payload):
    payload["order"]["orders"][1]["merchant_id"] = "seller-a"
    assert len(FraudEvaluationRequest.model_validate(payload).order.orders) == 2


def test_risk_signals_are_preserved_without_making_approval_decisions(payload):
    payload["intent"]["source"] = "agent_summary"
    payload["intent"]["budget"] = {"amount": money(100), "kind": "approximate"}
    payload["order"]["total"] = money(90000)
    payload["context"]["approval"]["snapshot"]["version"] = "1"
    payload["context"]["approval"]["snapshot"]["snapshot_hash"] = "b" * 64
    request = FraudEvaluationRequest.model_validate(payload)
    assert request.order.total.amount_minor == 90000
    assert request.intent.budget.kind == "approximate"
    assert request.context.approval.snapshot.version == "1"
    assert request.context.approval.snapshot.snapshot_hash == "b" * 64


def test_validated_input_is_detached_from_mutable_payload(payload):
    request = FraudEvaluationRequest.model_validate(payload)
    payload["order"]["orders"].clear()
    payload["intent"]["preferences"].append("changed")
    assert len(request.order.orders) == 2
    assert request.intent.preferences == ("빠른 배송 선호",)
    with pytest.raises(ValidationError, match="frozen"):
        request.context.user_id = "changed"


def test_error_text_does_not_echo_user_text():
    private_text = "synthetic-private-purchase-text"
    with pytest.raises(ValidationError) as caught:
        PurchaseIntent.model_validate({"purchase_request_id": "p-1", "source": private_text})
    assert private_text not in str(caught.value)
