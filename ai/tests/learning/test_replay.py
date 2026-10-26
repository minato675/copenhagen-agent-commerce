"""Historical feature snapshots retain their own clock and coverage."""

import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.learning_replay import LearningReplayInput, LearningSample
from copenhagen_fds.features import transform_features
from copenhagen_fds.learning_replay import replay_learning_samples


@pytest.fixture
def replay_payload(learning_payload):
    purchase = learning_payload["purchase"]
    evaluated_at = purchase["evaluated_at"]
    return {
        "learning_input": learning_payload,
        "feature_input": {
            "evaluation": {"request": {
                "context": {"evaluation_id": "evaluation-1", "user_id": "user-1", "purchase_request_id": "purchase-1"},
                "intent": {"purchase_request_id": "purchase-1", "source": "unknown"},
                "order": purchase["order"],
            }, "evaluated_at": evaluated_at, "feature_schema_version": "cold-features-v1",
                "requested_features": ["amount_minor", "purchase_count_10m", "purchase_count_1h", "purchase_count_24h",
                                       "attempt_count_10m", "attempt_count_1h", "attempt_count_24h", "kst_hour", "kst_day_of_week"]},
            "current_occurred_at": purchase["occurred_at"], "current_available_at": purchase["available_at"],
            "current_attempt_id": None,
            "history": {"as_of": evaluated_at, "events": [],
                        "purchases": {"status": "COLLECTED", "complete_from": "2026-09-09T00:00:00+09:00", "complete_until": evaluated_at},
                        "attempts": {"status": "COLLECTED", "complete_from": "2026-09-09T00:00:00+09:00", "complete_until": evaluated_at}},
        },
    }


def run(*items):
    return replay_learning_samples(items, origin="SYNTHETIC", source_reference="test-observations")


def test_reuses_feature_transform_and_preserves_two_clocks(replay_payload):
    item = LearningReplayInput.model_validate(replay_payload)
    sample, = run(item)
    assert sample.raw_features == transform_features(item.feature_input)
    assert sample.raw_features.evaluated_at < sample.eligibility.as_of
    assert sample.eligibility.status == "INCLUDE"
    assert sample.eligibility.learning_amount.amount_minor == 10000
    assert LearningSample.model_validate_json(sample.model_dump_json()) == sample


@pytest.mark.parametrize("mode,status,value", [("complete", "AVAILABLE", 0),
                                               ("absent", "NOT_COLLECTED", None),
                                               ("partial", "INSUFFICIENT_HISTORY", None)])
def test_zero_and_missing_collection_remain_distinct(replay_payload, mode, status, value):
    if mode == "absent":
        replay_payload["feature_input"]["history"] = None
    elif mode == "partial":
        replay_payload["feature_input"]["history"]["purchases"]["complete_from"] = "2026-09-10T09:55:00+09:00"
    sample, = run(LearningReplayInput.model_validate(replay_payload))
    count = next(feature for feature in sample.raw_features.features if feature.feature_id == "purchase_count_10m")
    assert (count.status, count.value) == (status, value)


def test_duplicate_inputs_are_idempotent_and_order_is_deterministic(replay_payload):
    first = LearningReplayInput.model_validate(replay_payload)
    second = LearningReplayInput.model_validate_json(json.dumps(replay_payload).replace('purchase-1', 'purchase-2').replace('evaluation-1', 'evaluation-2'))
    assert run(second, first, second) == run(first, second)
    assert [sample.eligibility.target.purchase_request_id for sample in run(second, first)] == ["purchase-1", "purchase-2"]


def test_current_purchase_is_not_a_pre_evaluation_training_sample(replay_payload):
    replay_payload["learning_input"]["current_purchase_request_id"] = "purchase-1"
    assert run(LearningReplayInput.model_validate(replay_payload)) == ()


def test_sample_schema_requires_explicit_missing_values_instead_of_omitted_features(replay_payload):
    sample, = run(LearningReplayInput.model_validate(replay_payload))
    payload = sample.model_dump(mode="json")
    payload["raw_features"]["features"].pop()
    with pytest.raises(ValidationError, match="complete raw feature schema"):
        LearningSample.model_validate(payload)
    replay_payload["feature_input"]["evaluation"]["requested_features"] = ["amount_minor"]
    with pytest.raises(ValidationError, match="complete raw feature schema"):
        LearningReplayInput.model_validate(replay_payload)


def test_incompatible_snapshots_or_learning_cutoffs_are_not_silently_combined(replay_payload):
    original = LearningReplayInput.model_validate(replay_payload)
    replay_payload["learning_input"]["purchase"]["feedback"] = None
    with pytest.raises(ValueError, match="conflicting snapshots"):
        run(original, LearningReplayInput.model_validate(replay_payload))
    replay_payload["learning_input"]["as_of"] = "2026-09-10T11:00:01+09:00"
    with pytest.raises(ValueError, match="one learning information cutoff"):
        run(original, LearningReplayInput.model_validate(replay_payload))


@pytest.mark.parametrize("field,value", [("current_available_at", "2026-09-10T09:59:00+09:00"),
                                        ("current_attempt_id", "attempt-1")])
def test_wrong_feature_context_is_rejected(replay_payload, field, value):
    replay_payload["feature_input"][field] = value
    with pytest.raises(ValidationError):
        LearningReplayInput.model_validate(replay_payload)


def test_sample_cannot_relabel_another_purchase_or_use_future_eligibility(replay_payload):
    sample, = run(LearningReplayInput.model_validate(replay_payload))
    payload = sample.model_dump(mode="json")
    payload["eligibility"]["target"]["order"]["version"] = "old-order-version"
    with pytest.raises(ValidationError, match="same purchase reference"):
        LearningSample.model_validate(payload)
    replay_payload["learning_input"]["as_of"] = replay_payload["feature_input"]["evaluation"]["evaluated_at"]
    for name in ("purchase_coverage", "attempt_coverage"):
        replay_payload["learning_input"][name]["complete_until"] = replay_payload["learning_input"]["as_of"]
    with pytest.raises(ValidationError, match="precede the learning cutoff"):
        LearningReplayInput.model_validate(replay_payload)
