"""Default policy adoption in request snapshots and the native entry point."""

import pytest

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.native_evaluation import evaluate_fraud
from copenhagen_fds.risk_settings import default_risk_policy


def _default_request(original):
    data = original.model_dump()
    del data["risk_policy"]
    return EngineEvaluationRequest.model_validate(data)


def test_default_is_materialized_in_json_and_hash_without_replacing_explicit_policy(engine_cases):
    original, _ = engine_cases.inputs()
    default = _default_request(original)
    assert default.risk_policy.version == "fds-risk-policy-v2"
    assert default.risk_policy == default_risk_policy()
    assert default.risk_policy.validation_status == "UNVALIDATED"
    assert EngineEvaluationRequest.model_validate_json(default.model_dump_json()) == default
    explicit = EngineEvaluationRequest.model_validate({**original.model_dump(), "risk_policy": default_risk_policy()})
    assert EngineRequestReference.from_request(default) == EngineRequestReference.from_request(explicit)
    assert EngineRequestReference.from_request(default).policies_sha256 != EngineRequestReference.from_request(original).policies_sha256
    restored = EngineEvaluationRequest.model_validate_json(original.model_dump_json())
    assert restored == original and restored.risk_policy.floor_rules[0].minimum_risk == "HIGH"


@pytest.mark.parametrize("invalid", [None, {}, {"version": "incomplete-policy"}])
def test_explicit_invalid_policy_is_not_silently_replaced(engine_cases, invalid):
    original, _ = engine_cases.inputs()
    with pytest.raises(ValueError):
        EngineEvaluationRequest.model_validate({**original.model_dump(), "risk_policy": invalid})


@pytest.mark.parametrize("amount,missing,signal,status,risk,advice", [
    (10000, False, "MATCH", "SUCCESS", "LOW", "NO_ADDITIONAL_REVIEW"),
    (1000000, False, "MATCH", "SUCCESS", "MEDIUM", "REVIEW_ORDER"),
    (1000000, True, "MATCH", "INCOMPLETE", "UNKNOWN", None),
    (1000000, False, "COMPARISON_FAILED", "FAILED", "UNKNOWN", None),
])
def test_native_entrypoint_uses_default_and_preserves_unresolved_state(
    final_cases, run_async, amount, missing, signal, status, risk, advice,
):
    case = final_cases.prepare(amount=amount, missing=missing, signal=signal)
    request = _default_request(case.request)
    result = run_async(evaluate_fraud(request, case.adapter, budget=case.budget))
    assert (result.outcome.status, result.outcome.risk_level, result.outcome.recommendation) == (status, risk, advice)
    assert result.policy_result.decision.policy == default_risk_policy()
    assert result.execution.request.risk_policy == request.risk_policy
    assert NativeEvaluationResult.model_validate_json(result.model_dump_json()) == result
    if risk == "MEDIUM":
        # The replay fixture also contributes 4.1667 points for its 14:00 purchase.
        assert result.policy_result.decision.behavior_score.value == pytest.approx(44.166666666666664)
        assert [rule.rule_id for rule in result.policy_result.decision.rule_evidence] == ["maximum-amount"]
