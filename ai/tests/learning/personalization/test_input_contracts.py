from datetime import timedelta, timezone

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.personalization_config import PersonalizationConfig, ReadinessThresholds
from copenhagen_fds.contracts.personalization_inputs import (
    PersonalizedScoringInput, PublishedProfileInput, personalization_hash,
)


def test_input_roundtrip_preserves_published_profile_and_policy_without_mutation(personalized):
    publication = personalized.publication(include_large=True)
    before = publication.model_dump_json()
    supplied = personalized.request(personal=publication)
    assert publication.state.candidate.snapshot.long_term.included_count == 4
    assert publication.state.published.long_term.included_count == 3
    restored = PersonalizedScoringInput.model_validate_json(supplied.model_dump_json())
    assert restored == supplied
    assert personalization_hash(restored) == personalization_hash(supplied)
    assert publication.model_dump_json() == before
    assert supplied.profile_availability()[0].context.profile == publication.state.published.profile
    with pytest.raises(ValidationError):
        supplied.config.version = "changed"


@pytest.mark.parametrize("change", ["reference", "policy_version", "candidate", "bare_snapshot", "config_hash"])
def test_publication_rejects_wrong_versions_and_candidate_substitution(personalized, change):
    publication = personalized.publication()
    data = publication.model_dump(mode="python")
    if change == "reference":
        data["expected_reference"]["version"] = "wrong"
    elif change == "policy_version":
        data["expected_policy_state_version"] = "wrong"
    elif change == "candidate":
        data["expected_reference"] = publication.state.candidate.snapshot.profile.reference
    elif change == "bare_snapshot":
        data["state"] = publication.state.published
    else:
        data["state"]["evaluation"]["config"]["deviation_ratio"] = 4.0
    with pytest.raises(ValidationError):
        PublishedProfileInput.model_validate(data)


@pytest.mark.parametrize("which", ["user", "currency", "schema", "raw_target", "raw_order", "raw_time", "raw_currency", "raw_amount", "config_missing"])
def test_request_rejects_mismatched_target_currency_schema_and_config(personalized, which):
    if which == "user":
        with pytest.raises(ValidationError):
            personalized.request(personal=personalized.publication(user="b"))
        return
    data = personalized.request().model_dump(mode="python")
    if which in {"currency", "schema"}:
        data["personal"]["state"]["published"]["profile"]["currency" if which == "currency" else "feature_schema_version"] = (
            "USD" if which == "currency" else "wrong-schema")
    elif which.startswith("raw"):
        raw = data["cold"]["raw_features"]
        if which == "raw_target":
            raw["target"]["purchase_request_id"] = "other"
        elif which == "raw_order":
            raw["target"]["order"]["version"] = "other"
        elif which == "raw_time":
            raw["evaluated_at"] += timedelta(seconds=1)
        elif which == "raw_currency":
            raw["features"][0]["currency"] = "USD"
        else:
            raw["features"][0]["value"] = 9999
    else:
        data["config"]["features"] = data["config"]["features"][:-1]
    with pytest.raises(ValidationError):
        PersonalizedScoringInput.model_validate(data)


@pytest.mark.parametrize("which", ["future_publication", "before_generation", "equal_cutoff", "future_feedback"])
def test_temporal_boundaries_reject_unknown_or_future_profile_information(personalized, which):
    publication = personalized.publication()
    data = publication.model_dump(mode="python")
    if which == "equal_cutoff":
        with pytest.raises(ValidationError):
            personalized.request(at=personalized.cutoff)
        return
    if which == "future_feedback":
        data["state"]["observations"][0]["feedback"]["available_at"] = personalized.cutoff + timedelta(seconds=1)
    else:
        data["available_at"] = personalized.cutoff + timedelta(seconds=3 if which == "future_publication" else 0)
    with pytest.raises(ValidationError):
        personalized.request(personal=PublishedProfileInput.model_validate(data))


def test_publication_available_at_evaluation_is_allowed_and_expiry_is_not_zero(personalized):
    publication = personalized.publication()
    supplied = personalized.request(personal=publication, at=publication.available_at)
    assert supplied.profile_availability()[0].status == "AVAILABLE"
    expired = personalized.request(personal=publication, at=publication.state.published.profile.valid_until)
    assert expired.profile_availability()[0].status == "EXPIRED"
    missing = personalized.request(personal=False)
    assert missing.profile_availability()[0].status == "NOT_SUPPLIED"
    assert missing.profile_availability()[0].context is None


@pytest.mark.parametrize("scope", ["PERSONAL", "POPULATION"])
def test_current_purchase_is_rejected_even_in_population_policy_history(personalized, scope):
    publication = personalized.publication(scope=scope)
    with pytest.raises(ValidationError, match="current purchase"):
        personalized.request(current="normal0", personal=publication if scope == "PERSONAL" else False,
                             population_profile=publication if scope == "POPULATION" else None)


def test_withheld_current_purchase_cannot_influence_policy_reference(personalized):
    publication = personalized.publication(include_large=True)
    assert not any(s.eligibility.target.purchase_request_id == "large" for s in publication.state.published.state.retained_samples)
    with pytest.raises(ValidationError, match="current purchase"):
        personalized.request(personal=publication, current="large")


def test_population_publication_must_match_cold_reference_and_scope(personalized):
    publication = personalized.publication(scope="POPULATION")
    supplied = personalized.request(population_profile=publication)
    assert supplied.profile_availability()[1].context.profile.reference == publication.expected_reference
    data = supplied.model_dump(mode="python")
    data["cold"]["request"]["population_reference"]["version"] = "wrong"
    with pytest.raises(ValidationError):
        PersonalizedScoringInput.model_validate(data)
    with pytest.raises(ValidationError, match="scope"):
        personalized.request(personal=publication)


def test_actual_population_derivation_has_separate_identity_and_selected_parameters(personalized):
    supplied = personalized.request(population_profile=personalized.publication(scope="POPULATION"))
    derived = supplied.population_baseline
    assert derived.baseline is not None
    assert derived.source_profile_reference == supplied.population.expected_reference
    assert supplied.cold.request.population_reference == derived.baseline.profile.reference
    assert supplied.cold.request.population_reference != supplied.population.expected_reference
    assert supplied.cold.selection.features[0].basis.profile == derived.baseline.profile.reference
    assert PersonalizedScoringInput.model_validate_json(supplied.model_dump_json()) == supplied


@pytest.mark.parametrize("change", ["source_hash", "parameter", "missing_source", "missing_derivation", "future_derivation"])
def test_population_derivation_cannot_detach_from_published_source(personalized, change):
    supplied = personalized.request(population_profile=personalized.publication(scope="POPULATION"))
    data = supplied.model_dump(mode="python")
    derived = data["population_baseline"]
    if change == "source_hash":
        derived["source_profile_sha256"] = "b" * 64
    elif change == "parameter":
        # Preserve the artifact's own shape; source reproduction must still reject it.
        derived["features"][0]["parameter"]["scale"] = 999.0
        derived["baseline"]["parameters"][0]["scale"] = 999.0
    elif change == "missing_source":
        data["population"] = None
    elif change == "missing_derivation":
        data["population_baseline"] = None
    else:
        future = personalized.cutoff + timedelta(seconds=3)
        derived["request"]["available_at"] = derived["baseline"]["available_at"] = future
    with pytest.raises(ValidationError):
        PersonalizedScoringInput.model_validate(data)


@pytest.mark.parametrize("field,value", [
    ("minimum_samples", 0), ("full_samples", 2), ("full_observed_seconds", 86400.0),
    ("stale_after_seconds", 604800.0), ("full_available_fraction", 0.5),
    ("minimum_available_fraction", -0.1), ("full_available_fraction", 1.1),
    ("fresh_for_seconds", float("inf")), ("minimum_observed_seconds", float("nan")),
    ("minimum_samples", True), ("full_samples", "10"),
])
def test_invalid_readiness_settings_are_rejected(personalized, field, value):
    data = personalized.request().config.features[0].readiness.model_dump()
    data[field] = value
    with pytest.raises(ValidationError):
        ReadinessThresholds.model_validate(data)


@pytest.mark.parametrize("change", ["duplicate", "unknown", "reordered", "extra", "blank_version"])
def test_configuration_rejects_ambiguous_feature_settings(personalized, change):
    data = personalized.request().config.model_dump(mode="python")
    data["features"] = list(data["features"])
    if change == "duplicate":
        data["features"].append(data["features"][0])
    elif change == "unknown":
        data["features"][0]["feature_id"] = "unknown"
    elif change == "reordered":
        data["features"].reverse()
    elif change == "extra":
        data["fraud_probability"] = 0.2
    else:
        data["score_scale_version"] = " "
    with pytest.raises(ValidationError):
        PersonalizationConfig.model_validate(data)


def test_input_hash_detects_content_change_and_normalizes_timezones(personalized):
    supplied = personalized.request()
    data = supplied.model_dump(mode="python")
    data["personal"]["available_at"] = data["personal"]["available_at"].astimezone(timezone.utc)
    same = PersonalizedScoringInput.model_validate(data)
    assert personalization_hash(same) == personalization_hash(supplied)
    data["config"]["features"][0]["readiness"]["full_samples"] = 11
    changed = PersonalizedScoringInput.model_validate(data)
    assert changed.config.version == supplied.config.version
    assert personalization_hash(changed) != personalization_hash(supplied)
