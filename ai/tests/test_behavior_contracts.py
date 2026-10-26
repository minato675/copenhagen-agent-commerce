"""Supplied behavior evidence must retain identity, uncertainty and provenance."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.behavior import (
    AnomalyScore, BehaviorEvaluationRequest, BehaviorEvaluationResult, FeatureScoreResult,
    ScoringBasis,
)
from copenhagen_fds.contracts.results import SiftScore


@pytest.fixture
def purchase():
    return {
        "intent": {"purchase_request_id": "purchase-1", "source": "user_direct", "version": "intent-v1"},
        "context": {"evaluation_id": "evaluation-1", "user_id": "user-1", "purchase_request_id": "purchase-1"},
        "order": {
            "purchase_request_id": "purchase-1",
            "reference": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
            "orders": [{
                "order_id": "order-1", "merchant_id": "merchant-1",
                "items": [{"item_id": "item-1", "name": "합성 상품", "quantity": 1,
                           "unit_price": {"amount_minor": 10000, "currency": "KRW"},
                           "line_total": {"amount_minor": 10000, "currency": "KRW"}}],
                "total": {"amount_minor": 10000, "currency": "KRW"},
            }],
            "total": {"amount_minor": 10000, "currency": "KRW"},
        },
    }


@pytest.fixture
def profile():
    return {
        "reference": {"profile_id": "profile-1", "version": "v1", "scope": "POPULATION", "cohort_id": "cohort-1"},
        "origin": "SYNTHETIC", "source_reference": "fixture-dataset-v1",
        "feature_schema_version": "features-v1", "currency": "KRW",
        "as_of": "2026-09-09T09:59:00+09:00", "generated_at": "2026-09-09T09:59:30+09:00",
        "valid_until": "2026-09-09T11:00:00+09:00",
        "observations": [{"feature_id": "amount", "valid_sample_count": 10,
                          "observed_from": "2026-08-01T00:00:00+09:00",
                          "last_observed_at": "2026-09-08T22:00:00+09:00"}],
    }


@pytest.fixture
def feature():
    return {
        "feature_id": "amount", "status": "SCORED",
        "score": {"value": 0.0, "unit": "BEHAVIOR_ANOMALY_0_100"},
        "bases": [{"kind": "INITIAL_POLICY", "policy_version": "cold-v1"}],
        "reasons": ["WITHIN_REFERENCE"],
    }


@pytest.fixture
def behavior_payload(result_metadata, feature):
    result_metadata["sources"] = ["FDS"]
    return {
        "status": "SUCCESS", "metadata": result_metadata, "reasons": [],
        "feature_schema_version": "features-v1", "scoring_version": "scoring-v1",
        "requested_features": ["amount"],
        "coverage": "COMPLETE", "features": [feature],
    }


def request_for(purchase, profiles=()):
    return BehaviorEvaluationRequest.model_validate({
        "request": purchase, "profiles": list(profiles),
        "evaluated_at": "2026-09-09T10:00:00+09:00", "feature_schema_version": "features-v1",
        "requested_features": ["amount"],
    })


def use_profile(payload, profile):
    payload["profiles"] = [deepcopy(profile)]
    payload["features"][0]["bases"] = [{"kind": "POPULATION_PROFILE", "profile": deepcopy(profile["reference"])}]
    return payload


def test_cold_without_profiles_keeps_zero_as_a_real_score(behavior_payload, purchase):
    request = request_for(purchase)
    result = BehaviorEvaluationResult.model_validate(behavior_payload).validate_for_request(request)
    assert result.features[0].score.value == 0
    assert not result.profiles
    for model in (request, result):
        restored = type(model).model_validate_json(model.model_dump_json())
        assert restored == model
        assert type(model).model_validate(model.model_dump(mode="json")) == model
    assert result.features[0].bases[0].policy_version == "cold-v1"


@pytest.mark.parametrize("value", [-1, 100.1, float("inf"), float("-inf"), float("nan"), True, "50"])
def test_anomaly_score_rejects_invalid_values(value):
    with pytest.raises(ValidationError):
        AnomalyScore.model_validate({"value": value, "unit": "BEHAVIOR_ANOMALY_0_100"})


def test_score_units_cannot_be_silently_relabelled():
    with pytest.raises(ValidationError):
        AnomalyScore.model_validate(SiftScore(value=0.2, unit="SIFT_PAYMENT_ABUSE_0_1").model_dump())
    with pytest.raises(ValidationError):
        SiftScore.model_validate(AnomalyScore(value=100, unit="BEHAVIOR_ANOMALY_0_100").model_dump())


@pytest.mark.parametrize("status,reason", [
    ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"), ("NOT_COLLECTED", "MISSING_INPUT"),
    ("FAILED", "CALCULATION_FAILED"), ("NOT_RUN", "NOT_EXECUTED"),
    ("NOT_APPLICABLE", "NOT_APPLICABLE"),
])
def test_unavailable_features_never_accept_a_default_zero(status, reason):
    payload = {"feature_id": "amount", "status": status, "reasons": [reason]}
    model = FeatureScoreResult.model_validate(payload)
    assert model.score is None
    assert FeatureScoreResult.model_validate_json(model.model_dump_json()) == model
    with pytest.raises(ValidationError):
        FeatureScoreResult.model_validate({**payload, "score": {"value": 0, "unit": "BEHAVIOR_ANOMALY_0_100"}})


def test_inapplicable_feature_cannot_claim_an_applied_basis():
    with pytest.raises(ValidationError):
        FeatureScoreResult.model_validate({
            "feature_id": "attempt_count_10m", "status": "NOT_APPLICABLE",
            "reasons": ["NOT_APPLICABLE"],
            "bases": [{"kind": "INITIAL_POLICY", "policy_version": "v1"}],
        })


@pytest.mark.parametrize("change", [
    {"score": None}, {"bases": []}, {"reasons": []}, {"reasons": ["MISSING_INPUT"]},
    {"reasons": ["WITHIN_REFERENCE", "PATTERN_DEVIATION"]}, {"fraud_probability": 0.0},
])
def test_scored_feature_requires_consistent_evidence(feature, change):
    with pytest.raises(ValidationError):
        FeatureScoreResult.model_validate({**feature, **change})


@pytest.mark.parametrize("basis", [
    {"kind": "INITIAL_POLICY"},
    {"kind": "INITIAL_POLICY", "policy_version": "v1", "profile": {
        "profile_id": "p", "version": "1", "scope": "PERSONAL", "user_id": "u"}},
    {"kind": "POPULATION_PROFILE"},
    {"kind": "PERSONAL_PROFILE", "profile": {
        "profile_id": "p", "version": "1", "scope": "POPULATION", "cohort_id": "c"}},
])
def test_scoring_basis_requires_matching_identity_and_version(basis):
    with pytest.raises(ValidationError):
        ScoringBasis.model_validate(basis)


@pytest.mark.parametrize("include_score", [False, True])
def test_data_coverage_is_independent_from_successful_execution(behavior_payload, include_score):
    missing = {"feature_id": "hour", "status": "NOT_COLLECTED", "reasons": ["MISSING_INPUT"]}
    behavior_payload["features"] = [*behavior_payload["features"], missing] if include_score else [missing]
    behavior_payload["requested_features"] = ["amount", "hour"] if include_score else ["hour"]
    behavior_payload["coverage"] = "PARTIAL" if include_score else "UNAVAILABLE"
    behavior_payload["metadata"]["valid_until"] = None
    behavior_payload["reasons"] = [{"code": "INSUFFICIENT_DATA", "source": "FDS"}]
    model = BehaviorEvaluationResult.model_validate(behavior_payload)
    assert model.status == "SUCCESS"
    assert model.features[-1].score is None
    assert BehaviorEvaluationResult.model_validate_json(model.model_dump_json()) == model
    for change in ({"coverage": "COMPLETE"}, {"reasons": []}):
        with pytest.raises(ValidationError):
            BehaviorEvaluationResult.model_validate({**behavior_payload, **change})


def test_failed_execution_preserves_successful_feature_evidence(behavior_payload):
    behavior_payload.update(status="FAILED", coverage="PARTIAL", reasons=[{"code": "EVALUATION_FAILED", "source": "FDS"}])
    behavior_payload["metadata"]["valid_until"] = None
    behavior_payload["features"].append({"feature_id": "hour", "status": "FAILED", "reasons": ["CALCULATION_FAILED"]})
    behavior_payload["requested_features"] = ["amount", "hour"]
    result = BehaviorEvaluationResult.model_validate(behavior_payload)
    assert result.features[0].score.value == 0
    assert result.features[1].score is None
    assert BehaviorEvaluationResult.model_validate_json(result.model_dump_json()) == result
    with pytest.raises(ValidationError):
        BehaviorEvaluationResult.model_validate({**behavior_payload, "status": "SUCCESS", "reasons": []})


def test_not_run_has_no_applied_score_or_version(behavior_payload, purchase):
    behavior_payload.update(status="NOT_RUN", coverage="UNAVAILABLE", scoring_version=None,
                            reasons=[{"code": "NOT_EXECUTED", "source": "FDS"}], features=[])
    behavior_payload["metadata"].update(evaluated_at=None, valid_until=None)
    result = BehaviorEvaluationResult.model_validate(behavior_payload).validate_for_request(request_for(purchase))
    assert not result.features
    with pytest.raises(ValidationError):
        BehaviorEvaluationResult.model_validate({**behavior_payload, "scoring_version": "invented-version"})


def test_profile_result_binds_the_exact_snapshot(behavior_payload, profile, purchase):
    request = request_for(purchase, [profile])
    result = BehaviorEvaluationResult.model_validate(use_profile(behavior_payload, profile))
    assert result.validate_for_request(request) is result
    assert result.profiles[0].origin == "SYNTHETIC"
    assert BehaviorEvaluationResult.model_validate_json(result.model_dump_json()).validate_for_request(request) == result
    # Reusing a version label does not make different profile contents equivalent.
    behavior_payload["profiles"][0]["observations"][0]["valid_sample_count"] = 11
    altered = BehaviorEvaluationResult.model_validate(behavior_payload)
    with pytest.raises(ValueError, match="exact supplied"):
        altered.validate_for_request(request)


@pytest.mark.parametrize("mutation", ["version", "absent", "foreign_user", "future", "expired", "unknown_feature", "zero_samples", "duplicate"])
def test_profile_basis_rejects_incompatible_evidence(behavior_payload, profile, mutation):
    payload = use_profile(behavior_payload, profile)
    stored = payload["profiles"][0]
    if mutation == "version":
        payload["features"][0]["bases"][0]["profile"]["version"] = "wrong"
    elif mutation == "absent":
        payload["profiles"] = []
    elif mutation == "foreign_user":
        reference = {"profile_id": "profile-1", "version": "v1", "scope": "PERSONAL", "user_id": "another-user"}
        stored["reference"] = reference
        payload["features"][0]["bases"] = [{"kind": "PERSONAL_PROFILE", "profile": reference}]
    elif mutation == "future":
        stored["generated_at"] = "2026-09-09T10:00:01+09:00"
    elif mutation == "expired":
        stored["valid_until"] = "2026-09-09T10:00:00+09:00"
    elif mutation == "unknown_feature":
        stored["observations"][0]["feature_id"] = "other"
    elif mutation == "zero_samples":
        stored["observations"][0].update(valid_sample_count=0, observed_from=None, last_observed_at=None)
    else:
        payload["profiles"].append(deepcopy(stored))
    with pytest.raises((ValidationError, ValueError)):
        BehaviorEvaluationResult.model_validate(payload)


def test_result_validity_cannot_outlive_profile(behavior_payload, profile):
    profile["valid_until"] = "2026-09-09T10:05:00+09:00"
    with pytest.raises(ValidationError, match="outlive"):
        BehaviorEvaluationResult.model_validate(use_profile(behavior_payload, profile))


@pytest.mark.parametrize("field,value", [
    ("evaluation_id", "different-evaluation"), ("user_id", "different-user"),
    ("purchase_request_id", "different-purchase"), ("intent_version", "different-intent"),
])
def test_result_rejects_foreign_target(behavior_payload, purchase, field, value):
    behavior_payload["metadata"]["target"][field] = value
    result = BehaviorEvaluationResult.model_validate(behavior_payload)
    with pytest.raises(ValueError, match="target"):
        result.validate_for_request(request_for(purchase))


def test_request_binding_checks_order_version_time_and_feature_schema(behavior_payload, purchase):
    request = request_for(purchase)
    for mutated in ("order", "time", "schema"):
        data = deepcopy(behavior_payload)
        if mutated == "order":
            data["metadata"]["target"]["order"]["version"] = "different"
        elif mutated == "time":
            data["metadata"]["evaluated_at"] = "2026-09-09T10:00:01+09:00"
        else:
            data["feature_schema_version"] = "different"
        with pytest.raises(ValueError):
            BehaviorEvaluationResult.model_validate(data).validate_for_request(request)


def test_request_rejects_foreign_currency_and_schema(profile, purchase):
    for name, value in (("currency", "USD"), ("feature_schema_version", "different")):
        with pytest.raises(ValueError):
            request_for(purchase, [{**profile, name: value}])


def test_duplicate_features_and_unused_profiles_are_rejected(behavior_payload, profile):
    with pytest.raises(ValidationError):
        BehaviorEvaluationResult.model_validate({**behavior_payload, "features": behavior_payload["features"] * 2})
    with pytest.raises(ValidationError):
        BehaviorEvaluationResult.model_validate({**behavior_payload, "profiles": [profile]})


def test_missing_requested_feature_cannot_look_complete(behavior_payload, purchase):
    behavior_payload["requested_features"].append("hour")
    with pytest.raises(ValidationError):
        BehaviorEvaluationResult.model_validate(behavior_payload)
    with pytest.raises(ValidationError):
        BehaviorEvaluationResult.model_validate({**behavior_payload, "coverage": "PARTIAL"})
    request = request_for(purchase).model_dump(mode="json")
    request["requested_features"] = ["amount", "hour"]
    behavior_payload["requested_features"] = ["amount"]
    with pytest.raises(ValueError, match="feature set"):
        BehaviorEvaluationResult.model_validate(behavior_payload).validate_for_request(
            BehaviorEvaluationRequest.model_validate(request)
        )


def test_not_run_feature_cannot_claim_used_basis(feature):
    feature.update(status="NOT_RUN", score=None, reasons=["NOT_EXECUTED"])
    with pytest.raises(ValidationError, match="applied scoring basis"):
        FeatureScoreResult.model_validate(feature)


def test_partial_failure_coverage_uses_requested_features(behavior_payload):
    behavior_payload.update(status="FAILED", coverage="PARTIAL", requested_features=["amount", "hour"],
                            reasons=[{"code": "EVALUATION_FAILED", "source": "FDS"}])
    behavior_payload["metadata"]["valid_until"] = None
    result = BehaviorEvaluationResult.model_validate(behavior_payload)
    assert result.coverage == "PARTIAL"
    assert len(result.features) == 1


def test_public_contract_exports():
    from copenhagen_fds import contracts

    for model in (AnomalyScore, BehaviorEvaluationRequest, BehaviorEvaluationResult, FeatureScoreResult, ScoringBasis):
        assert getattr(contracts, model.__name__) is model
