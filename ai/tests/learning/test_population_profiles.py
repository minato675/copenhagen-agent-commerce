"""Cohort isolation, observable support, frequent users and deterministic population state."""

from datetime import timedelta, timezone

import pytest

from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.population_profiles import PopulationProfileSnapshot
from copenhagen_fds.population_profiles import build_population_profile


def test_cohort_currency_and_eligibility_are_isolated(population):
    p = population
    samples = (p.sample("a"), p.sample("b", user="b", amount=300), p.sample("other", user="outsider", amount=99999),
               p.sample("usd", currency="USD", amount=80000), p.sample("fraud", amount=77777, status="EXCLUDE"),
               p.sample("wait", amount=66666, status="DEFER"))
    result = p.fit(*samples)
    assert p.feature(result, "amount_minor").numeric.mean == 200
    assert (result.long_term.included_count, result.long_term.deferred_count, result.long_term.excluded_count) == (2, 1, 1)
    assert result.cohort.member_user_ids == ("a", "b", "empty")
    assert result.long_term.members[-1].included_count == 0
    usd = p.fit(*samples, currency="USD")
    assert p.feature(usd, "amount_minor").numeric.mean == 80000
    other = p.fit(*samples, users=("outsider",), cohort_id="other-cohort")
    assert p.feature(other, "amount_minor").numeric.mean == 99999


def test_frequent_user_weight_and_replication_effect_are_explainable(population):
    p = population
    frequent = tuple(p.sample(f"a-{i}") for i in range(9))
    rare = p.sample("b", user="b", amount=1000)
    balanced = p.fit(*frequent, rare)
    pooled = p.fit(*frequent, rare, config={"aggregation": "TRANSACTION", "min_samples": 2, "min_users": 2})
    assert p.feature(balanced, "amount_minor").numeric.mean == 550
    assert p.feature(pooled, "amount_minor").numeric.mean == 190
    assert [item.total_weight for item in p.feature(balanced, "amount_minor").contributors] == [.5, .5]
    assert [item.total_weight for item in p.feature(pooled, "amount_minor").contributors] == [.9, .1]
    assert [(x.count, x.probability) for x in p.feature(balanced, "amount_minor").histogram] == [(9, .5), (1, .5)]
    assert p.feature(balanced, "amount_minor").weight_effective_sample_size == pytest.approx(3.6)
    more = p.fit(*frequent, *(p.sample(f"more-{i}") for i in range(9)), rare)
    assert p.feature(more, "amount_minor").numeric == p.feature(balanced, "amount_minor").numeric
    without_frequent = p.fit(rare)
    assert p.feature(without_frequent, "amount_minor").numeric.mean == 1000
    assert without_frequent.long_term.status == "INSUFFICIENT_SAMPLES"


def test_feature_missingness_renormalizes_only_observed_users(population):
    p = population
    missing = {"attempt_count_1h": "NOT_COLLECTED", "purchase_count_24h": "INSUFFICIENT_HISTORY"}
    result = p.fit(p.sample("a", missing=missing), p.sample("b", user="b", counts=4))
    amount = p.feature(result, "amount_minor")
    attempts = p.feature(result, "attempt_count_1h")
    assert amount.status == "READY" and attempts.status == "INSUFFICIENT_SAMPLES"
    assert attempts.numeric.mean == 4 and attempts.not_collected_count == 1
    assert [(x.user_id, x.total_weight) for x in attempts.contributors] == [("b", 1)]
    assert p.feature(result, "purchase_count_24h").insufficient_history_count == 1


def test_windows_support_provenance_and_circular_midnight_are_preserved(population):
    p = population
    samples = (p.sample("outside", at=p.cutoff - timedelta(days=90, microseconds=1)),
               p.sample("long-edge", at=p.cutoff - timedelta(days=90)),
               p.sample("recent-edge", user="b", at=p.cutoff - timedelta(days=7)),
               p.sample("midnight", at=p.cutoff - timedelta(days=1)),
               p.sample("before-midnight", user="b", at=p.cutoff - timedelta(days=1, hours=1)))
    result = p.fit(*samples)
    assert (result.long_term.retained_count, result.recent.retained_count) == (4, 3)
    assert p.feature(result, "amount_minor").observation.observed_from == p.cutoff - timedelta(days=90)
    assert p.feature(result, "amount_minor").recency_seconds == 86400
    assert result.profile.origin == "SYNTHETIC" and result.profile.source_reference == "observed-fixture"
    assert result.cohort.source_reference == "test-enrollment" and result.cohort.membership_version == "members-v1"
    assert result.profile.reference.version == "v1" and result.state.state_version == "state-v1"
    pair = p.fit(*samples[-2:])
    assert p.feature(pair, "kst_hour").circular.mean_position == pytest.approx(23.5)
    assert len(p.feature(pair, "kst_hour").histogram) == 24
    assert len(p.feature(pair, "kst_day_of_week").histogram) == 7


def test_empty_cohort_and_single_user_cannot_claim_population_support(population):
    p = population
    empty = p.fit()
    assert empty.long_term.status == "EMPTY" and len(empty.long_term.members) == 3
    assert all(f.weight_effective_sample_size == 0 and f.numeric is f.circular is None for f in empty.long_term.features)
    no_members = p.fit(p.sample("a"), users=())
    assert not no_members.state.retained_samples and no_members.long_term.status == "EMPTY"
    busy = p.fit(*(p.sample(str(i)) for i in range(50)))
    assert busy.long_term.status == "INSUFFICIENT_SAMPLES"
    assert p.feature(busy, "amount_minor").numeric.population_stddev == 0


def test_reproducible_membership_order_sample_order_duplicates_and_json(population):
    p = population
    a, b = p.sample("a"), p.sample("b", user="b", amount=300)
    original = p.fit(a, b)
    reordered = p.fit(b, a, a, users=("empty", "b", "a"), as_of=p.cutoff.astimezone(timezone.utc))
    assert reordered.model_dump_json() == original.model_dump_json()
    restored = PopulationProfileSnapshot.model_validate_json(original.model_dump_json())
    assert restored == original
    assert p.fit(*restored.state.retained_samples) == original
    assert p.fit(a, b, users=("a", "b")).cohort_sha256 != original.cohort_sha256


@pytest.mark.parametrize("mode", ["source", "cutoff", "conflict"])
def test_incompatible_observations_are_rejected(population, mode):
    p = population
    original = p.sample("a")
    data = original.model_dump(mode="python")
    if mode == "source":
        data["source_reference"] = "other"
    elif mode == "cutoff":
        data["eligibility"]["as_of"] += timedelta(seconds=1)
    else:
        data["eligibility"]["feedback_id"] = "different-review"
    with pytest.raises(ValueError):
        p.fit(original, LearningSample.model_validate(data))


@pytest.mark.parametrize("mode", ["duplicate-member", "future-membership", "reference", "zero-users"])
def test_invalid_cohort_reference_time_and_settings_are_rejected(population, mode):
    data = population.request().model_dump(mode="python")
    if mode == "duplicate-member":
        data["cohort"]["member_user_ids"] = ("a", "a")
    elif mode == "future-membership":
        data["cohort"]["available_at"] = population.cutoff + timedelta(seconds=1)
    elif mode == "reference":
        data["reference"]["cohort_id"] = "wrong"
    else:
        data["config"]["min_users"] = 0
    with pytest.raises(ValueError):
        type(population.request()).model_validate(data)


def test_snapshot_rejects_tampered_support_weights_and_references(population):
    result = population.fit(population.sample("a"), population.sample("b", user="b"))
    for mutate in (
        lambda d: d["cohort"].update(currency="USD"),
        lambda d: d["state"]["profile_reference"].update(version="wrong"),
        lambda d: d["long_term"]["members"][0].update(included_count=4),
        lambda d: d["long_term"]["features"][0]["contributors"][0].update(total_weight=.8),
        lambda d: d["long_term"]["features"][0]["histogram"][0].update(probability=.2),
        lambda d: d["long_term"]["features"][0].update(weight_effective_sample_size=99.0),
    ):
        data = result.model_dump(mode="python")
        mutate(data)
        with pytest.raises(ValueError):
            PopulationProfileSnapshot.model_validate(data)
