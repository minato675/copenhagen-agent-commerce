"""Whole-contract fixtures with no network, database or real credentials."""

from copy import deepcopy
import socket

import pytest

from copenhagen_fds.settings import load_settings


@pytest.fixture(autouse=True)
def offline_contract_tests(monkeypatch):
    def reject_connection(*args, **kwargs):
        pytest.fail("contract tests must not open network or database connections")

    monkeypatch.setattr(socket, "create_connection", reject_connection)
    monkeypatch.setattr(socket.socket, "connect", reject_connection)
    monkeypatch.setattr(socket.socket, "connect_ex", reject_connection)


@pytest.fixture
def contract_settings():
    return load_settings({"FDS_MODE": "MOCK", "FDS_POLICY_VERSION": "contract-test-v1"})


@pytest.fixture
def contract_payload(decision_payload, contract_settings):
    def money(value):
        return {"amount_minor": value, "currency": "KRW"}

    request = {
        "intent": {
            "purchase_request_id": "purchase-1",
            "source": "user_direct",
            "version": "intent-v1",
            "items": [{"name": "테스트 키보드", "quantity": 1}],
            "budget": {"amount": money(100000), "kind": "maximum"},
            "explicit_conditions": ["키보드 한 개"],
            "preferences": ["조용한 제품 선호"],
            "ambiguities": [],
        },
        "order": {
            "purchase_request_id": "purchase-1",
            "reference": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
            "orders": [{
                "order_id": "order-1",
                "merchant_id": "seller-a",
                "items": [{
                    "item_id": "line-1",
                    "product_id": "sample-keyboard",
                    "name": "테스트 키보드",
                    "quantity": 1,
                    "unit_price": money(80000),
                    "line_total": money(80000),
                }],
                "total": money(80000),
            }],
            "total": money(82000),
            "shipping": money(3000),
            "tax": money(0),
            "discount": money(1000),
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
    decision_payload["metadata"]["policy_version"] = contract_settings.policy_version
    decision_payload["intent"]["metadata"]["model_version"] = contract_settings.openai_model
    return {"request": request, "decision": decision_payload}


@pytest.fixture
def scenario_payload(contract_payload, request):
    payload = deepcopy(contract_payload)
    name = request.param
    order = payload["request"]["order"]
    intent = payload["request"]["intent"]
    decision = payload["decision"]

    if name == "multi_merchant":
        second = deepcopy(order["orders"][0])
        second.update(order_id="order-2", merchant_id="seller-b")
        order["orders"].append(second)
        order["total"]["amount_minor"] = 162000
        intent["items"][0]["quantity"] = 2
        intent["budget"]["amount"]["amount_minor"] = 200000
    elif name in ("semantic_unknown", "not_collected"):
        decision["intent"]["comparison"] = "UNKNOWN"
        decision["intent"]["reasons"] = [{"code": "INSUFFICIENT_DATA", "source": "MOCK"}]
        if name == "not_collected":
            payload["request"]["intent"] = {"purchase_request_id": "purchase-1", "source": "unknown"}
            payload["request"]["context"]["approval"] = None
            for result in (decision, decision["intent"], decision["sift"], *decision["rules"]):
                result["metadata"]["target"]["intent_version"] = None
    elif name == "provider_failed":
        decision["sift"].update(
            status="FAILED", score=None, reasons=[{"code": "TIMEOUT", "source": "MOCK"}],
        )
        decision["sift"]["metadata"]["valid_until"] = None
    elif name == "not_run":
        for result, field in (
            (decision["intent"], "comparison"),
            (decision["sift"], "score"),
            (decision["rules"][0], "risk_level"),
        ):
            result.update(status="NOT_RUN", reasons=[{"code": "NOT_EXECUTED", "source": "MOCK"}])
            result[field] = None
            result["metadata"].update(evaluated_at=None, valid_until=None, model_version=None, prompt_version=None, policy_version=None)
    elif name != "normal":
        raise ValueError("unknown synthetic scenario")

    if name in ("semantic_unknown", "not_collected", "provider_failed", "not_run"):
        not_run = name == "not_run"
        decision.update(
            status="NOT_RUN" if not_run else "INCOMPLETE",
            risk_level="UNKNOWN",
            recommendation=None,
            reasons=[{"code": "NOT_EXECUTED" if not_run else "REQUIRED_EVALUATION_UNAVAILABLE", "source": "MOCK"}],
            review_actions=["CHECK_INPUT"],
        )
        decision["metadata"]["valid_until"] = None
        if not_run:
            decision["metadata"].update(evaluated_at=None, policy_version=None)
    return payload
