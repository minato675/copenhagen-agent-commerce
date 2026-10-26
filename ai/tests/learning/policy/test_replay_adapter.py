from datetime import timedelta

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.learning_replay import LearningReplayInput
from copenhagen_fds.profile_policy_replay import policy_observations_from_replay


def replay_item(payload):
    purchase = payload["purchase"]
    return LearningReplayInput.model_validate({"learning_input": payload, "feature_input": {
        "evaluation": {"request": {"context": {"evaluation_id": "evaluation", "user_id": payload["user_id"],
            "purchase_request_id": purchase["order"]["purchase_request_id"]},
            "intent": {"purchase_request_id": purchase["order"]["purchase_request_id"], "source": "unknown"},
            "order": purchase["order"]}, "evaluated_at": purchase["evaluated_at"],
            "feature_schema_version": "cold-features-v1", "requested_features": list(FEATURE_SPECS)},
        "current_occurred_at": purchase["occurred_at"], "current_available_at": purchase["available_at"],
        "current_attempt_id": None, "history": None,
    }})


def run(*items):
    return policy_observations_from_replay(items, origin="SYNTHETIC", source_reference="adapter-fixture")


def test_adapter_attaches_actual_review_availability_and_verification(learning_payload):
    item = replay_item(learning_payload)
    row, = run(item)
    assert row.feedback == item.learning_input.purchase.feedback
    assert row.sample.eligibility.feedback_id == row.feedback.feedback_id
    assert row.feedback.available_at <= row.sample.eligibility.as_of


def test_future_review_does_not_make_raw_learning_eligible(learning_payload):
    item = replay_item(learning_payload)
    learning_payload["purchase"]["feedback"]["available_at"] = item.learning_input.as_of + timedelta(days=1)
    row, = run(replay_item(learning_payload))
    assert row.sample.eligibility.status == "DEFER" and row.sample.eligibility.feedback_id is None


def test_adapter_preserves_purchase_deduplication(learning_payload):
    item = replay_item(learning_payload)
    assert run(item, item) == run(item)


def test_current_purchase_is_not_a_policy_observation(learning_payload):
    learning_payload["current_purchase_request_id"] = learning_payload["purchase"]["order"]["purchase_request_id"]
    assert run(replay_item(learning_payload)) == ()
