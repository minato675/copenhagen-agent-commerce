"""Synthetic analysis data, with no SDK, credentials or network access."""

from copy import deepcopy
import socket

import pytest

from copenhagen_fds.contracts import IntentAnalysisRequest, IntentExtractionOutput


@pytest.fixture(autouse=True)
def offline_analysis_tests(monkeypatch):
    def reject_connection(*args, **kwargs):
        pytest.fail("analysis contract tests must not open network connections")
    monkeypatch.setattr(socket, "create_connection", reject_connection)
    monkeypatch.setattr(socket.socket, "connect", reject_connection)
    monkeypatch.setattr(socket.socket, "connect_ex", reject_connection)


@pytest.fixture
def extraction_payload():
    return {
        "items": [{"name": "조용한 키보드", "quantity": None, "brand": "테스트 브랜드", "category": "키보드"}],
        "budget": {"amount": {"amount_minor": 100000, "currency": "KRW"}, "kind": "approximate"},
        "purpose": "사무실 문서 작성",
        "explicit_conditions": ["한국어 자판"],
        "preferences": ["조용한 제품 선호"],
        "ambiguities": ["구매 수량 미지정"],
    }


@pytest.fixture
def analysis_input_payload():
    def money(amount):
        return {"amount_minor": amount, "currency": "KRW"}
    first_order = {
        "order_id": "order-1", "merchant_id": "seller-a", "total": money(80000),
        "items": [{
            "item_id": "line-1", "name": "조용한 키보드", "quantity": 1,
            "unit_price": money(80000), "line_total": money(80000),
            "brand": "테스트 브랜드", "category": "키보드", "description": "합성 상품 설명",
        }],
    }
    second_order = deepcopy(first_order)
    second_order.update(order_id="order-2", merchant_id="seller-b")
    return {
        "user_request": {
            "purchase_request_id": "purchase-1", "source": "agent_summary", "version": "intent-v1",
            "text": "한국어 자판 키보드. 예산은 대략 10만원, 사무실에서 조용하게 쓰고 싶어.",
        },
        "order": {
            "purchase_request_id": "purchase-1",
            "reference": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
            "orders": [first_order, second_order], "total": money(160000),
        },
        "context": {"evaluation_id": "evaluation-1", "user_id": "user-1", "purchase_request_id": "purchase-1"},
    }


@pytest.fixture
def analysis_request(analysis_input_payload):
    return IntentAnalysisRequest.model_validate(analysis_input_payload)


@pytest.fixture
def evaluation_request(analysis_request, extraction_payload):
    return analysis_request.with_extracted_intent(IntentExtractionOutput.model_validate(extraction_payload))


@pytest.fixture
def comparison_payload():
    return {
        "comparison": "UNKNOWN",
        "findings": [{
            "code": "INTENT_UNCERTAIN", "dimension": "QUANTITY",
            "related_items": [{"order_id": "order-1", "item_id": "line-1"}, {"order_id": "order-2", "item_id": "line-1"}],
        }],
        "confirmation_requests": [{"code": "CLARIFY_INTENT", "related_items": []}],
    }


@pytest.fixture
def analysis_metadata(result_metadata):
    result_metadata.update(model_version="gpt-5.4-mini", prompt_version="synthetic-prompt-v1")
    return result_metadata


@pytest.fixture
def extraction_result_payload(analysis_metadata, extraction_payload):
    return {
        "status": "SUCCESS", "metadata": analysis_metadata, "reasons": [],
        "telemetry": {"gateway": "MOCK", "requested_model": "gpt-5.4-mini", "duration_ms": 12.5},
        "payload": extraction_payload,
    }


@pytest.fixture
def comparison_result_payload(analysis_metadata, comparison_payload):
    return {
        "status": "SUCCESS", "metadata": analysis_metadata,
        "reasons": [{"code": "INTENT_UNCERTAIN", "source": "MOCK"}],
        "telemetry": {"gateway": "MOCK", "requested_model": "gpt-5.4-mini", "response_id": "synthetic-response"},
        "payload": comparison_payload,
    }
