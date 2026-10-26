"""Synthetic result fixtures shared by result contract tests."""

from copy import deepcopy
import asyncio
import socket

import pytest


@pytest.fixture
def run_async():
    # Initialize Windows asyncio's internal socketpair before the network guard.
    with asyncio.Runner() as runner:
        runner.get_loop()
        yield runner.run


@pytest.fixture(autouse=True)
def block_external_network(monkeypatch, run_async):
    def reject(*args, **kwargs):
        pytest.fail("default AI tests must not access external networks")
    monkeypatch.setattr(socket, "getaddrinfo", reject)
    monkeypatch.setattr(socket, "create_connection", reject)
    monkeypatch.setattr(socket.socket, "connect", reject)
    monkeypatch.setattr(socket.socket, "connect_ex", reject)


@pytest.fixture
def result_metadata():
    return {
        "target": {
            "evaluation_id": "evaluation-1",
            "user_id": "user-1",
            "purchase_request_id": "purchase-1",
            "order": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
            "intent_version": "intent-v1",
        },
        "trace_id": "trace-1",
        "sources": ["MOCK"],
        "evaluated_at": "2026-09-09T10:00:00+09:00",
        "valid_until": "2026-09-09T10:10:00+09:00",
    }


@pytest.fixture
def component_payloads(result_metadata):
    def base(**versions):
        metadata = deepcopy(result_metadata)
        metadata.update(versions)
        return {"status": "SUCCESS", "metadata": metadata, "reasons": []}

    return {
        "intent": {
            **base(model_version="test-model-v1", prompt_version="prompt-v1"),
            "comparison": "MATCH",
        },
        "sift": {**base(), "score": {"value": 0.2, "unit": "SIFT_PAYMENT_ABUSE_0_1"}},
        "rule": {**base(policy_version="rules-v1"), "rule_id": "rule-1", "risk_level": "LOW"},
    }


@pytest.fixture
def decision_payload(result_metadata, component_payloads):
    metadata = deepcopy(result_metadata)
    metadata.update(evaluated_at="2026-09-09T10:01:00+09:00", policy_version="policy-v1")
    return {
        "status": "SUCCESS",
        "metadata": metadata,
        "required_components": ["INTENT", "SIFT", "RULES"],
        "intent": component_payloads["intent"],
        "sift": component_payloads["sift"],
        "rules": [component_payloads["rule"]],
        "risk_level": "LOW",
        "recommendation": "ALLOW",
        "reasons": [],
        "review_actions": [],
    }
