import math

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.personalization_results import PersonalizedFeatureResult, PersonalizedScoringResult


@pytest.mark.parametrize("weight,stage,amount", [(0.0, "COLD", 0.0), (0.25, "TRANSITION", 20.0), (1.0, "PERSONALIZED", 80.0)])
def test_hand_calculated_mixture_stages_and_json_roundtrip(personalized, weight, stage, amount):
    supplied = personalized.request()
    before = supplied.model_dump_json()
    result = personalized.result(supplied, weight=weight)
    feature = result.features[0]
    assert (feature.stage, feature.cold.score.value, feature.personal.score.value, feature.combined.score.value) == (
        stage, 0.0, 80.0, amount)
    assert feature.cold_weight == 1.0 - weight and feature.personal_weight == weight
    assert result.behavior.coverage == "COMPLETE"
    assert result.profiles[0].context.policy_state_version == supplied.personal.state.state_version
    restored = PersonalizedScoringResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied)
    assert restored == result and personalized.result(supplied, weight=weight) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("change", ["negative", "too_large", "infinity", "nan", "missing", "sum", "stage", "score", "bases", "reason", "zero_personal_reason", "window", "version", "unscored_weight", "wrong_feature"])
def test_inconsistent_component_mixture_is_rejected(personalized, change):
    data = personalized.result(personalized.request()).features[0].model_dump(mode="python")
    if change in {"negative", "too_large", "infinity", "nan", "missing", "sum"}:
        data["personal_weight"] = {"negative": -0.1, "too_large": 1.1, "infinity": float("inf"),
                                   "nan": float("nan"), "missing": None, "sum": 0.5}[change]
    elif change == "stage":
        data["stage"] = "PERSONALIZED"
    elif change == "score":
        data["combined"]["score"]["value"] = 21.0
    elif change == "bases":
        data["combined"]["bases"] = data["cold"]["bases"]
    elif change == "reason":
        data["combined"]["reasons"] = ["WITHIN_REFERENCE"]
    elif change == "zero_personal_reason":
        data["personal"]["score"]["value"] = 0.0
    elif change in {"window", "version"}:
        data["personal_window" if change == "window" else "personal_score_version"] = None
    elif change == "unscored_weight":
        data["personal"] = {"feature_id": "amount_minor", "status": "INSUFFICIENT_DATA", "reasons": ["REFERENCE_UNAVAILABLE"]}
        data.update(personal_window=None, personal_score_version=None)
    else:
        data["personal"]["feature_id"] = "purchase_count_10m"
    with pytest.raises(ValidationError):
        PersonalizedFeatureResult.model_validate(data)


@pytest.mark.parametrize("which", ["NOT_COLLECTED", "INSUFFICIENT_HISTORY", "NOT_APPLICABLE"])
def test_missing_raw_features_have_no_score_or_mixture(personalized, which):
    supplied = personalized.request(states={"purchase_count_10m": which})
    result = personalized.result(supplied)
    feature = result.features[1]
    assert feature.stage == "UNAVAILABLE" and feature.combined.score is None
    assert feature.personal_weight is None and feature.cold_weight is None
    assert result.behavior.coverage == "PARTIAL" and result.behavior.status == "SUCCESS"
    assert feature.personal.status == feature.combined.status == feature.cold.status


@pytest.mark.parametrize("personal", [False, "expired"])
def test_missing_or_expired_personal_profile_preserves_cold_score(personalized, personal):
    if personal == "expired":
        publication = personalized.publication()
        supplied = personalized.request(personal=publication, at=publication.state.published.profile.valid_until)
    else:
        supplied = personalized.request(personal=False)
    result = personalized.result(supplied)
    assert result.features[0].combined.score.value == 0.0
    assert result.features[0].personal.score is None and result.features[0].personal_weight == 0.0
    assert result.features[0].stage == "COLD"
    assert result.profiles[0].status == ("EXPIRED" if personal == "expired" else "NOT_SUPPLIED")
    assert result.behavior.profiles == ()


@pytest.mark.parametrize("field", ["input_sha256", "state_sha256", "config_sha256", "personal_score_version", "evidence"])
def test_result_binding_rejects_different_input_provenance_and_statistics(personalized, field):
    supplied = personalized.request()
    data = personalized.result(supplied).model_dump(mode="python")
    if field == "state_sha256":
        data["profiles"][0]["context"]["state_sha256"] = "b" * 64
    elif field == "personal_score_version":
        data["features"][0]["personal_score_version"] = "unknown-score-v2"
    elif field == "evidence":
        # Counts and metadata can still be internally valid while the distribution is not the supplied one.
        data["features"][0]["evidence"][0]["distribution"]["numeric"]["median_absolute_deviation"] = 7.0
    else:
        data[field] = "b" * 64
    with pytest.raises((ValidationError, ValueError)):
        PersonalizedScoringResult.model_validate(data).validate_for_input(supplied)


def test_different_request_cannot_reuse_previously_valid_result(personalized):
    supplied = personalized.request()
    result = personalized.result(supplied)
    other = personalized.request(current="another-purchase")
    with pytest.raises(ValueError, match="exact supplied input"):
        result.validate_for_input(other)


def test_raw_vector_order_does_not_change_evidence_binding(personalized):
    data = personalized.request().model_dump(mode="python")
    data["cold"]["raw_features"]["features"] = list(reversed(data["cold"]["raw_features"]["features"]))
    supplied = PersonalizedScoringInput.model_validate(data)
    assert personalized.result(supplied).features[0].combined.feature_id == "amount_minor"


def test_failed_component_remains_failed_even_with_usable_cold_fallback(personalized):
    supplied = personalized.request()
    original = personalized.result(supplied, weight=0.0)
    data = original.model_dump(mode="python")
    data["features"][0]["personal"].update(status="FAILED", score=None, reasons=["CALCULATION_FAILED"])
    with pytest.raises(ValidationError, match="failures cannot be hidden"):
        PersonalizedScoringResult.model_validate(data)
    data["behavior"].update(status="FAILED", reasons=[{"code": "EVALUATION_FAILED", "source": "FDS"}])
    valid = PersonalizedScoringResult.model_validate(data).validate_for_input(supplied)
    assert valid.features[0].combined.score.value == 0.0
    assert valid.behavior.status == "FAILED" and valid.behavior.coverage == "COMPLETE"


def test_final_risk_and_probability_are_not_part_of_personalized_result(personalized):
    data = personalized.result(personalized.request()).model_dump(mode="python")
    data["risk_level"] = "LOW"
    with pytest.raises(ValidationError):
        PersonalizedScoringResult.model_validate(data)


def test_no_references_returns_unavailable_instead_of_all_zero(personalized):
    supplied = personalized.request(personal=False, policy_enabled=False)
    result = personalized.result(supplied)
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "UNAVAILABLE"
    assert all(f.combined.score is None and f.stage == "UNAVAILABLE" for f in result.features)
    assert all(f.cold_weight is None and f.personal_weight is None for f in result.features)


def test_population_publication_and_derived_cold_result_keep_both_origins(personalized):
    supplied = personalized.request(population_profile=personalized.publication(scope="POPULATION"))
    result = personalized.result(supplied)
    assert result.features[0].cold.bases[0].profile == supplied.population_baseline.baseline.profile.reference
    assert result.profiles[1].context.profile.reference == supplied.population.expected_reference
    assert PersonalizedScoringResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result


def test_missing_input_cannot_be_claimed_as_scored_even_with_fabricated_personal_score(personalized):
    supplied = personalized.request(states={"purchase_count_10m": "NOT_COLLECTED"})
    real = personalized.result(supplied)
    valid = personalized.result(personalized.request(), weight=1.0)
    data = real.model_dump(mode="python")
    features = list(data["features"])
    fake = valid.features[1].model_dump(mode="python")
    fake["cold"] = real.features[1].cold
    features[1] = fake
    data["features"] = features
    data["behavior"].update(features=[f["combined"] for f in features], coverage="COMPLETE", reasons=[])
    with pytest.raises(ValueError, match="missing raw input"):
        PersonalizedScoringResult.model_validate(data).validate_for_input(supplied)


@pytest.mark.parametrize("weight,cold_score,personal_score,expected", [
    (0.0004, 100.0, 100.0, 100.0),
    (math.nextafter(0.0, 1.0), 0.0, 0.5, math.nextafter(0.0, 1.0)),
])
def test_mixture_rounding_preserves_upper_bound_and_positive_underflow(personalized, weight, cold_score, personal_score, expected):
    data = personalized.result(personalized.request()).features[0].model_dump(mode="python")
    data.update(personal_weight=weight, cold_weight=1.0 - weight)
    for key, value in (("cold", cold_score), ("personal", personal_score), ("combined", expected)):
        data[key]["score"]["value"] = value
        data[key]["reasons"] = ["PATTERN_DEVIATION" if value else "WITHIN_REFERENCE"]
    assert PersonalizedFeatureResult.model_validate(data).combined.score.value == expected
