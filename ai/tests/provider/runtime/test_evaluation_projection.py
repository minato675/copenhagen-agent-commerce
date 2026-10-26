"""S15P21A207-301: native evidence matches the independent 299 contract fixtures."""

import pytest

from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse
from copenhagen_fds.provider_runtime.evaluation_projection import project_evaluation_response


@pytest.mark.parametrize("options", [{}, {"profile": True}, {"missing": True},
    {"signal": "MISMATCH"}, {"signal": "UNKNOWN"}, {"signal": "EXTRACTION_FAILED"},
    {"signal": "COMPARISON_FAILED"}, {"signal": "NOT_RUN"}])
def test_projection_matches_existing_contract_without_mutating_sources(evaluation_case, options, monkeypatch):
    case = evaluation_case(**options)
    before = (case.request.model_dump_json(), case.native.model_dump_json())

    def forbidden(*args, **kwargs):
        pytest.fail("projection must not rerun scoring or GPT")

    monkeypatch.setattr("copenhagen_fds.engine.evaluate_engine_behavior", forbidden)
    monkeypatch.setattr("copenhagen_fds.engine_finalization.evaluate_risk_policy", forbidden)
    monkeypatch.setattr("copenhagen_fds.engine.IntentAnalyzer.extract", forbidden)
    response = project_evaluation_response(case.request, case.native)
    assert response == case.response
    assert ProviderEvaluationResponse.model_validate_json(response.model_dump_json()) == response
    assert (case.request.model_dump_json(), case.native.model_dump_json()) == before
    assert response.result_valid_until is None
    assert response.reuse_status == "NOT_ASSESSED" and response.storage_status == "NOT_PERSISTED"
    assert case.request.evaluation.analysis.user_request.text is None or (
        case.request.evaluation.analysis.user_request.text not in response.model_dump_json())


def test_projection_rejects_record_from_another_purchase_content(evaluation_case):
    first, other = evaluation_case(amount=10000), evaluation_case(amount=20000)
    with pytest.raises(ValueError):
        project_evaluation_response(first.request, other.native)
