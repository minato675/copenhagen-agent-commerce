from datetime import timedelta

import pytest

from copenhagen_fds.contracts.risk_intent import RiskIntentSignal
from copenhagen_fds.risk_intent import interpret_risk_intent


@pytest.mark.parametrize("state,execution,reason", [
    ("MATCH", "SUCCESS", None), ("MISMATCH", "SUCCESS", "INTENT_MISMATCH"),
    ("UNKNOWN", "SUCCESS", "INTENT_UNKNOWN"), ("FAILED", "FAILED", "INTENT_FAILED"),
    ("NOT_RUN", "NOT_RUN", "INTENT_NOT_RUN"),
])
def test_signal_decision_table_and_lossless_roundtrip(intent_cases, state, execution, reason):
    supplied = intent_cases.request(state=state, detailed=state in {"MISMATCH", "UNKNOWN"})
    result = interpret_risk_intent(supplied)
    assert result.signal == state and result.source.status == execution
    assert [r.code for r in result.reasons] == ([] if reason is None else [reason])
    assert result.source == supplied.intent
    assert RiskIntentSignal.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    assert not {"score", "risk_level", "probability", "recommendation"}.intersection(type(result).model_fields)


@pytest.mark.parametrize("kind", ["TIMEOUT", "API_ERROR", "REFUSAL", "INCOMPLETE_RESPONSE",
                                  "SCHEMA_ERROR", "INSUFFICIENT_INPUT", "INVALID_INPUT"])
def test_failure_kinds_never_become_semantic_unknown_or_match(intent_cases, kind):
    supplied = intent_cases.request(state="FAILED", failure=dict(kind=kind))
    result = interpret_risk_intent(supplied)
    assert result.signal == "FAILED" and result.source.payload is None
    assert result.source.failure.kind == kind
    assert result.source.reasons == supplied.intent.reasons


@pytest.mark.parametrize("field", ["user_id", "purchase_request_id", "intent_version", "version", "trace_id"])
def test_target_binding_revalidated_at_function_boundary(intent_cases, field):
    supplied = intent_cases.request()
    metadata = supplied.intent.metadata
    target = metadata.target
    if field == "trace_id":
        metadata = metadata.model_copy(update={field: "other-trace"})
    else:
        change = {"order": target.order.model_copy(update={field: "other-order-version"})} if field == "version" else {
            field: "other-target"}
        metadata = metadata.model_copy(update={"target": target.model_copy(update=change)})
    unsafe = supplied.model_copy(update={"intent": supplied.intent.model_copy(update={"metadata": metadata})})
    with pytest.raises(ValueError, match="target|trace"):
        interpret_risk_intent(unsafe)


@pytest.mark.parametrize("offset,valid", [(0, True), (-299.999999, True), (-300, False), (0.000001, False)])
def test_freshness_boundary_rejects_expired_or_future_results(intent_cases, offset, valid):
    supplied = intent_cases.request()
    metadata = supplied.intent.metadata.model_copy(update={
        "evaluated_at": supplied.evaluated_at + timedelta(seconds=offset)})
    supplied = supplied.model_copy(update={"intent": supplied.intent.model_copy(update={"metadata": metadata})})
    if valid:
        assert interpret_risk_intent(supplied).signal == "MATCH"
    else:
        with pytest.raises(ValueError, match="exclusive age limit"):
            interpret_risk_intent(supplied)


@pytest.mark.parametrize("state", ["MATCH", "MISMATCH", "UNKNOWN"])
def test_explicit_expiry_is_rejected_not_converted_to_unknown(intent_cases, state):
    supplied = intent_cases.request(state=state)
    metadata = supplied.intent.metadata.model_copy(update={
        "evaluated_at": supplied.evaluated_at - timedelta(seconds=1), "valid_until": supplied.evaluated_at})
    supplied = supplied.model_copy(update={"intent": supplied.intent.model_copy(update={"metadata": metadata})})
    with pytest.raises(ValueError, match="expired intent"):
        interpret_risk_intent(supplied)


def test_detailed_findings_confirmations_and_measurements_preserved(intent_cases):
    supplied = intent_cases.request(state="MISMATCH", detailed=True)
    result = interpret_risk_intent(supplied)
    finding = result.source.payload.findings[0]
    confirmation = result.source.payload.confirmation_requests[0]
    assert finding.code == "INTENT_MISMATCH" and finding.dimension == "PRODUCT"
    assert confirmation.code == "CLARIFY_INTENT" and confirmation.related_items == finding.related_items
    assert finding.related_items[0].item_id == supplied.request.order.orders[0].items[0].item_id
    assert result.source.telemetry.usage.total_tokens == 15
    assert result.source.metadata.model_version == supplied.intent.metadata.model_version
    assert result.source.metadata.prompt_version == supplied.intent.metadata.prompt_version


def test_foreign_item_reference_rejected(intent_cases):
    supplied = intent_cases.request(state="MISMATCH", detailed=True)
    finding = supplied.intent.payload.findings[0]
    foreign = finding.related_items[0].model_copy(update={"item_id": "foreign-item"})
    payload = supplied.intent.payload.model_copy(update={"findings": (finding.model_copy(update={"related_items": (foreign,)}),)})
    supplied = supplied.model_copy(update={"intent": supplied.intent.model_copy(update={"payload": payload})})
    with pytest.raises(ValueError, match="outside the supplied order"):
        interpret_risk_intent(supplied)


@pytest.mark.parametrize("field,value", [("signal", "MATCH"), ("reasons", ()), ("schema_version", "unsupported-v2")])
def test_signal_tampering_rejected(intent_cases, field, value):
    supplied = intent_cases.request(state="UNKNOWN")
    result = interpret_risk_intent(supplied)
    with pytest.raises(ValueError):
        RiskIntentSignal.model_validate({**result.model_dump(), field: value})
    with pytest.raises(ValueError):
        result.model_copy(update={field: value}).validate_for_input(supplied)


def test_source_and_policy_rebinding_rejected(intent_cases):
    supplied = intent_cases.request(state="MISMATCH", detailed=True)
    result = interpret_risk_intent(supplied)
    data = result.model_dump()
    data["source"]["payload"]["confirmation_requests"] = ()
    with pytest.raises(ValueError, match="entire source envelope"):
        RiskIntentSignal.model_validate(data)
    with pytest.raises(ValueError, match="exact valid request and policy"):
        result.validate_for_input(intent_cases.request(state="MISMATCH", detailed=True, required=False))
