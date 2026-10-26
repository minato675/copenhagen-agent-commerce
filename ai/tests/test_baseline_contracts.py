"""Reference contracts constrain raw parameters and preserve declared origin."""

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.baselines import FeatureReference, InitialPolicy, SelectedFeatureBasis


@pytest.fixture
def parameter():
    return {"feature_id": "amount_minor", "unit": "MINOR_UNIT", "method": "UPPER_DISTANCE", "center": 0.0, "scale": 1.0}


@pytest.fixture
def policy_payload(parameter):
    return {
        "policy_id": "initial-policy", "version": "v1", "source_reference": "synthetic-policy-v1",
        "currency": "KRW", "feature_schema_version": "cold-features-v1",
        "generated_at": "2026-09-09T09:00:00+09:00", "available_at": "2026-09-09T09:30:00+09:00",
        "valid_from": "2026-09-09T10:00:00+09:00", "valid_until": "2026-09-09T11:00:00+09:00",
        "parameters": [parameter],
    }


@pytest.mark.parametrize("field,value", [
    ("center", -1), ("center", float("nan")), ("center", float("inf")), ("center", True), ("center", "0"),
    ("scale", 0), ("scale", -1), ("scale", float("nan")), ("scale", float("inf")), ("scale", True), ("scale", "1"),
    ("window_seconds", 0), ("period", True), ("method", "UPPER"),
])
def test_parameter_rejects_nonfinite_or_coerced_values_without_epsilon(parameter, field, value):
    parameter[field] = value
    with pytest.raises(ValidationError):
        FeatureReference.model_validate(parameter)


def test_zero_center_and_positive_scale_are_preserved_without_replacement(parameter):
    reference = FeatureReference.model_validate({**parameter, "scale": 1e-12})
    assert reference.center == 0
    assert reference.scale == 1e-12
    assert FeatureReference.model_validate_json(reference.model_dump_json()) == reference


@pytest.mark.parametrize("field,value", [
    ("available_at", "2026-09-09T08:59:59+09:00"),
    ("valid_until", "2026-09-09T10:00:00+09:00"),
    ("valid_until", "2026-09-09T09:00:00+09:00"),
    ("generated_at", "2026-09-09T09:00:00"), ("available_at", 1788913800),
    ("origin", "OBSERVED"), ("origin", "SYNTHETIC"), ("validation_status", "VALIDATED"),
    ("policy_id", ""), ("version", " "), ("currency", "krw"), ("parameters", []),
])
def test_policy_rejects_invalid_validity_and_claimed_observation(policy_payload, field, value):
    policy_payload[field] = value
    with pytest.raises(ValidationError):
        InitialPolicy.model_validate(policy_payload)


def test_policy_is_unvalidated_and_immutable_and_json_round_trips(policy_payload):
    policy = InitialPolicy.model_validate(policy_payload)
    assert policy.origin == "INITIAL_POLICY"
    assert policy.validation_status == "UNVALIDATED"
    assert InitialPolicy.model_validate_json(policy.model_dump_json()) == policy
    with pytest.raises(ValidationError, match="frozen"):
        policy.version = "v2"


@pytest.mark.parametrize("change", [{"parameter": None}, {"source_reference": None}, {"generated_at": None}, {"policy_id": None}, {"origin": "SYNTHETIC"}, {"validation_status": None}])
def test_selected_policy_requires_complete_applied_provenance(parameter, policy_payload, change):
    payload = {
        "feature_id": "amount_minor", "status": "SELECTED", "parameter": parameter,
        "basis": {"kind": "INITIAL_POLICY", "policy_version": "v1"},
        "origin": "INITIAL_POLICY", "source_reference": "synthetic-policy-v1",
        "validation_status": "UNVALIDATED", "policy_id": "initial-policy",
        **{name: policy_payload[name] for name in ("generated_at", "available_at", "valid_from", "valid_until")},
        **change,
    }
    with pytest.raises(ValidationError):
        SelectedFeatureBasis.model_validate(payload)


def test_unavailable_reference_requires_diagnostics_and_cannot_keep_parameters(parameter):
    payload = {"feature_id": "amount_minor", "status": "UNAVAILABLE"}
    with pytest.raises(ValidationError):
        SelectedFeatureBasis.model_validate(payload)
    payload["issues"] = [{"source": "POPULATION", "code": "MISSING"}]
    reference = SelectedFeatureBasis.model_validate(payload)
    assert reference.parameter is reference.basis is reference.origin is None
    with pytest.raises(ValidationError):
        SelectedFeatureBasis.model_validate({**payload, "parameter": parameter})


def test_unknown_keys_are_rejected_without_echoing_private_input(parameter):
    parameter["unsupported_field"] = "synthetic-private-reference"
    with pytest.raises(ValidationError) as caught:
        FeatureReference.model_validate(parameter)
    assert "synthetic-private-reference" not in str(caught.value)
