"""S15P21A207-301: distinguish original component faults and dependent non-execution."""

import pytest

from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse


@pytest.mark.parametrize("fault,signal", [("behavior", "MATCH"), ("policy", "MATCH"),
    ("personal", "MATCH"), ("personal", "EXTRACTION_FAILED")])
def test_single_and_compound_failures_preserve_available_evidence(provider_case, run_async, monkeypatch, fault, signal):
    case = provider_case(profile=True, amount=100000, signal=signal)
    before = case.request.model_dump_json()

    def fail(*args, **kwargs):
        raise ArithmeticError("synthetic-private-component-fault")

    paths = {"behavior": "copenhagen_fds.engine.evaluate_engine_behavior",
             "policy": "copenhagen_fds.engine_finalization.evaluate_risk_policy",
             "personal": "copenhagen_fds.personal_amount._calculation"}
    monkeypatch.setattr(paths[fault], fail)
    encoded = run_async(case.execute())
    response = ProviderEvaluationResponse.model_validate_json(encoded).validate_for_request(case.request)
    assert response.outcome.status == "FAILED" and response.outcome.risk_level == "UNKNOWN"
    assert response.outcome.recommendation is None and case.adapter.closed == 1
    assert b"synthetic-private-component-fault" not in encoded
    assert case.request.model_dump_json() == before
    if fault == "behavior":
        assert response.stages[0].status == "FAILED" and not response.features
        assert response.stages[-1].status == "NOT_RUN"
        assert response.stages[-1].skip_reason == "UPSTREAM_FAILED"
    elif fault == "policy":
        assert response.stages[-1].status == "FAILED"
        assert all(s.status == "SUCCESS" for s in response.stages[:-1])
        assert len(response.features) == 9 and any(f.score is not None for f in response.features)
        assert response.behavior_score is None and response.score_coverage == "UNAVAILABLE" and not response.groups
    else:
        assert response.stages[0].failure_code == "CALCULATION_FAILED"
        assert any(f.score is not None for f in response.features)
        assert "EVALUATION_FAILED" in {reason.code for reason in response.reasons}
        if signal == "EXTRACTION_FAILED":
            assert response.stages[1].status == "FAILED" and response.stages[2].status == "NOT_RUN"
            assert "INTENT_FAILED" in {reason.code for reason in response.reasons}


@pytest.mark.parametrize("stage,index", [("extraction", 1), ("comparison", 2)])
def test_provider_timeout_retains_original_failed_stage(provider_case, run_async, stage, index):
    case = provider_case()
    case.adapter.outcomes[stage] = AnalysisFailure(kind="TIMEOUT", retryable=False)
    response = ProviderEvaluationResponse.model_validate_json(run_async(case.execute()))
    assert response.outcome.status == "FAILED"
    assert response.stages[index].status == "FAILED" and response.stages[index].failure_code == "TIMEOUT"
    assert response.stages[0].status == "SUCCESS" and len(response.features) == 9
    if stage == "extraction":
        assert response.stages[2].status == "NOT_RUN" and len(case.adapter.calls) == 1
    assert case.adapter.closed == 1
