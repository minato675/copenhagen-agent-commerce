"""S15P21A207-299: BE evaluation request/result contract acceptance criteria."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, ProviderEvaluationResponse


@pytest.mark.parametrize("options", [{}, {"profile": True}, {"missing": True}, {"signal": "UNKNOWN"},
                                      {"signal": "EXTRACTION_FAILED"}, {"signal": "NOT_RUN"}])
def test_real_offline_results_roundtrip_and_bind(evaluation_case, options):
    case = evaluation_case(**options)
    request = ProviderEvaluationRequest.model_validate_json(case.request.model_dump_json())
    response = ProviderEvaluationResponse.model_validate_json(case.response.model_dump_json())
    assert request == case.request
    assert response.validate_for_request(request) == case.response
    assert response.storage_status == "NOT_PERSISTED"
    assert response.result_valid_until is None and response.reuse_status == "NOT_ASSESSED"


def test_insufficient_data_is_unknown_and_missing_not_zero(evaluation_case):
    result = evaluation_case(missing=True).response
    assert result.outcome.status == "INCOMPLETE" and result.outcome.risk_level == "UNKNOWN"
    assert result.behavior_score is None and result.score_coverage == "PARTIAL"
    assert result.outcome.recommendation is None
    assert any(row.feature.score is None for group in result.groups for row in group.features)


@pytest.mark.parametrize("path,value", [
    (("call", "trace_id"), "wrong-trace"),
    (("call", "sent_at"), "2000-01-01T00:00:00+09:00"),
    (("call", "sent_at"), "2026-01-07T14:00:00"),
    (("configuration", "content_sha256"), "b" * 64),
    (("evaluation", "analysis", "context", "user_id"), "other-user"),
    (("evaluation", "execution", "requested_model"), "changed-model"),
    (("schema_version",), "fds-provider-v2"),
    (("api_key",), "never-accepted"),
])
def test_bad_request_binding_is_rejected(evaluation_case, path, value):
    data = evaluation_case().request.model_dump()
    current = data
    for key in path[:-1]:
        current = current[key]
    current[path[-1]] = value
    with pytest.raises(ValidationError):
        ProviderEvaluationRequest.model_validate(data)


def test_other_user_profile_is_rejected(evaluation_case):
    data = evaluation_case(profile=True).request.model_dump()
    data["evaluation"]["analysis"]["context"]["user_id"] = "another-user"
    data["evaluation"]["feature_input"]["evaluation"]["request"]["context"]["user_id"] = "another-user"
    with pytest.raises(ValidationError):
        ProviderEvaluationRequest.model_validate(data)


@pytest.mark.parametrize("field,value", [("request_sha256", "f" * 64), ("versions", None), ("engine_reference", None)])
def test_response_cannot_switch_request_or_versions(evaluation_case, field, value):
    case = evaluation_case()
    data = case.response.model_dump()
    if field == "versions":
        data[field]["risk_policy"] = "other-policy"
    elif field == "engine_reference":
        data[field]["target"]["user_id"] = "other-user"
    else:
        data[field] = value
    with pytest.raises(ValueError):
        ProviderEvaluationResponse.model_validate(data).validate_for_request(case.request)


@pytest.mark.parametrize("mutation", ["score", "coverage", "stage", "model", "expiry", "stored", "raw", "missing_feature"])
def test_response_rejects_fabricated_or_leaking_summary(evaluation_case, mutation):
    data = deepcopy(evaluation_case().response.model_dump())
    if mutation == "score":
        data["behavior_score"]["value"] = 101.0
    elif mutation == "coverage":
        data["behavior_score"] = None
    elif mutation == "stage":
        data["features"][0]["stage"] = "PERSONALIZED"
    elif mutation == "model":
        data["versions"]["extraction_model"] = None
    elif mutation == "expiry":
        data["result_valid_until"] = data["completed_at"]
    elif mutation == "stored":
        data["storage_status"] = "SAVED"
    elif mutation == "raw":
        data["original_text"] = "private-input"
    else:
        data["features"] = data["features"][:-1]
    with pytest.raises(ValidationError):
        ProviderEvaluationResponse.model_validate(data)


def test_public_projection_excludes_internal_purchase_text_and_state(evaluation_case):
    case = evaluation_case(profile=True)
    encoded = case.response.model_dump_json()
    assert case.request.evaluation.analysis.user_request.text not in encoded
    assert not {"evaluation", "execution", "order", "policy_input", "observations"} & set(type(case.response).model_fields)
    for forbidden in ('"retained_samples"', '"raw_features"', '"user_request"', '"payload"', '"telemetry"'):
        assert forbidden not in encoded


def test_policy_failure_retains_computed_features_without_invented_total(evaluation_case):
    case = evaluation_case()
    data = case.response.model_dump()
    data["stages"][-1].update(status="FAILED", failure_code="CALCULATION_FAILED")
    data.update(outcome=dict(status="FAILED", risk_level="UNKNOWN", recommendation=None, review_actions=["RETRY_EVALUATION"]),
                groups=[], behavior_score=None, score_coverage="UNAVAILABLE", reasons=[dict(code="EVALUATION_FAILED")])
    response = ProviderEvaluationResponse.model_validate(data).validate_for_request(case.request)
    assert all(feature.score is not None for feature in response.features)
    assert response.behavior_score is None and response.outcome.risk_level == "UNKNOWN"
