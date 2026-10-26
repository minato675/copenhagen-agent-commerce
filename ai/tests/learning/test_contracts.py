"""Acceptance cases for purchase linkage, review availability and ledger round trips."""

from copy import deepcopy

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.learning import LearningInput, ProfileLearningState


def change(payload, path, value):
    target = payload
    for part in path[:-1]:
        target = target[part]
    target[path[-1]] = value


class TestReferencesAndTime:
    @pytest.mark.parametrize("path,value", [
        (("user_id",), "another-user"),
        (("currency",), "USD"),
        (("purchase", "events", 0, "purchase_request_id"), "another-purchase"),
        (("purchase", "events", 0, "order_id"), "another-order"),
        (("purchase", "outcomes", 0, "attempt_id"), "missing-attempt"),
        (("purchase", "outcomes", 0, "target", "order", "version"), "v0"),
        (("purchase", "feedback", "target", "order", "snapshot_hash"), "b" * 64),
    ])
    def test_foreign_reference_is_rejected(self, learning_payload, path, value):
        change(learning_payload, path, value)
        with pytest.raises(ValidationError):
            LearningInput.model_validate(learning_payload)

    @pytest.mark.parametrize("path,value", [
        (("purchase", "available_at"), "2026-09-10T09:59:00+09:00"),
        (("purchase", "evaluated_at"), "2026-09-10T09:59:00+09:00"),
        (("purchase", "outcomes", 0, "occurred_at"), "2026-09-10T09:59:00+09:00"),
        (("purchase", "feedback", "available_at"), "2026-09-10T10:01:00+09:00"),
        (("requested_at",), "2026-09-10T10:59:00+09:00"),
        (("purchase_coverage", "complete_until"), "2026-09-10T11:01:00+09:00"),
        (("as_of",), "2026-09-10T11:00:00"),
    ])
    def test_invalid_time_relationship_is_rejected(self, learning_payload, path, value):
        change(learning_payload, path, value)
        with pytest.raises(ValidationError):
            LearningInput.model_validate(learning_payload)

    @pytest.mark.parametrize("field", ["schema_version", "feature_schema_version", "policy_version"])
    def test_unsupported_version_is_rejected(self, learning_payload, field):
        learning_payload[field] = "unsupported-v99"
        with pytest.raises(ValidationError):
            LearningInput.model_validate(learning_payload)

    def test_duplicate_events_and_results_require_canonicalization(self, learning_payload):
        for field in ("events", "outcomes"):
            payload = deepcopy(learning_payload)
            payload["purchase"][field].append(deepcopy(payload["purchase"][field][0]))
            with pytest.raises(ValidationError):
                LearningInput.model_validate(payload)

    def test_uncollected_stream_cannot_supply_events(self, learning_payload):
        learning_payload["attempt_coverage"] = {"status": "NOT_COLLECTED"}
        with pytest.raises(ValidationError, match="collected stream"):
            LearningInput.model_validate(learning_payload)


class TestSerializationAndState:
    def test_input_and_state_json_preserve_references_versions_and_status(self, learning_payload, state_payload):
        request = LearningInput.model_validate(learning_payload)
        state = ProfileLearningState.model_validate(state_payload)
        assert LearningInput.model_validate_json(request.model_dump_json()) == request
        restored = ProfileLearningState.model_validate_json(state.model_dump_json())
        assert restored == state
        assert restored.schema_version == "profile-learning-state-v1"
        assert restored.policy_version == "confirmed-normal-v1"
        assert restored.state_version == "ledger-v2"
        assert restored.profile.reference.version == "profile-v1"
        assert restored.decisions[0].status == "DEFER"
        # An expired evaluation profile may still be the input to a later update.
        assert restored.validate_for_update(user_id="user-1", currency="KRW",
            expected_state_version="ledger-v2", expected_profile_reference=state.profile.reference) is restored

    @pytest.mark.parametrize("field,value", [("user_id", "other"), ("currency", "USD"),
                                               ("expected_state_version", "ledger-v1"),
                                               ("expected_profile_reference", None)])
    def test_stale_or_foreign_update_context_is_rejected(self, state_payload, field, value):
        state = ProfileLearningState.model_validate(state_payload)
        context = dict(user_id="user-1", currency="KRW", expected_state_version="ledger-v2",
                       expected_profile_reference=state.profile.reference)
        context[field] = value
        with pytest.raises(ValueError):
            state.validate_for_update(**context)

    @pytest.mark.parametrize("path,value", [
        (("decisions", 0, "target", "user_id"), "other"),
        (("decisions", 0, "as_of"), "2026-09-10T12:00:00+09:00"),
        (("profile", "reference", "user_id"), "other"),
        (("profile", "feature_schema_version"), "old-schema"),
        (("schema_version",), "state-v99"),
    ])
    def test_incompatible_ledger_content_is_rejected(self, state_payload, path, value):
        change(state_payload, path, value)
        with pytest.raises(ValidationError):
            ProfileLearningState.model_validate(state_payload)

    def test_ledger_cannot_count_two_order_versions_as_two_purchases(self, state_payload):
        duplicate = deepcopy(state_payload["decisions"][0])
        duplicate["target"]["order"]["version"] = "v2"
        state_payload["decisions"].append(duplicate)
        with pytest.raises(ValidationError, match="one decision per whole purchase"):
            ProfileLearningState.model_validate(state_payload)


class TestInputIsolation:
    @pytest.mark.parametrize("path,value", [
        (("truth",), {"is_fraud": True}),
        (("purchase", "scenario"), "amount_spike"),
        (("purchase", "feedback", "persona"), {"median": 5000}),
    ])
    def test_benchmark_labels_and_hidden_distribution_are_rejected(self, learning_payload, path, value):
        change(learning_payload, path, value)
        with pytest.raises(ValidationError, match="Extra inputs"):
            LearningInput.model_validate(learning_payload)

    def test_feedback_requires_availability_and_explicit_verification(self, learning_payload):
        for field in ("available_at", "verification"):
            payload = deepcopy(learning_payload)
            del payload["purchase"]["feedback"][field]
            with pytest.raises(ValidationError, match="Field required"):
                LearningInput.model_validate(payload)
