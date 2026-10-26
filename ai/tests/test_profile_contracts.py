"""Profile provenance and temporal coverage cannot become invented observations."""

from datetime import datetime, timezone
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.profiles import (
    BehaviorProfile,
    FeatureObservation,
    ProfileOrigin,
    ProfileReference,
    ProfileScope,
)


@pytest.fixture
def profile_payload():
    return {
        "reference": {
            "profile_id": "profile-1", "version": "v2", "scope": "PERSONAL", "user_id": "user-1",
        },
        "origin": "OBSERVED",
        "source_reference": "history-batch-7",
        "feature_schema_version": "behavior-v1",
        "currency": "KRW",
        "as_of": "2026-09-10T10:00:00+09:00",
        "generated_at": "2026-09-10T10:00:01+09:00",
        "valid_until": "2026-09-10T11:00:00+09:00",
        "observations": [
            {
                "feature_id": "purchase_amount", "valid_sample_count": 4,
                "observed_from": "2026-09-09T10:00:00+09:00",
                "last_observed_at": "2026-09-10T09:59:59+09:00",
            },
            {"feature_id": "failed_attempts", "valid_sample_count": 0},
        ],
    }


@pytest.fixture
def evaluation_context():
    return {"user_id": "user-1", "currency": "KRW", "evaluated_at": "2026-09-10T10:30:00+09:00"}


@pytest.mark.parametrize("scope", ["PERSONAL", "POPULATION"])
@pytest.mark.parametrize("origin", ["OBSERVED", "SYNTHETIC"])
def test_profile_round_trip_preserves_coverage_and_provenance(profile_payload, scope, origin):
    profile_payload["origin"] = origin
    if scope == "POPULATION":
        profile_payload["reference"].update(scope=scope, user_id=None, cohort_id="cohort-1")
    profile = BehaviorProfile.model_validate(profile_payload)
    assert profile.reference.scope == ProfileScope(scope)
    assert profile.origin == ProfileOrigin(origin)
    assert BehaviorProfile.model_validate_json(json.dumps(profile_payload)) == profile
    assert BehaviorProfile.model_validate_json(profile.model_dump_json()) == profile
    assert profile.observations[1].valid_sample_count == 0
    assert profile.observations[1].last_observed_at is None
    assert profile.source_reference == "history-batch-7"
    assert isinstance(profile.observations, tuple)


@pytest.mark.parametrize("scope,user_id,cohort_id", [
    ("PERSONAL", None, None), ("PERSONAL", None, "cohort-1"),
    ("PERSONAL", "user-1", "cohort-1"), ("POPULATION", None, None),
    ("POPULATION", "user-1", None), ("POPULATION", "user-1", "cohort-1"),
])
def test_scope_requires_exactly_the_correct_owner(scope, user_id, cohort_id):
    with pytest.raises(ValidationError):
        ProfileReference(profile_id="p-1", version="v1", scope=scope, user_id=user_id, cohort_id=cohort_id)


@pytest.mark.parametrize("field,value", [
    ("scope", "personal"), ("scope", True), ("scope", 1),
    ("profile_id", ""), ("version", " "), ("user_id", 1), ("cohort_id", ""),
])
def test_reference_rejects_coercion_and_blank_identifiers(profile_payload, field, value):
    reference = profile_payload["reference"]
    reference[field] = value
    with pytest.raises(ValidationError):
        ProfileReference.model_validate(reference)


@pytest.mark.parametrize("count", [-1, True, False, 1.0, "1", None])
def test_sample_count_must_be_a_nonnegative_integer(count):
    with pytest.raises(ValidationError):
        FeatureObservation(feature_id="amount", valid_sample_count=count)


@pytest.mark.parametrize("field", ["observed_from", "last_observed_at"])
def test_zero_samples_cannot_claim_an_observation(field):
    with pytest.raises(ValidationError, match="zero samples"):
        FeatureObservation.model_validate({
            "feature_id": "amount", "valid_sample_count": 0,
            field: "2026-09-10T09:00:00+09:00",
        })


@pytest.mark.parametrize("missing", ["observed_from", "last_observed_at"])
def test_positive_samples_require_both_timestamps(profile_payload, missing):
    observation = profile_payload["observations"][0]
    observation.pop(missing)
    with pytest.raises(ValidationError, match="positive samples"):
        FeatureObservation.model_validate(observation)


def test_same_instant_is_a_valid_single_observation(profile_payload):
    observation = profile_payload["observations"][0]
    observation.update(valid_sample_count=1, observed_from=observation["last_observed_at"])
    profile = BehaviorProfile.model_validate(profile_payload)
    assert profile.observations[0].observed_from == profile.observations[0].last_observed_at


def test_reversed_observation_range_is_rejected(profile_payload):
    profile_payload["observations"][0]["observed_from"] = "2026-09-10T10:00:00+09:00"
    with pytest.raises(ValidationError, match="observed_from"):
        BehaviorProfile.model_validate(profile_payload)


@pytest.mark.parametrize("last_observed_at", ["2026-09-10T10:00:00+09:00", "2026-09-10T01:00:00Z", "2026-09-10T10:00:01+09:00"])
def test_cutoff_excludes_observations_at_or_after_as_of(profile_payload, last_observed_at):
    profile_payload["observations"][0]["last_observed_at"] = last_observed_at
    with pytest.raises(ValidationError, match="earlier than as_of"):
        BehaviorProfile.model_validate(profile_payload)


@pytest.mark.parametrize("field,value", [
    ("generated_at", "2026-09-10T09:59:59+09:00"),
    ("valid_until", "2026-09-10T10:00:01+09:00"),
    ("valid_until", "2026-09-10T10:00:00+09:00"),
])
def test_profile_time_order_is_checked(profile_payload, field, value):
    profile_payload[field] = value
    with pytest.raises(ValidationError):
        BehaviorProfile.model_validate(profile_payload)


@pytest.mark.parametrize("field", ["as_of", "generated_at", "valid_until", "observed_from", "last_observed_at"])
@pytest.mark.parametrize("value", ["2026-09-10T10:00:00", 1789002000, True, "not-a-date"])
def test_all_timestamps_require_explicit_timezone_without_epoch_coercion(profile_payload, field, value):
    target = profile_payload["observations"][0] if field in {"observed_from", "last_observed_at"} else profile_payload
    target[field] = value
    with pytest.raises(ValidationError):
        BehaviorProfile.model_validate(profile_payload)


def test_empty_profile_does_not_invent_feature_history(profile_payload, evaluation_context):
    profile_payload["observations"] = []
    profile = BehaviorProfile.model_validate(profile_payload)
    assert profile.observations == ()
    assert profile.validate_for_evaluation(**evaluation_context) is profile


def test_duplicate_feature_ids_are_rejected_even_with_different_counts(profile_payload):
    profile_payload["observations"][1]["feature_id"] = "purchase_amount"
    with pytest.raises(ValidationError, match="unique"):
        BehaviorProfile.model_validate(profile_payload)


@pytest.mark.parametrize("field,value", [
    ("origin", "observed"), ("origin", True), ("source_reference", " "),
    ("feature_schema_version", 1), ("currency", "krw"), ("currency", "ZZZ"),
    ("currency", "XAU"), ("observations", None), ("observations", {}),
])
def test_profile_rejects_invalid_wire_values(profile_payload, field, value):
    profile_payload[field] = value
    with pytest.raises(ValidationError):
        BehaviorProfile.model_validate(profile_payload)


@pytest.mark.parametrize("nested", [None, "reference", "observation"])
def test_profile_contracts_reject_extra_keys_and_do_not_echo_private_values(profile_payload, nested):
    target = profile_payload if nested is None else profile_payload["reference"] if nested == "reference" else profile_payload["observations"][0]
    marker = "synthetic-private-value"
    target["unrecognized_field"] = marker
    with pytest.raises(ValidationError) as caught:
        BehaviorProfile.model_validate(profile_payload)
    assert marker not in str(caught.value)


def test_profile_and_nested_records_are_immutable(profile_payload):
    profile = BehaviorProfile.model_validate(profile_payload)
    for model, field, replacement in [
        (profile, "currency", "USD"), (profile.reference, "version", "v3"),
        (profile.observations[0], "valid_sample_count", 5),
    ]:
        with pytest.raises(ValidationError, match="frozen"):
            setattr(model, field, replacement)


def test_evaluation_accepts_matching_versions_and_aware_datetime(profile_payload, evaluation_context):
    profile = BehaviorProfile.model_validate(profile_payload)
    evaluation_context["evaluated_at"] = datetime(2026, 9, 10, 1, 30, tzinfo=timezone.utc)
    assert profile.validate_for_evaluation(
        **evaluation_context, expected_reference=profile.reference, feature_schema_version="behavior-v1",
    ) is profile


@pytest.mark.parametrize("field,value", [
    ("user_id", "other-user"), ("currency", "USD"), ("feature_schema_version", "behavior-v2"),
    ("evaluated_at", "2026-09-10T09:59:59+09:00"),
    ("evaluated_at", "2026-09-10T10:00:00+09:00"),
    ("evaluated_at", "2026-09-10T11:00:00+09:00"),
    ("evaluated_at", "2026-09-10T11:00:01+09:00"),
])
def test_evaluation_rejects_mismatched_or_unavailable_profile(profile_payload, evaluation_context, field, value):
    profile = BehaviorProfile.model_validate(profile_payload)
    evaluation_context[field] = value
    with pytest.raises(ValueError):
        profile.validate_for_evaluation(**evaluation_context)


def test_generation_time_is_inclusive_and_expiry_is_exclusive(profile_payload, evaluation_context):
    profile_payload["generated_at"] = profile_payload["as_of"]
    profile = BehaviorProfile.model_validate(profile_payload)
    evaluation_context["evaluated_at"] = profile_payload["generated_at"]
    assert profile.validate_for_evaluation(**evaluation_context) is profile
    evaluation_context["evaluated_at"] = "2026-09-10T10:59:59.999999+09:00"
    assert profile.validate_for_evaluation(**evaluation_context) is profile


@pytest.mark.parametrize("field,value", [("profile_id", "other-profile"), ("version", "v3"), ("user_id", "other-user")])
def test_evaluation_checks_the_entire_expected_reference(profile_payload, evaluation_context, field, value):
    profile = BehaviorProfile.model_validate(profile_payload)
    reference = profile.reference.model_dump()
    reference[field] = value
    with pytest.raises(ValueError, match="expected reference"):
        profile.validate_for_evaluation(**evaluation_context, expected_reference=ProfileReference.model_validate(reference))


def test_population_profile_does_not_claim_to_check_cohort_membership(profile_payload, evaluation_context):
    profile_payload["reference"].update(scope="POPULATION", user_id=None, cohort_id="cohort-1")
    profile = BehaviorProfile.model_validate(profile_payload)
    evaluation_context["user_id"] = "another-user"
    assert profile.validate_for_evaluation(**evaluation_context) is profile
    reference = profile.reference.model_dump()
    reference["cohort_id"] = "different-cohort"
    with pytest.raises(ValueError, match="expected reference"):
        profile.validate_for_evaluation(**evaluation_context, expected_reference=ProfileReference.model_validate(reference))


@pytest.mark.parametrize("field,value", [
    ("user_id", ""), ("user_id", True), ("currency", "krw"),
    ("evaluated_at", "2026-09-10T10:30:00"), ("evaluated_at", 1789003800),
    ("evaluated_at", True), ("feature_schema_version", " "), ("expected_reference", "v2"),
])
def test_evaluation_arguments_are_validated_too(profile_payload, evaluation_context, field, value):
    profile = BehaviorProfile.model_validate(profile_payload)
    evaluation_context[field] = value
    with pytest.raises(ValidationError):
        profile.validate_for_evaluation(**evaluation_context)


def test_evaluation_error_does_not_expose_reference_values(profile_payload, evaluation_context):
    profile = BehaviorProfile.model_validate(profile_payload)
    marker = "synthetic-private-reference"
    evaluation_context["feature_schema_version"] = marker
    with pytest.raises(ValueError) as caught:
        profile.validate_for_evaluation(**evaluation_context)
    assert marker not in str(caught.value)
