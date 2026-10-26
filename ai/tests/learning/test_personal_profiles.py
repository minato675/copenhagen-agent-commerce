"""Known numeric answers, distinct habits, and bounded personal profile state."""

import json
import math
from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest, PersonalProfileSnapshot
from copenhagen_fds.personal_profiles import build_personal_profile


CUTOFF = datetime.fromisoformat("2026-05-01T00:00:00+09:00")
KST = timezone(timedelta(hours=9))


def sample(pid, *, at=None, amount=10000, user="u", currency="KRW", status="INCLUDE", missing=None, counts=0):
    occurred = at or CUTOFF - timedelta(days=1)
    evaluated = occurred + timedelta(microseconds=1)
    reference = {"snapshot_id": f"snapshot-{pid}", "version": "v1", "snapshot_hash": "a" * 64}
    features = []
    for name, (unit, seconds) in FEATURE_SPECS.items():
        value = amount if name == "amount_minor" else (
            occurred.astimezone(KST).hour if name == "kst_hour" else (
                occurred.astimezone(KST).weekday() if name == "kst_day_of_week" else counts
            )
        )
        absent = (missing or {}).get(name)
        features.append({"feature_id": name, "unit": unit, "window_seconds": seconds,
                         "currency": currency if seconds or name == "amount_minor" else None,
                         "status": absent or "AVAILABLE", "value": None if absent else value})
    coverage = {"status": "COLLECTED", "complete_from": occurred - timedelta(days=1), "complete_until": evaluated}
    return LearningSample.model_validate({
        "origin": "SYNTHETIC", "source_reference": "observed-fixture",
        "purchase_occurred_at": occurred, "purchase_available_at": occurred,
        "raw_features": {"target": {"evaluation_id": f"eval-{pid}", "user_id": user,
            "purchase_request_id": pid, "order": reference, "intent_version": None},
            "evaluated_at": evaluated, "features": features},
        "eligibility": {"target": {"user_id": user, "purchase_request_id": pid, "order": reference, "currency": currency},
            "as_of": CUTOFF, "status": status, "reason": {"INCLUDE": "NORMAL_CONFIRMED", "DEFER": "UNCONFIRMED", "EXCLUDE": "FRAUD_CONFIRMED"}[status],
            "observed_purchase": True, "feedback_id": f"review-{pid}" if status != "DEFER" else None,
            "learning_amount": {"amount_minor": amount, "currency": currency} if status == "INCLUDE" else None},
        "purchase_coverage": coverage, "attempt_coverage": coverage,
    })


def request(**changes):
    payload = {"user_id": "u", "currency": "KRW", "state_version": "state-v1",
               "reference": {"scope": "PERSONAL", "user_id": "u", "profile_id": "personal-u-KRW", "version": "v1"},
               "origin": "SYNTHETIC", "source_reference": "observed-fixture", "as_of": CUTOFF,
               "generated_at": CUTOFF + timedelta(seconds=1), "valid_until": CUTOFF + timedelta(days=1)}
    payload.update(changes)
    return PersonalProfileRequest.model_validate(payload)


def fit(*samples, **changes):
    return build_personal_profile(request(**changes), samples)


def feature(snapshot, name, window="long_term"):
    return next(value for value in getattr(snapshot, window).features if value.feature_id == name)


def test_known_amount_statistics_and_frequency_distribution():
    result = fit(*(sample(str(i), amount=value, counts=i) for i, value in enumerate((10, 20, 30, 40, 100))))
    stats = feature(result, "amount_minor").numeric
    assert (stats.mean, stats.median, stats.q25, stats.q75, stats.q05, stats.q95) == (40, 30, 20, 40, 12, 88)
    assert stats.population_stddev == pytest.approx(math.sqrt(1000))
    assert stats.median_absolute_deviation == 10
    frequency = feature(result, "attempt_count_1h")
    assert [(item.value, item.count) for item in frequency.histogram] == [(0, 1), (1, 1), (2, 1), (3, 1), (4, 1)]
    assert frequency.numeric.mean == 2
    assert result.long_term.status == "READY"


def test_user_currency_and_eligibility_are_isolated():
    result = fit(sample("normal", amount=10), sample("high", amount=90000, user="other"),
                 sample("usd", amount=70000, currency="USD"), sample("defer", amount=80000, status="DEFER"),
                 sample("fraud", amount=60000, status="EXCLUDE"))
    assert feature(result, "amount_minor").numeric.mean == 10
    assert (result.long_term.included_count, result.long_term.deferred_count, result.long_term.excluded_count) == (1, 1, 1)
    assert len(result.state.retained_samples) == 3
    usd = fit(sample("normal", amount=10), sample("usd", amount=70000, currency="USD"), currency="USD")
    assert feature(usd, "amount_minor").numeric.mean == 70000


def test_high_night_weekend_and_multimodal_habits_remain_distinct():
    weekday = datetime.fromisoformat("2026-04-27T12:00:00+09:00")
    weekend = datetime.fromisoformat("2026-04-26T01:00:00+09:00")
    daytime = fit(*(sample(str(i), at=weekday, amount=1000) for i in range(5)))
    night = fit(*(sample(str(i), at=weekend, amount=100000) for i in range(5)))
    assert feature(daytime, "amount_minor").numeric.mean < feature(night, "amount_minor").numeric.mean
    assert feature(daytime, "kst_hour").histogram[12].count == 5
    assert feature(night, "kst_hour").histogram[1].count == 5
    assert feature(daytime, "kst_day_of_week").histogram[0].count == 5
    assert feature(night, "kst_day_of_week").histogram[6].count == 5
    multimodal = fit(sample("am", at=weekday.replace(hour=8)), sample("pm", at=weekday.replace(hour=20)))
    hours = feature(multimodal, "kst_hour")
    assert hours.histogram[8].count == hours.histogram[20].count == 1
    assert hours.circular.mean_position is None  # Opposite directions have no single mean.
    assert hours.numeric is None


@pytest.mark.parametrize("count,status", [(0, "EMPTY"), (1, "INSUFFICIENT_SAMPLES"), (4, "INSUFFICIENT_SAMPLES"), (5, "READY")])
def test_empty_sparse_and_constant_samples_return_defined_finite_results(count, status):
    result = fit(*(sample(str(i)) for i in range(count)))
    amount = feature(result, "amount_minor")
    assert amount.status == result.long_term.status == status
    assert amount.observation.valid_sample_count == count
    if count:
        assert amount.numeric.population_stddev == amount.numeric.median_absolute_deviation == 0
        assert amount.numeric.minimum == amount.numeric.maximum == 10000
    else:
        assert amount.numeric is amount.recency_seconds is None
        assert len(feature(result, "kst_hour").histogram) == 24
    assert "NaN" not in result.model_dump_json() and "Infinity" not in result.model_dump_json()


def test_midnight_and_week_boundary_use_circular_distance():
    sunday = datetime.fromisoformat("2026-04-26T23:00:00+09:00")
    monday = sunday + timedelta(hours=1)
    result = fit(sample("sun", at=sunday), sample("mon", at=monday))
    hours, days = feature(result, "kst_hour"), feature(result, "kst_day_of_week")
    assert hours.circular.mean_position == pytest.approx(23.5)
    assert hours.circular.concentration > .99
    assert days.circular.mean_position == pytest.approx(6.5)
    assert hours.histogram[0].count == hours.histogram[23].count == 1


def test_recent_long_term_boundaries_provenance_versions_and_recency():
    result = fit(sample("old-out", at=CUTOFF - timedelta(days=90, microseconds=1)),
                 sample("old-edge", at=CUTOFF - timedelta(days=90), amount=10),
                 sample("recent-out", at=CUTOFF - timedelta(days=7, microseconds=1), amount=20),
                 sample("recent-edge", at=CUTOFF - timedelta(days=7), amount=100))
    assert result.long_term.included_count == 3 and result.recent.included_count == 1
    assert feature(result, "amount_minor", "recent").numeric.mean == 100
    assert feature(result, "amount_minor").recency_seconds == 7 * 86400
    assert feature(result, "amount_minor").observation.observed_from == CUTOFF - timedelta(days=90)
    assert result.profile.reference.version == "v1" and result.state.state_version == "state-v1"
    assert result.profile.source_reference == "observed-fixture"
    assert result.profile.origin == "SYNTHETIC"
    assert result.profile.generated_at > result.profile.as_of


def test_missing_collection_is_excluded_per_feature_without_zero_imputation():
    missing = {"purchase_count_10m": "NOT_COLLECTED", "attempt_count_1h": "INSUFFICIENT_HISTORY",
               "attempt_count_24h": "NOT_APPLICABLE"}
    result = fit(sample("partial", missing=missing), sample("complete"))
    for name, status in missing.items():
        stats = feature(result, name)
        assert stats.observation.valid_sample_count == 1
        assert stats.unavailable_count == 1
        assert stats.numeric.mean == 0  # The known zero is still an observation.
    assert feature(result, "purchase_count_10m").not_collected_count == 1
    assert feature(result, "attempt_count_1h").insufficient_history_count == 1
    assert feature(result, "attempt_count_24h").not_applicable_count == 1
    assert feature(result, "amount_minor").observation.valid_sample_count == 2


def test_reproducible_order_duplicates_timezone_and_json_state_roundtrip():
    first, second = sample("a"), sample("b", amount=20000)
    altered = first.model_dump(mode="json")
    altered["raw_features"]["features"].reverse()
    altered["purchase_occurred_at"] = first.purchase_occurred_at.astimezone(timezone.utc).isoformat()
    expected = fit(first, second)
    result = fit(second, LearningSample.model_validate(altered), first)
    assert result.model_dump_json() == expected.model_dump_json()
    restored = PersonalProfileSnapshot.model_validate_json(result.model_dump_json())
    assert fit(*restored.state.retained_samples) == expected
    assert restored == result


@pytest.mark.parametrize("change", ["cutoff", "source", "conflict"])
def test_incompatible_learning_snapshots_are_rejected(change):
    first = sample("a")
    payload = first.model_dump(mode="json")
    if change == "cutoff":
        payload["eligibility"]["as_of"] = (CUTOFF + timedelta(seconds=1)).isoformat()
    elif change == "source":
        payload["source_reference"] = "another-source"
    else:
        payload["eligibility"]["feedback_id"] = "another-review"
    with pytest.raises(ValueError):
        fit(first, LearningSample.model_validate(payload))


@pytest.mark.parametrize("changes", [{"generated_at": CUTOFF - timedelta(seconds=1)},
                                    {"user_id": "wrong-user"}])
def test_invalid_profile_configuration_or_reference_is_rejected(changes):
    with pytest.raises(ValidationError):
        request(**changes)


def test_snapshot_rejects_wrong_counts_state_owner_and_nonfinite_values():
    snapshot = fit(sample("a"))
    for mutate in (
        lambda data: data["long_term"].update(included_count=2),
        lambda data: data["state"]["profile_reference"].update(version="wrong-version"),
        lambda data: data["long_term"]["features"][0]["numeric"].update(mean=float("inf")),
        lambda data: data["recent"]["features"][0].update(recency_seconds=1.0),
    ):
        data = json.loads(snapshot.model_dump_json())
        mutate(data)
        with pytest.raises(ValidationError):
            PersonalProfileSnapshot.model_validate(data)
