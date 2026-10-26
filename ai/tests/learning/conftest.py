"""Explicit observable purchase fixtures for population learning and export tests."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.population_profiles import PopulationProfileRequest
from copenhagen_fds.population_profiles import build_population_profile


CUTOFF = datetime.fromisoformat("2026-05-01T00:00:00+09:00")
KST = timezone(timedelta(hours=9))


def _sample(pid, *, user="a", currency="KRW", amount=100, counts=0, at=None, status="INCLUDE", missing=None):
    occurred = at or CUTOFF - timedelta(days=1)
    evaluated = occurred + timedelta(microseconds=1)
    reference = {"snapshot_id": f"snapshot-{pid}", "version": "v1", "snapshot_hash": "a" * 64}
    values = {"amount_minor": amount, "kst_hour": occurred.astimezone(KST).hour,
              "kst_day_of_week": occurred.astimezone(KST).weekday()}
    features = [{"feature_id": name, "unit": unit, "window_seconds": seconds,
                 "currency": currency if seconds or name == "amount_minor" else None,
                 "status": (missing or {}).get(name, "AVAILABLE"),
                 "value": None if name in (missing or {}) else values.get(name, counts)}
                for name, (unit, seconds) in FEATURE_SPECS.items()]
    coverage = {"status": "COLLECTED", "complete_from": occurred - timedelta(days=1), "complete_until": evaluated}
    return LearningSample.model_validate({
        "origin": "SYNTHETIC", "source_reference": "observed-fixture",
        "purchase_occurred_at": occurred, "purchase_available_at": occurred,
        "raw_features": {"target": {"evaluation_id": f"eval-{pid}", "user_id": user,
            "purchase_request_id": pid, "order": reference, "intent_version": None},
            "evaluated_at": evaluated, "features": features},
        "eligibility": {"target": {"user_id": user, "purchase_request_id": pid, "order": reference, "currency": currency},
            "as_of": CUTOFF, "status": status,
            "reason": {"INCLUDE": "NORMAL_CONFIRMED", "DEFER": "UNCONFIRMED", "EXCLUDE": "FRAUD_CONFIRMED"}[status],
            "observed_purchase": True, "feedback_id": f"review-{pid}" if status != "DEFER" else None,
            "learning_amount": {"amount_minor": amount, "currency": currency} if status == "INCLUDE" else None},
        "purchase_coverage": coverage, "attempt_coverage": coverage,
    })


def _request(*, users=("a", "b", "empty"), cohort_id="cohort", currency="KRW", **changes):
    data = {"cohort": {"cohort_id": cohort_id, "membership_version": "members-v1", "definition": "Explicit test users",
                       "source_reference": "test-enrollment", "available_at": CUTOFF - timedelta(days=100),
                       "member_user_ids": users, "currency": currency},
            "reference": {"scope": "POPULATION", "cohort_id": cohort_id, "profile_id": "population-" + cohort_id, "version": "v1"},
            "state_version": "state-v1", "origin": "SYNTHETIC", "source_reference": "observed-fixture",
            "as_of": CUTOFF, "generated_at": CUTOFF + timedelta(seconds=1), "valid_until": CUTOFF + timedelta(days=1),
            "config": {"min_samples": 2, "min_users": 2}}
    data.update(changes)
    return PopulationProfileRequest.model_validate(data)


@pytest.fixture
def population():
    def fit(*samples, **changes):
        return build_population_profile(_request(**changes), samples)

    def feature(snapshot, name, window="long_term"):
        return next(item for item in getattr(snapshot, window).features if item.feature_id == name)

    return SimpleNamespace(sample=_sample, request=_request, fit=fit, feature=feature, cutoff=CUTOFF)


@pytest.fixture
def learning_payload():
    reference = {"snapshot_id": "snapshot-1", "version": "v1", "snapshot_hash": "a" * 64}
    target = {"user_id": "user-1", "purchase_request_id": "purchase-1",
              "order": reference, "currency": "KRW"}
    events, orders, outcomes = [], [], []
    for index, amount in enumerate((6000, 4000), start=1):
        money = {"amount_minor": amount, "currency": "KRW"}
        order_id = f"order-{index}"
        orders.append({"order_id": order_id, "merchant_id": f"merchant-{index}",
                       "items": [{"item_id": f"item-{index}", "name": "Synthetic purchase",
                                  "quantity": 1, "unit_price": money, "line_total": money}],
                       "total": money})
        base = {"user_id": "user-1", "purchase_request_id": "purchase-1", "order_id": order_id,
                "currency": "KRW", "occurred_at": "2026-09-10T10:00:00+09:00",
                "available_at": "2026-09-10T10:00:00+09:00"}
        events.append({**base, "event_id": f"purchase-event-{index}",
                       "kind": "PURCHASE_REQUESTED", "attempt_id": None})
        events.append({**base, "event_id": f"attempt-event-{index}",
                       "kind": "PAYMENT_ATTEMPTED", "attempt_id": f"attempt-{index}",
                       "occurred_at": f"2026-09-10T10:00:0{index}+09:00",
                       "available_at": f"2026-09-10T10:00:0{index}+09:00"})
        outcomes.append({"outcome_id": f"outcome-{index}", "target": deepcopy(target),
                         "attempt_id": f"attempt-{index}", "status": "FAILED" if index == 1 else "SUCCEEDED",
                         "occurred_at": "2026-09-10T10:00:10+09:00",
                         "available_at": "2026-09-10T10:00:11+09:00"})
    return deepcopy({
        "user_id": "user-1", "currency": "KRW", "as_of": "2026-09-10T11:00:00+09:00",
        "requested_at": "2026-09-10T11:00:01+09:00", "current_purchase_request_id": None,
        "purchase_coverage": {"status": "COLLECTED", "complete_from": "2026-09-10T00:00:00+09:00",
                              "complete_until": "2026-09-10T11:00:00+09:00"},
        "attempt_coverage": {"status": "COLLECTED", "complete_from": "2026-09-10T00:00:00+09:00",
                             "complete_until": "2026-09-10T11:00:00+09:00"},
        "purchase": {
            "user_id": "user-1", "order": {"purchase_request_id": "purchase-1", "reference": reference,
                "orders": orders, "total": {"amount_minor": 10000, "currency": "KRW"}},
            "occurred_at": "2026-09-10T10:00:00+09:00", "available_at": "2026-09-10T10:00:00+09:00",
            "evaluated_at": "2026-09-10T10:00:00.000001+09:00", "events": events, "outcomes": outcomes,
            "feedback": {"feedback_id": "feedback-1", "target": target,
                         "classification": "NORMAL_CONFIRMED", "source": "SYNTHETIC_REVIEW",
                         "verification": "VERIFIED", "occurred_at": "2026-09-10T10:02:00+09:00",
                         "available_at": "2026-09-10T10:03:00+09:00"},
        },
    })


@pytest.fixture
def state_payload():
    return {
        "state_version": "ledger-v2", "user_id": "user-1", "currency": "KRW",
        "as_of": "2026-09-10T11:00:00+09:00",
        "profile": {
            "reference": {"profile_id": "profile-1", "version": "profile-v1", "scope": "PERSONAL", "user_id": "user-1"},
            "origin": "SYNTHETIC", "source_reference": "fds-behavior-v1",
            "feature_schema_version": "cold-features-v1", "currency": "KRW",
            "as_of": "2026-09-10T09:00:00+09:00", "generated_at": "2026-09-10T09:00:01+09:00",
            "valid_until": "2026-09-10T10:00:00+09:00", "observations": [],
        },
        "decisions": [{
            "target": {"user_id": "user-1", "purchase_request_id": "purchase-1", "currency": "KRW",
                       "order": {"snapshot_id": "snapshot-1", "version": "v1", "snapshot_hash": "a" * 64}},
            "as_of": "2026-09-10T11:00:00+09:00", "status": "DEFER", "reason": "UNCONFIRMED",
            "observed_purchase": True, "observed_attempt_ids": ["attempt-1", "attempt-2"],
        }],
    }
