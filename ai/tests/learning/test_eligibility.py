"""Business outcomes, temporal boundaries and activity retained outside learning."""

from copy import deepcopy

import pytest

from copenhagen_fds.contracts.learning import LearningDecision, LearningInput, ProfileLearningState
from copenhagen_fds.learning import assess_learning_eligibility


def assess(payload):
    return assess_learning_eligibility(LearningInput.model_validate(payload))


@pytest.mark.parametrize("classification,verification,status,reason", [
    (None, None, "DEFER", "UNCONFIRMED"),
    ("UNCONFIRMED", "VERIFIED", "DEFER", "UNCONFIRMED"),
    ("SUSPECTED", "UNVERIFIED", "DEFER", "SUSPECTED"),
    ("NORMAL_CONFIRMED", "VERIFIED", "INCLUDE", "NORMAL_CONFIRMED"),
    ("FRAUD_CONFIRMED", "VERIFIED", "EXCLUDE", "FRAUD_CONFIRMED"),
    ("NORMAL_CONFIRMED", "UNVERIFIED", "DEFER", "UNVERIFIED_FEEDBACK"),
    ("FRAUD_CONFIRMED", "UNVERIFIED", "DEFER", "UNVERIFIED_FEEDBACK"),
])
def test_basic_policy_preserves_observed_activity(learning_payload, classification, verification, status, reason):
    if classification is None:
        learning_payload["purchase"]["feedback"] = None
    else:
        learning_payload["purchase"]["feedback"].update(classification=classification, verification=verification)
    result = assess(learning_payload)
    assert (result.status, result.reason) == (status, reason)
    assert result.observed_purchase is True
    assert result.observed_attempt_ids == ("attempt-1", "attempt-2")
    assert result.policy_version == "confirmed-normal-v1"
    assert (result.learning_amount is not None) == (status == "INCLUDE")


@pytest.mark.parametrize("status", ["SUCCEEDED", "FAILED"])
def test_payment_result_does_not_establish_normality(learning_payload, status):
    for outcome in learning_payload["purchase"]["outcomes"]:
        outcome["status"] = status
    confirmed = assess(learning_payload)
    learning_payload["purchase"]["feedback"] = None
    unconfirmed = assess(learning_payload)
    assert confirmed.status == "INCLUDE"
    assert unconfirmed.status == "DEFER"


@pytest.mark.parametrize("classification,expected", [("NORMAL_CONFIRMED", "INCLUDE"), ("FRAUD_CONFIRMED", "EXCLUDE")])
def test_future_feedback_is_unavailable_until_exact_cutoff(learning_payload, classification, expected):
    feedback = learning_payload["purchase"]["feedback"]
    feedback.update(classification=classification, available_at="2026-09-10T11:00:00.000001+09:00")
    before = assess(learning_payload)
    assert (before.status, before.reason, before.feedback_id) == ("DEFER", "UNCONFIRMED", None)
    feedback["available_at"] = learning_payload["as_of"]
    assert assess(learning_payload).status == expected


def test_current_purchase_is_not_used_in_its_own_pre_evaluation_profile(learning_payload):
    learning_payload["current_purchase_request_id"] = "purchase-1"
    result = assess(learning_payload)
    assert (result.status, result.reason) == ("DEFER", "CURRENT_PURCHASE")
    assert not result.observed_purchase
    assert result.observed_attempt_ids == ()
    assert result.feedback_id is None


def test_late_purchase_event_does_not_expose_review_or_attempts(learning_payload):
    for event in learning_payload["purchase"]["events"]:
        if event["kind"] == "PURCHASE_REQUESTED":
            event["available_at"] = "2026-09-10T11:00:01+09:00"
    result = assess(learning_payload)
    assert result.reason == "PURCHASE_NOT_OBSERVED"
    assert not result.observed_purchase and not result.observed_attempt_ids
    assert result.feedback_id is None


def test_event_at_cutoff_and_late_attempt_are_not_prior_activity(learning_payload):
    at_purchase = deepcopy(learning_payload)
    at_purchase["as_of"] = at_purchase["purchase"]["occurred_at"]
    for field in ("purchase_coverage", "attempt_coverage"):
        at_purchase[field]["complete_until"] = at_purchase["as_of"]
    assert assess(at_purchase).reason == "PURCHASE_NOT_OBSERVED"
    learning_payload["purchase"]["events"][1]["available_at"] = "2026-09-10T11:00:01+09:00"
    assert assess(learning_payload).observed_attempt_ids == ("attempt-2",)


def test_whole_purchase_amount_is_included_once_and_state_round_trips(learning_payload):
    result = assess(learning_payload)
    assert result.learning_amount.amount_minor == 10000  # Not 10000 + 6000 + 4000.
    assert LearningDecision.model_validate_json(result.model_dump_json()) == result
    state = ProfileLearningState(state_version="ledger-v1", user_id="user-1", currency="KRW",
        as_of=result.as_of, profile=None, decisions=(result,))
    assert ProfileLearningState.model_validate_json(state.model_dump_json()) == state
    assert assess(learning_payload) == result
    assert learning_payload["purchase"]["outcomes"][0]["status"] == "FAILED"
