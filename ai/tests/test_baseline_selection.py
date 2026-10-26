"""Cold-start baselines retain provenance and reject incompatible evidence."""

from copy import deepcopy
from datetime import datetime, timedelta
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.contracts.baselines import (
    BaselineSelection, BaselineSelectionRequest, InitialPolicy,
    PopulationBaseline,
)
from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest
from copenhagen_fds.contracts.features import FEATURE_SCHEMA_VERSION, FEATURE_SPECS


NOW = datetime.fromisoformat("2026-09-09T10:00:00+09:00")


@pytest.fixture
def evaluation_payload():
    money = {"amount_minor": 10000, "currency": "KRW"}
    return {
        "request": {
            "intent": {"purchase_request_id": "purchase-1", "source": "user_direct", "version": "intent-v1"},
            "context": {"evaluation_id": "evaluation-1", "user_id": "user-1", "purchase_request_id": "purchase-1"},
            "order": {
                "purchase_request_id": "purchase-1",
                "reference": {"snapshot_id": "checkout-1", "version": "2", "snapshot_hash": "a" * 64},
                "orders": [{
                    "order_id": "order-1", "merchant_id": "merchant-1",
                    "items": [{"item_id": "item-1", "name": "합성 상품", "quantity": 1,
                               "unit_price": deepcopy(money), "line_total": deepcopy(money)}],
                    "total": deepcopy(money),
                }],
                "total": deepcopy(money),
            },
        },
        "evaluated_at": NOW.isoformat(),
        "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "requested_features": list(FEATURE_SPECS),
    }


@pytest.fixture
def population_profile():
    return {
        "reference": {"profile_id": "population-1", "version": "population-v1",
                      "scope": "POPULATION", "cohort_id": "cohort-1"},
        "origin": "SYNTHETIC", "source_reference": "synthetic-source-v1",
        "feature_schema_version": FEATURE_SCHEMA_VERSION, "currency": "KRW",
        "as_of": (NOW - timedelta(minutes=1)).isoformat(),
        "generated_at": (NOW - timedelta(seconds=30)).isoformat(),
        "valid_until": (NOW + timedelta(hours=1)).isoformat(),
        "observations": [
            {"feature_id": name, "valid_sample_count": 10,
             "observed_from": (NOW - timedelta(days=30)).isoformat(),
             "last_observed_at": (NOW - timedelta(minutes=2)).isoformat()}
            for name in FEATURE_SPECS
        ],
    }


def parameters():
    return [
        {"feature_id": name, "unit": unit, "window_seconds": window,
         "method": "CIRCULAR_DISTANCE" if name in {"kst_hour", "kst_day_of_week"} else "UPPER_DISTANCE",
         "center": 0.0, "scale": 1.0,
         "period": {"kst_hour": 24, "kst_day_of_week": 7}.get(name)}
        for name, (unit, window) in FEATURE_SPECS.items()
    ]


@pytest.fixture
def population(population_profile):
    return {"profile": population_profile, "available_at": NOW.isoformat(), "parameters": parameters()}


@pytest.fixture
def policy():
    return {
        "policy_id": "initial-policy", "version": "initial-v1", "source_reference": "synthetic-policy-spec-v1",
        "currency": "KRW", "feature_schema_version": FEATURE_SCHEMA_VERSION,
        "generated_at": (NOW - timedelta(days=1)).isoformat(),
        "available_at": NOW.isoformat(), "valid_from": NOW.isoformat(),
        "valid_until": (NOW + timedelta(hours=2)).isoformat(), "parameters": parameters(),
    }


def request_for(evaluation_payload, population):
    return BaselineSelectionRequest.model_validate({
        "evaluation": evaluation_payload,
        "population_reference": population["profile"]["reference"] if population is not None else None,
        "policy_id": "initial-policy",
        "policy_version": "initial-v1",
    })


def issues(feature):
    return [(issue.source, issue.code) for issue in feature.issues]


@pytest.mark.parametrize("origin", ["OBSERVED", "SYNTHETIC"])
@pytest.mark.parametrize("encoding", ["dict", "json", "model"])
def test_population_first_retains_exact_profile_and_unvalidated_provenance(evaluation_payload, population, policy, origin, encoding):
    population["profile"]["origin"] = origin
    request = request_for(evaluation_payload, population)
    candidate = population if encoding == "dict" else json.dumps(population) if encoding == "json" else PopulationBaseline.model_validate(population)
    result = select_cold_baselines(request, population=candidate, policy=policy)
    assert result.validate_for_request(request) is result
    assert result.selection_version == "cold-basis-selection-v1"
    assert result.target == request.evaluation.target
    assert len(result.profiles) == 1
    for feature in result.features:
        assert feature.status == "SELECTED"
        assert feature.basis.kind == "POPULATION_PROFILE"
        assert feature.basis.profile == request.population_reference
        assert feature.origin == origin
        assert feature.source_reference == population["profile"]["source_reference"]
        assert feature.validation_status == "UNVALIDATED"
        assert feature.valid_until == result.profiles[0].valid_until
        assert not feature.issues
        assert "score" not in feature.model_dump()
    assert BaselineSelection.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("encoding", ["dict", "json", "model"])
def test_missing_population_uses_pinned_policy_without_fabricating_observations(evaluation_payload, policy, encoding):
    request = request_for(evaluation_payload, None)
    candidate = policy if encoding == "dict" else json.dumps(policy) if encoding == "json" else InitialPolicy.model_validate(policy)
    result = select_cold_baselines(request, policy=candidate)
    assert result.profiles == ()
    for feature in result.features:
        assert feature.status == "SELECTED"
        assert feature.basis.policy_version == "initial-v1"
        assert feature.policy_id == "initial-policy"
        assert feature.origin == "INITIAL_POLICY"
        assert feature.source_reference == policy["source_reference"]
        assert feature.validation_status == "UNVALIDATED"
        assert feature.generated_at == datetime.fromisoformat(policy["generated_at"])
        assert feature.available_at == NOW
        assert feature.valid_from == NOW
        assert issues(feature) == [("POPULATION", "MISSING")]


def test_missing_both_sources_is_unavailable_with_two_explicit_diagnostics(evaluation_payload):
    result = select_cold_baselines(request_for(evaluation_payload, None))
    assert result.profiles == ()
    for feature in result.features:
        assert feature.status == "UNAVAILABLE"
        assert feature.parameter is feature.basis is feature.origin is feature.valid_until is None
        assert issues(feature) == [("POPULATION", "MISSING"), ("INITIAL_POLICY", "MISSING")]


def test_valid_population_missing_one_feature_uses_policy_only_for_that_feature(evaluation_payload, population, policy):
    population["parameters"] = population["parameters"][1:]
    result = select_cold_baselines(request_for(evaluation_payload, population), population=population, policy=policy)
    assert result.features[0].feature_id == "amount_minor"
    assert result.features[0].basis.kind == "INITIAL_POLICY"
    assert issues(result.features[0]) == [("POPULATION", "FEATURE_MISSING")]
    assert all(feature.basis.kind == "POPULATION_PROFILE" for feature in result.features[1:])


def test_valid_policy_missing_feature_keeps_unavailable_instead_of_zero(evaluation_payload, policy):
    policy["parameters"] = policy["parameters"][:1]
    result = select_cold_baselines(request_for(evaluation_payload, None), policy=policy)
    assert result.features[0].status == "SELECTED"
    for feature in result.features[1:]:
        assert feature.status == "UNAVAILABLE" and feature.parameter is None
        assert issues(feature) == [("POPULATION", "MISSING"), ("INITIAL_POLICY", "FEATURE_MISSING")]


@pytest.mark.parametrize("problem,expected", [
    ("expired", "EXPIRED"), ("future_as_of", "FUTURE"), ("future_generation", "FUTURE"),
    ("future_availability", "FUTURE"), ("wrong_schema", "VERSION_MISMATCH"),
    ("wrong_version", "VERSION_MISMATCH"), ("wrong_identity", "REFERENCE_MISMATCH"),
    ("wrong_cohort", "REFERENCE_MISMATCH"), ("personal_scope", "REFERENCE_MISMATCH"),
    ("wrong_currency", "CURRENCY_MISMATCH"), ("wrong_unit", "UNIT_MISMATCH"),
    ("wrong_window", "UNIT_MISMATCH"), ("unsupported_feature", "UNSUPPORTED_FEATURE"),
    ("duplicate_feature", "INVALID_CONFIG"), ("unavailable_before_generation", "INVALID_CONFIG"),
    ("unsupported_format", "VERSION_MISMATCH"),
])
def test_invalid_population_blocks_silent_policy_fallback(evaluation_payload, population, policy, problem, expected):
    request = request_for(evaluation_payload, population)
    profile = population["profile"]
    future = (NOW + timedelta(seconds=1)).isoformat()
    if problem == "expired":
        profile["valid_until"] = NOW.isoformat()
    elif problem == "future_as_of":
        profile.update(as_of=future, generated_at=future)
        population["available_at"] = future
    elif problem == "future_generation":
        profile["generated_at"] = future
        population["available_at"] = future
    elif problem == "future_availability":
        population["available_at"] = future
    elif problem == "wrong_schema":
        profile["feature_schema_version"] = "unsupported-schema"
    elif problem == "wrong_version":
        profile["reference"]["version"] = "population-v2"
    elif problem == "wrong_identity":
        profile["reference"]["profile_id"] = "another-profile"
    elif problem == "wrong_cohort":
        profile["reference"]["cohort_id"] = "another-cohort"
    elif problem == "personal_scope":
        profile["reference"].update(scope="PERSONAL", cohort_id=None, user_id="user-1")
    elif problem == "wrong_currency":
        profile["currency"] = "USD"
    elif problem == "wrong_unit":
        population["parameters"][0]["unit"] = "PURCHASE_COUNT"
    elif problem == "wrong_window":
        population["parameters"][1]["window_seconds"] = 3600
    elif problem == "unsupported_feature":
        population["parameters"][0]["feature_id"] = "unavailable-new-feature"
    elif problem == "duplicate_feature":
        population["parameters"].append(deepcopy(population["parameters"][0]))
    elif problem == "unavailable_before_generation":
        population["available_at"] = (NOW - timedelta(minutes=1)).isoformat()
    else:
        population["format_version"] = "cold-reference-v2"
    result = select_cold_baselines(request, population=population, policy=policy)
    assert result.profiles == ()
    assert all(feature.status == "UNAVAILABLE" for feature in result.features)
    assert all(issues(feature) == [("POPULATION", expected)] for feature in result.features)


@pytest.mark.parametrize("problem,expected", [
    ("expired", "EXPIRED"), ("future_generation", "FUTURE"), ("future_availability", "FUTURE"),
    ("future_valid_from", "FUTURE"), ("wrong_schema", "VERSION_MISMATCH"),
    ("wrong_version", "VERSION_MISMATCH"), ("wrong_identity", "REFERENCE_MISMATCH"),
    ("wrong_currency", "CURRENCY_MISMATCH"),
    ("wrong_unit", "UNIT_MISMATCH"), ("unsupported_feature", "UNSUPPORTED_FEATURE"),
    ("unavailable_before_generation", "INVALID_CONFIG"), ("duplicate_feature", "INVALID_CONFIG"),
])
def test_invalid_policy_diagnostics_remain_distinct(evaluation_payload, policy, problem, expected):
    request = request_for(evaluation_payload, None)
    future = (NOW + timedelta(seconds=1)).isoformat()
    if problem == "expired":
        policy.update(valid_from=(NOW - timedelta(hours=1)).isoformat(), valid_until=NOW.isoformat())
    elif problem == "future_generation":
        policy.update(generated_at=future, available_at=future)
    elif problem == "future_availability":
        policy["available_at"] = future
    elif problem == "future_valid_from":
        policy["valid_from"] = future
    elif problem == "wrong_schema":
        policy["feature_schema_version"] = "unsupported-schema"
    elif problem == "wrong_version":
        policy["version"] = "initial-v2"
    elif problem == "wrong_identity":
        policy["policy_id"] = "other-policy"
    elif problem == "wrong_currency":
        policy["currency"] = "USD"
    elif problem == "wrong_unit":
        policy["parameters"][0]["unit"] = "UNSUPPORTED_UNIT"
    elif problem == "unsupported_feature":
        policy["parameters"][0]["feature_id"] = "unsupported_feature"
    elif problem == "unavailable_before_generation":
        policy["available_at"] = (NOW - timedelta(days=2)).isoformat()
    else:
        policy["parameters"].append(deepcopy(policy["parameters"][0]))
    result = select_cold_baselines(request, policy=policy)
    assert all(feature.status == "UNAVAILABLE" for feature in result.features)
    assert all(issues(feature) == [("POPULATION", "MISSING"), ("INITIAL_POLICY", expected)] for feature in result.features)


@pytest.mark.parametrize("source", ["population", "policy"])
@pytest.mark.parametrize("candidate,expected", [("{", "INVALID_FORMAT"), ('{"x":1,"x":2}', "INVALID_FORMAT"), ("[]", "INVALID_CONFIG"), ("null", "INVALID_CONFIG"), ({}, "INVALID_CONFIG")])
def test_malformed_candidate_is_not_confused_with_missing(evaluation_payload, population, source, candidate, expected):
    request = request_for(evaluation_payload, population)
    result = select_cold_baselines(request, **{source: candidate})
    expected_issues = [("POPULATION", expected)] if source == "population" else [("POPULATION", "MISSING"), ("INITIAL_POLICY", expected)]
    assert all(issues(feature) == expected_issues for feature in result.features)


@pytest.mark.parametrize("source", ["population", "policy"])
def test_nested_duplicate_json_is_rejected_before_validation(evaluation_payload, population, policy, source):
    candidate = population if source == "population" else policy
    encoded = json.dumps(candidate).replace('"center": 0.0', '"center": 0.0, "center": 100.0', 1)
    result = select_cold_baselines(request_for(evaluation_payload, population), **{source: encoded})
    assert all(feature.status == "UNAVAILABLE" for feature in result.features)
    assert all(feature.issues[-1].code == "INVALID_FORMAT" for feature in result.features)


@pytest.mark.parametrize("feature_id,change", [
    ("kst_hour", {"method": "UPPER_DISTANCE"}), ("kst_hour", {"period": None}),
    ("kst_hour", {"period": 7}), ("kst_hour", {"center": 24}),
    ("kst_day_of_week", {"method": "ABSOLUTE_DISTANCE"}),
    ("kst_day_of_week", {"period": 24}), ("kst_day_of_week", {"center": 7}),
    ("amount_minor", {"method": "CIRCULAR_DISTANCE", "period": 24}),
    ("purchase_count_10m", {"period": 7}),
])
def test_circular_time_reference_cannot_be_relabelled_as_numeric(evaluation_payload, policy, feature_id, change):
    next(parameter for parameter in policy["parameters"] if parameter["feature_id"] == feature_id).update(change)
    result = select_cold_baselines(request_for(evaluation_payload, None), policy=policy)
    assert all(feature.status == "UNAVAILABLE" and feature.issues[-1].code == "INVALID_CONFIG" for feature in result.features)


@pytest.mark.parametrize("feature_id,center,period", [("kst_hour", 0, 24), ("kst_hour", 23, 24), ("kst_day_of_week", 0, 7), ("kst_day_of_week", 6, 7)])
def test_circular_reference_keeps_wraparound_parameters_for_later_scoring(evaluation_payload, policy, feature_id, center, period):
    evaluation_payload["requested_features"] = [feature_id]
    parameter = next(parameter for parameter in policy["parameters"] if parameter["feature_id"] == feature_id)
    parameter["center"] = center
    result = select_cold_baselines(request_for(evaluation_payload, None), policy=policy)
    assert (result.features[0].parameter.center, result.features[0].parameter.period) == (center, period)
    assert result.features[0].parameter.method == "CIRCULAR_DISTANCE"


@pytest.mark.parametrize("state", ["missing", "zero"])
def test_population_parameters_require_positive_observation_support(evaluation_payload, population, policy, state):
    request = request_for(evaluation_payload, population)
    if state == "missing":
        population["profile"]["observations"] = population["profile"]["observations"][1:]
    else:
        population["profile"]["observations"][0].update(valid_sample_count=0, observed_from=None, last_observed_at=None)
    result = select_cold_baselines(request, population=population, policy=policy)
    assert all(feature.status == "UNAVAILABLE" and issues(feature) == [("POPULATION", "INSUFFICIENT_SAMPLES")] for feature in result.features)


def test_source_version_cannot_be_inferred_when_no_source_is_pinned(evaluation_payload, population, policy):
    request = BaselineSelectionRequest(evaluation=BehaviorEvaluationRequest.model_validate(evaluation_payload), population_reference=None, policy_id=None, policy_version=None)
    result = select_cold_baselines(request, population=population, policy=policy)
    assert all(issues(feature) == [("POPULATION", "REFERENCE_MISMATCH")] for feature in result.features)
    result = select_cold_baselines(request, policy=policy)
    assert all(feature.issues[-1].code == "VERSION_MISMATCH" for feature in result.features)


def test_population_conflicting_with_supplied_snapshot_is_rejected(evaluation_payload, population, policy):
    evaluation_payload["profiles"] = [deepcopy(population["profile"])]
    request = request_for(evaluation_payload, population)
    population["profile"]["source_reference"] = "another-source-with-same-identity"
    result = select_cold_baselines(request, population=population, policy=policy)
    assert all(issues(feature) == [("POPULATION", "REFERENCE_MISMATCH")] for feature in result.features)


@pytest.mark.parametrize("field,value", [("origin", "OBSERVED"), ("source_reference", "other-source"), ("validation_status", "VALIDATED")])
def test_serialized_selection_cannot_claim_different_provenance(evaluation_payload, population, field, value):
    result = select_cold_baselines(request_for(evaluation_payload, population), population=population)
    payload = result.model_dump(mode="json")
    payload["features"][0][field] = value
    with pytest.raises(ValidationError):
        BaselineSelection.model_validate(payload)


def test_same_input_and_reordered_parameters_have_identical_canonical_output(evaluation_payload, population, policy):
    request = request_for(evaluation_payload, population)
    first = select_cold_baselines(request, population=population, policy=policy)
    same = select_cold_baselines(BaselineSelectionRequest.model_validate_json(request.model_dump_json()), population=json.dumps(population), policy=json.dumps(policy))
    assert first.model_dump_json() == same.model_dump_json()
    evaluation_payload["requested_features"].reverse()
    population["parameters"].reverse()
    policy["parameters"].reverse()
    reordered = select_cold_baselines(request_for(evaluation_payload, population), population=population, policy=policy)
    assert [feature.feature_id for feature in reordered.features] == list(FEATURE_SPECS)
    assert first.model_dump_json() == reordered.model_dump_json()


def test_invalid_input_values_are_not_echoed_in_diagnostics(evaluation_payload, population, policy):
    marker = "synthetic-private-error-value"
    population["parameters"][0]["center"] = marker
    result = select_cold_baselines(request_for(evaluation_payload, population), population=population, policy=policy)
    assert marker not in result.model_dump_json()
    assert all(issues(feature) == [("POPULATION", "INVALID_CONFIG")] for feature in result.features)


@pytest.mark.parametrize("field", ["population_reference", "policy_id", "policy_version"])
def test_source_configuration_requires_explicit_null_instead_of_omission(evaluation_payload, field):
    payload = {"evaluation": evaluation_payload, "population_reference": None, "policy_id": None, "policy_version": None}
    payload.pop(field)
    with pytest.raises(ValidationError):
        BaselineSelectionRequest.model_validate(payload)


@pytest.mark.parametrize("mutation", ["unit", "period", "method", "missing_observation", "zero_samples", "future_availability", "changed_generation", "changed_valid_from", "changed_expiry"])
def test_decoded_population_selection_revalidates_its_evidence(evaluation_payload, population, mutation):
    result = select_cold_baselines(request_for(evaluation_payload, population), population=population)
    payload = result.model_dump(mode="json")
    feature = payload["features"][0]
    profile = payload["profiles"][0]
    if mutation == "unit":
        feature["parameter"]["unit"] = "PURCHASE_COUNT"
    elif mutation == "period":
        next(item for item in payload["features"] if item["feature_id"] == "kst_hour")["parameter"]["period"] = 7
    elif mutation == "method":
        next(item for item in payload["features"] if item["feature_id"] == "kst_hour")["parameter"]["method"] = "UPPER_DISTANCE"
    elif mutation == "missing_observation":
        profile["observations"] = profile["observations"][1:]
    elif mutation == "zero_samples":
        profile["observations"][0].update(valid_sample_count=0, observed_from=None, last_observed_at=None)
    elif mutation == "future_availability":
        feature["available_at"] = (NOW + timedelta(seconds=1)).isoformat()
    elif mutation == "changed_generation":
        feature["generated_at"] = (NOW - timedelta(seconds=15)).isoformat()
    elif mutation == "changed_valid_from":
        feature["valid_from"] = (NOW - timedelta(seconds=15)).isoformat()
    else:
        feature["valid_until"] = (NOW + timedelta(hours=2)).isoformat()
    with pytest.raises(ValidationError):
        BaselineSelection.model_validate(payload)


@pytest.mark.parametrize("mutation", ["identity", "version", "future_availability", "observed_origin"])
def test_decoded_policy_selection_rechecks_pins_and_provenance(evaluation_payload, policy, mutation):
    request = request_for(evaluation_payload, None)
    payload = select_cold_baselines(request, policy=policy).model_dump(mode="json")
    feature = payload["features"][0]
    if mutation == "identity":
        feature["policy_id"] = "another-policy"
    elif mutation == "version":
        feature["basis"]["policy_version"] = "initial-v2"
    elif mutation == "future_availability":
        feature["available_at"] = (NOW + timedelta(seconds=1)).isoformat()
    else:
        feature["origin"] = "OBSERVED"
    with pytest.raises(ValueError):
        BaselineSelection.model_validate(payload).validate_for_request(request)


def test_valid_population_does_not_depend_on_an_unused_policy(evaluation_payload, population):
    request = request_for(evaluation_payload, population)
    result = select_cold_baselines(request, population=population, policy="{broken-unused-policy")
    assert all(feature.status == "SELECTED" and feature.basis.kind == "POPULATION_PROFILE" and not feature.issues for feature in result.features)


def test_expiry_boundary_is_exclusive_and_availability_is_inclusive(evaluation_payload, population, policy):
    request = request_for(evaluation_payload, population)
    population["profile"]["valid_until"] = (NOW + timedelta(microseconds=1)).isoformat()
    policy["valid_until"] = (NOW + timedelta(microseconds=1)).isoformat()
    assert all(feature.status == "SELECTED" for feature in select_cold_baselines(request, population=population).features)
    assert all(feature.status == "SELECTED" for feature in select_cold_baselines(request, policy=policy).features)


def test_cli_ready_input_produces_reproducible_selected_json(evaluation_payload, population, policy, tmp_path, capsys):
    from copenhagen_fds.baseline_cli import main

    request = request_for(evaluation_payload, population)
    path = tmp_path / "synthetic-input.json"
    path.write_text(json.dumps({"request": request.model_dump(mode="json"), "population": population, "policy": policy}), encoding="utf-8")
    original = path.read_bytes()
    assert main([str(path)]) == 0
    first = capsys.readouterr()
    assert first.err == ""
    result = BaselineSelection.model_validate_json(first.out).validate_for_request(request)
    assert all(feature.status == "SELECTED" for feature in result.features)
    assert main([str(path)]) == 0
    assert first.out == capsys.readouterr().out
    assert path.read_bytes() == original


def test_cli_invalid_expired_source_returns_unavailable_json_and_exit_one(evaluation_payload, population, policy, tmp_path, capsys):
    from copenhagen_fds.baseline_cli import main

    request = request_for(evaluation_payload, population)
    population["profile"]["valid_until"] = NOW.isoformat()
    path = tmp_path / "synthetic-expired.json"
    path.write_text(json.dumps({"request": request.model_dump(mode="json"), "population": population, "policy": policy}), encoding="utf-8")
    assert main([str(path)]) == 1
    captured = capsys.readouterr()
    assert captured.err == ""
    result = BaselineSelection.model_validate_json(captured.out)
    assert all(feature.status == "UNAVAILABLE" and issues(feature) == [("POPULATION", "EXPIRED")] for feature in result.features)


@pytest.mark.parametrize("mutation", ["wrong_envelope", "invalid_request", "duplicate_json", "missing_file"])
def test_cli_invalid_input_uses_fixed_error_without_private_values(evaluation_payload, policy, tmp_path, capsys, mutation):
    from copenhagen_fds.baseline_cli import main

    marker = "synthetic-private-input"
    request = request_for(evaluation_payload, None).model_dump(mode="json")
    payload = {"request": request, "population": None, "policy": policy}
    if mutation == "wrong_envelope":
        payload["unexpected"] = marker
    elif mutation == "invalid_request":
        request["evaluation"]["feature_schema_version"] = marker
    encoded = json.dumps(payload)
    if mutation == "duplicate_json":
        encoded = encoded.replace('"population": null', f'"population": null, "population": "{marker}"', 1)
    path = tmp_path / f"{marker}.json"
    if mutation != "missing_file":
        path.write_text(encoded, encoding="utf-8")
    with pytest.raises(SystemExit) as caught:
        main([str(path)])
    assert caught.value.code == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert captured.err == "Baseline input could not be read or failed request validation.\n"
    assert marker not in captured.err
