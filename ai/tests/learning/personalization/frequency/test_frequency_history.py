from datetime import timedelta

import pytest

from copenhagen_fds.contracts.features import FEATURE_SPECS, FeatureTransformInput
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES
from copenhagen_fds.features import transform_features
from copenhagen_fds.personal_frequency import score_personal_frequencies


def _event(at, identity, *, purchase="prior", attempt=None, order=None, available=None, currency="KRW"):
    return {"event_id": identity, "kind": "PAYMENT_ATTEMPTED" if attempt else "PURCHASE_REQUESTED",
        "user_id": "a", "purchase_request_id": purchase, "attempt_id": attempt, "order_id": order,
        "occurred_at": at, "available_at": available or at, "currency": currency}


def _history_score(cases, supplied, events, *, purchases=None, attempts=None, snapshot_at=None):
    evaluation = supplied.cold.request.evaluation
    at = evaluation.evaluated_at
    complete = {"status": "COLLECTED", "complete_from": at - timedelta(days=1), "complete_until": at}
    data = FeatureTransformInput(evaluation=evaluation, current_occurred_at=at - timedelta(minutes=1),
        current_available_at=at, current_attempt_id="active-attempt", history={
            "as_of": snapshot_at or at, "purchases": purchases or complete,
            "attempts": attempts or complete, "events": events,
        })
    raw = transform_features(data)
    result = score_personal_frequencies(cases.with_raw(supplied, raw))
    assert tuple(f.raw for f in result.features) == raw.features
    return result


def test_merchant_purchase_copies_and_retries_reuse_existing_identities(frequency_cases):
    supplied = frequency_cases.request()
    at = supplied.cold.request.evaluation.evaluated_at
    current = supplied.cold.request.evaluation.target.purchase_request_id
    prior_at = at - timedelta(minutes=5)
    events = [
        _event(prior_at, "purchase-merchant-a", order="merchant-order-a"),
        _event(prior_at, "purchase-merchant-b", order="merchant-order-b"),
        _event(prior_at, "attempt-first", attempt="attempt-1", order="merchant-order-a"),
        _event(prior_at, "attempt-copy", attempt="attempt-1", order="merchant-order-a"),
        _event(prior_at + timedelta(seconds=1), "attempt-retry", attempt="attempt-2", order="merchant-order-a"),
        _event(at - timedelta(minutes=1), "current-purchase", purchase=current),
        _event(at - timedelta(seconds=30), "current-prior-attempt", purchase=current, attempt="previous-attempt"),
        _event(at - timedelta(seconds=1), "current-active-attempt", purchase=current, attempt="active-attempt"),
    ]
    result = _history_score(frequency_cases, supplied, events)
    for feature in result.features:
        purchase = feature.raw.unit == "PURCHASE_COUNT"
        assert feature.raw.value == (1 if purchase else 3)
        assert feature.windows[0].result.score.value == (100 / 3 if purchase else 100)


@pytest.mark.parametrize("name", FREQUENCY_FEATURES)
def test_each_frequency_window_includes_start_and_excludes_evaluation(frequency_cases, name):
    supplied = frequency_cases.request()
    at = supplied.cold.request.evaluation.evaluated_at
    lower = at - timedelta(seconds=FEATURE_SPECS[name][1])
    attempt = "prior-attempt" if name.startswith("attempt") else None
    for occurred, expected in ((lower - timedelta(microseconds=1), 0), (lower, 1),
                               (at - timedelta(microseconds=1), 1), (at, 0)):
        result = _history_score(frequency_cases, supplied, [_event(occurred, "edge", attempt=attempt)])
        feature = frequency_cases.feature(result, name)
        assert feature.raw.value == expected
        assert feature.windows[0].result.score.value == (100 / 3 if expected else 0)


def test_partial_coverage_and_uncollected_attempts_preserve_missingness(frequency_cases):
    supplied = frequency_cases.request()
    at = supplied.cold.request.evaluation.evaluated_at
    result = _history_score(frequency_cases, supplied, [], purchases={"status": "COLLECTED",
        "complete_from": at - timedelta(minutes=10), "complete_until": at}, attempts={"status": "NOT_COLLECTED"})
    assert result.features[0].raw.value == 0 and result.features[0].windows[0].result.score.value == 0
    assert all(f.windows[0].diagnostic == "INPUT_INSUFFICIENT_HISTORY" for f in result.features[1:3])
    assert all(f.windows[0].diagnostic == "INPUT_NOT_COLLECTED" for f in result.features[3:])
    assert all(f.windows[0].result.score is None for f in result.features[1:])


def test_coverage_end_before_evaluation_does_not_fabricate_zero(frequency_cases):
    supplied = frequency_cases.request()
    at = supplied.cold.request.evaluation.evaluated_at
    result = _history_score(frequency_cases, supplied, [], purchases={"status": "COLLECTED",
        "complete_from": at - timedelta(days=1), "complete_until": at - timedelta(microseconds=1)})
    assert all(f.windows[0].diagnostic == "INPUT_INSUFFICIENT_HISTORY" for f in result.features[:3])
    assert all(f.windows[0].result.score.value == 0 for f in result.features[3:])


def test_event_availability_and_currency_filters_reach_frequency_scores(frequency_cases):
    supplied = frequency_cases.request()
    at = supplied.cold.request.evaluation.evaluated_at
    occurred = at - timedelta(minutes=5)
    result = _history_score(frequency_cases, supplied, [
        _event(occurred, "known", purchase="known", available=at),
        _event(occurred, "not-known", purchase="not-known", available=at + timedelta(microseconds=1)),
        _event(occurred, "other-currency", purchase="usd", currency="USD"),
    ])
    assert all(f.raw.value == 1 and f.windows[0].result.score.value == 100 / 3 for f in result.features[:3])
    assert all(f.raw.value == 0 and f.windows[0].result.score.value == 0 for f in result.features[3:])
