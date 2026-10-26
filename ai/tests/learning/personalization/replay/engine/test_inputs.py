from datetime import timedelta, timezone

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference


@pytest.mark.parametrize("profile,missing", [(False, False), (True, False), (True, True)])
def test_request_preserves_supplied_snapshots_through_json(engine_cases, profile, missing):
    request, row = engine_cases.inputs(profile=profile, missing=missing)
    restored = EngineEvaluationRequest.model_validate_json(request.model_dump_json())
    assert restored == request
    assert restored.feature_input == row.case.input
    assert restored.personal == row.input.personal
    assert EngineRequestReference.from_request(restored) == EngineRequestReference.from_request(request)
    if missing:
        assert restored.feature_input.history.purchases.status == "NOT_COLLECTED"


@pytest.mark.parametrize("change", ["user", "purchase", "order_content", "order_version", "intent_version", "intent_source"])
def test_analysis_cannot_replace_original_target_or_content(engine_cases, change):
    data = engine_cases.inputs()[0].model_dump()
    if change in {"user", "purchase"}:
        data["analysis"]["context"]["user_id" if change == "user" else "purchase_request_id"] = "another"
    elif change == "order_content":
        data["analysis"]["order"]["orders"][0]["items"][0]["name"] = "different product"
    elif change == "order_version":
        data["analysis"]["order"]["reference"]["version"] = "another-version"
    else:
        data["analysis"]["user_request"]["version" if change == "intent_version" else "source"] = (
            "another-version" if change == "intent_version" else "agent_summary"
        )
    with pytest.raises(ValueError):
        EngineEvaluationRequest.model_validate(data)


@pytest.mark.parametrize("change", ["schema", "trace_type", "required_policy", "prompt", "score", "readiness", "budget", "nan", "key"])
def test_unsupported_or_ambiguous_execution_configuration_is_rejected(engine_cases, change):
    data = engine_cases.inputs()[0].model_dump()
    if change == "schema":
        data["schema_version"] = "engine-evaluation-request-v999"
    elif change == "trace_type":
        data["trace_id"] = 123
    elif change == "required_policy":
        del data["initial_policy"]
    elif change == "prompt":
        data["execution"]["comparison_prompt_version"] = "unsupported"
    elif change == "score":
        data["personalization"]["features"][0]["score_version"] = "unsupported"
    elif change == "readiness":
        data["personalization"]["transition_policy_version"] = "unsupported"
    elif change == "budget":
        data["execution"]["provider_timeout_seconds"] = 11.0
    elif change == "nan":
        data["execution"]["total_timeout_seconds"] = float("nan")
    else:
        data["execution"]["gms_key"] = "private-fixture-value"
    with pytest.raises(ValidationError) as caught:
        EngineEvaluationRequest.model_validate(data)
    assert "private-fixture-value" not in str(caught.value)
    assert "합성 구매 요청" not in str(caught.value)


def test_explicit_absence_and_expired_profile_are_preserved_for_fallback(engine_cases):
    request, _ = engine_cases.inputs(profile=True)
    data = request.model_dump()
    data["initial_policy"] = None
    profile = data["personal"]["state"]["published"]["profile"]
    profile["valid_until"] = engine_cases.at - timedelta(seconds=1)
    restored = EngineEvaluationRequest.model_validate(data)
    assert restored.initial_policy is None
    assert restored.personal.state.published.profile.valid_until < restored.feature_input.evaluation.evaluated_at


@pytest.mark.parametrize("change", ["future_publication", "current_cutoff", "wrong_user", "future_policy", "population_pair"])
def test_unavailable_or_unrelated_sources_cannot_enter_behavior_snapshot(engine_cases, change):
    data = engine_cases.inputs(profile=True)[0].model_dump()
    profile = data["personal"]["state"]["published"]["profile"]
    if change == "future_publication":
        data["personal"]["available_at"] = engine_cases.at + timedelta(microseconds=1)
    elif change == "current_cutoff":
        profile["as_of"] = engine_cases.at
    elif change == "wrong_user":
        profile["reference"]["user_id"] = "another-user"
    elif change == "future_policy":
        data["initial_policy"]["available_at"] = engine_cases.at + timedelta(microseconds=1)
    else:
        data["population"] = data["personal"]
    with pytest.raises(ValueError):
        EngineEvaluationRequest.model_validate(data)


def test_references_bind_text_model_and_settings_even_without_version_change(engine_cases):
    request, _ = engine_cases.inputs()
    reference = EngineRequestReference.from_request(request)
    for section, field, value in [("analysis", "text", "새로운 구매 내용"),
                                  ("execution", "requested_model", "another-model"),
                                  ("execution", "total_timeout_seconds", 12.0)]:
        data = request.model_dump()
        if section == "analysis":
            data[section]["user_request"][field] = value
        else:
            data[section][field] = value
        changed = EngineRequestReference.from_request(EngineEvaluationRequest.model_validate(data))
        assert changed.target == reference.target
        assert changed.request_sha256 != reference.request_sha256
    data = request.model_dump()
    data["feature_input"]["evaluation"]["evaluated_at"] = engine_cases.at.astimezone(timezone.utc)
    assert EngineRequestReference.from_request(EngineEvaluationRequest.model_validate(data)) == reference
    assert "합성 구매 요청" not in repr(request)
    assert "합성 구매 요청" not in reference.model_dump_json()


def test_reference_revalidates_unchecked_nested_copy(engine_cases):
    request, _ = engine_cases.inputs()
    altered = request.execution.model_copy(update={"total_timeout_seconds": -1.0})
    with pytest.raises(ValueError):
        EngineRequestReference.from_request(request.model_copy(update={"execution": altered}))


def test_population_derivation_pins_an_available_publication(engine_cases, personalized):
    population = personalized.publication(scope="POPULATION")
    data = engine_cases.inputs()[0].model_dump()
    data["feature_input"]["evaluation"]["evaluated_at"] = population.available_at + timedelta(seconds=2)
    data["population"] = population.model_dump()
    data["population_baseline_request"] = dict(expected_source_reference=population.expected_reference,
        currency="KRW", available_at=population.available_at)
    request = EngineEvaluationRequest.model_validate(data)
    assert EngineEvaluationRequest.model_validate_json(request.model_dump_json()) == request
    for change in ("missing_request", "reference_version", "before_publication", "after_cutoff"):
        altered = request.model_dump()
        basis = altered["population_baseline_request"]
        if change == "missing_request":
            altered["population_baseline_request"] = None
        elif change == "reference_version":
            basis["expected_source_reference"]["version"] = "another-version"
        else:
            basis["available_at"] = (population.available_at - timedelta(microseconds=1)
                if change == "before_publication" else data["feature_input"]["evaluation"]["evaluated_at"] + timedelta(microseconds=1))
        with pytest.raises(ValueError):
            EngineEvaluationRequest.model_validate(altered)
