"""S15P21A207-301: execute real native stages once with the caller's original budget."""

from datetime import datetime, timedelta, timezone

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse
from copenhagen_fds.provider_runtime.evaluation import evaluate_provider_request


@pytest.mark.parametrize("options,status,calls", [({}, "SUCCESS", 2),
    ({"signal": "MISMATCH"}, "SUCCESS", 2), ({"signal": "UNKNOWN"}, "INCOMPLETE", 2),
    ({"missing": True}, "INCOMPLETE", 2), ({"signal": "NOT_RUN"}, "INCOMPLETE", 0),
    ({"signal": "EXTRACTION_FAILED"}, "FAILED", 1), ({"signal": "COMPARISON_FAILED"}, "FAILED", 2)])
def test_runtime_calls_native_entrypoint_once_with_original_input_and_budget(
    provider_case, run_async, monkeypatch, options, status, calls,
):
    import copenhagen_fds.provider_runtime.evaluation as module
    native, seen = module.evaluate_fraud, []

    async def observe(request, adapter, *, budget):
        record = await native(request, adapter, budget=budget)
        seen.append((request, adapter, budget, record))
        return record

    monkeypatch.setattr(module, "evaluate_fraud", observe)
    case = provider_case(**options)
    before = case.request.model_dump_json()
    response = ProviderEvaluationResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
    assert len(seen) == 1 and len(case.adapter.calls) == calls and case.adapter.closed == 1
    request, context, budget, record = seen[0]
    assert request == case.request.evaluation and context.identity == case.bound.identity
    assert context.budget is budget and all(call_budget is budget for _, call_budget in case.adapter.calls)
    assert response.outcome == record.outcome and response.outcome.status == status
    decision = record.policy_result.decision
    assert (response.behavior_score, response.groups, response.reasons) == (
        decision.behavior_score, decision.groups, decision.reasons)
    assert response.stages == (*record.execution.stages, record.policy_stage)
    assert response.record_sha256 == personalization_hash(record)
    assert case.request.model_dump_json() == before


def test_trusted_handler_can_retain_record_while_only_summary_crosses_runtime(provider_case, run_async):
    retained = []

    async def handler(request, context):
        bundle = await evaluate_provider_request(request, context)
        retained.append(bundle)
        return bundle.response

    case = provider_case(handler=handler, profile=True)
    encoded = run_async(case.execute())
    response = ProviderEvaluationResponse.model_validate_json(encoded)
    bundle = retained[0]
    assert response == bundle.response
    assert response.record_sha256 == personalization_hash(bundle.record)
    text = case.request.evaluation.analysis.user_request.text
    assert text not in encoded.decode() and text not in repr(bundle)
    for forbidden in ('"retained_samples"', '"user_request"', '"payload"', '"telemetry"', '"execution"'):
        assert forbidden not in encoded.decode()
    assert response.result_valid_until is None and response.storage_status == "NOT_PERSISTED"


def test_future_submission_is_rejected_before_model_calls(provider_case, run_async):
    case = provider_case()
    data = case.request.model_dump()
    data["call"]["sent_at"] = datetime.now(timezone.utc) + timedelta(days=1)
    request = type(case.request).model_validate(data)
    case.bound.raw = request.model_dump_json().encode()
    response = ProviderError.model_validate_json(run_async(case.execute()))
    assert response.code == "INVALID_CONTRACT"
    assert not case.adapter.calls and case.adapter.closed == 1
