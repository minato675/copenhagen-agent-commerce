"""Checks on the simulation's evidence and replay properties, not FDS accuracy."""

from collections import Counter
from dataclasses import replace
from datetime import datetime
import hashlib
import json
import random
import statistics

import pytest

from copenhagen_fds.contracts.features import HistoryEvent
from copenhagen_fds.synthetic.generator import (
    ARCHETYPES, CHALLENGE_SCENARIOS, GeneratorConfig, generate,
)


@pytest.fixture(scope="module")
def config():
    return GeneratorConfig(users=24, days=45, train_days=20, validation_days=10)


@pytest.fixture(scope="module")
def stream(config):
    return generate(config)


@pytest.fixture(scope="module")
def challenge(config):
    return generate(config, suite="challenge")


def test_reproducible_without_touching_global_rng(config, stream):
    before = random.getstate()
    assert generate(config) == stream
    assert random.getstate() == before


def test_seed_and_suite_are_independent(config, stream, challenge):
    alternate = generate(replace(config, seed=config.seed + 1))
    assert alternate != stream
    assert {p["purchase_request_id"] for p in stream["purchases"]}.isdisjoint(
        p["purchase_request_id"] for p in challenge["purchases"]
    )


def test_hidden_labels_and_personas_are_separate(stream):
    assert all(set(user) == {"user_id", "currency", "registered_at", "collection_started_at"} for user in stream["users"])
    forbidden = {"is_fraud", "archetype", "scenario", "split", "expected_observability", "label_available_at"}
    for name in ("users", "purchases", "events", "outcomes"):
        assert all(forbidden.isdisjoint(record) for record in stream[name])
    assert {p["archetype"] for p in stream["personas"]} == set(ARCHETYPES)


def test_per_user_patterns_are_distinct(stream):
    by_user = {p["user_id"]: p for p in stream["personas"]}
    truth = {t["purchase_request_id"]: t for t in stream["truth"]}
    amounts, hours, counts = {}, {}, Counter()
    for purchase in stream["purchases"]:
        if truth[purchase["purchase_request_id"]]["is_fraud"]:
            continue
        archetype = by_user[purchase["user_id"]]["archetype"]
        amounts.setdefault(archetype, []).append(purchase["amount_minor"])
        hours.setdefault(archetype, []).append(datetime.fromisoformat(purchase["occurred_at"]).hour)
        counts[archetype] += 1
    assert set(amounts["constant"]) == {15000}
    assert statistics.median(amounts["high_spender"]) > 4 * statistics.median(amounts["routine"])
    assert sum(hour >= 22 or hour <= 2 for hour in hours["night"]) / len(hours["night"]) > 0.9
    assert any(hour <= 9 for hour in hours["multipeak"])
    assert any(hour >= 17 for hour in hours["multipeak"])
    assert counts["sparse"] < counts["routine"] / 3


def test_all_challenge_scenarios_have_explicit_targets(challenge):
    targets = [t for t in challenge["truth"] if t["benchmark_role"] == "challenge"]
    assert {t["scenario"] for t in targets} == set(CHALLENGE_SCENARIOS)
    assert all(t["split"] == "test" for t in targets)
    assert all(not t["is_fraud"] for t in targets if t["scenario"].startswith("normal_"))
    mimic = [t for t in targets if t["scenario"] == "pattern_mimic"]
    assert mimic and all(t["is_fraud"] and not t["behavioral_deviation_axes"] for t in mimic)
    assert all(t["expected_observability"] == "indistinguishable" for t in mimic)


def test_legitimate_exceptional_amount_can_confuse_a_behavior_only_model(challenge):
    target = next(t for t in challenge["truth"] if t["scenario"] == "normal_high_amount" and t["benchmark_role"] == "challenge")
    purchase = next(p for p in challenge["purchases"] if p["purchase_request_id"] == target["purchase_request_id"])
    persona = next(p for p in challenge["personas"] if p["user_id"] == purchase["user_id"])
    assert persona["archetype"] == "routine"
    assert purchase["amount_minor"] > 6 * persona["amount_median_minor"]
    assert target["is_fraud"] is False
    assert target["behavioral_deviation_axes"] == ["amount"]
    assert target["expected_observability"] == "legitimate_confuser"


def test_purchase_event_identity_amount_and_time_conservation(challenge):
    purchases = {p["purchase_request_id"]: p for p in challenge["purchases"]}
    users = {u["user_id"]: u for u in challenge["users"]}
    assert len(purchases) == len(challenge["purchases"])
    assert set(purchases) == {t["purchase_request_id"] for t in challenge["truth"]}
    for purchase in purchases.values():
        assert isinstance(purchase["order_version"], str)
        assert sum(order["amount_minor"] for order in purchase["merchant_orders"]) == purchase["amount_minor"]
        assert all(order["amount_minor"] > 0 for order in purchase["merchant_orders"])
        assert purchase["occurred_at"] >= users[purchase["user_id"]]["registered_at"]
        assert purchase["available_at"] >= purchase["occurred_at"]
        expected_hash = hashlib.sha256(json.dumps({
            "merchant_orders": purchase["merchant_orders"], "amount_minor": purchase["amount_minor"], "currency": "KRW",
        }, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        assert purchase["snapshot_hash"] == expected_hash
    seen = {}
    for event in challenge["events"]:
        HistoryEvent.model_validate(event)
        purchase = purchases[event["purchase_request_id"]]
        assert event["user_id"] == purchase["user_id"]
        assert event["currency"] == purchase["currency"]
        if event["event_id"] in seen:
            assert event == seen[event["event_id"]]
        seen[event["event_id"]] = event
        if event["order_id"] is not None:
            assert event["order_id"] in {order["order_id"] for order in purchase["merchant_orders"]}


def test_payment_success_does_not_supply_ground_truth(challenge):
    truth = {t["purchase_request_id"]: t for t in challenge["truth"]}
    successes = [o for o in challenge["outcomes"] if o["status"] == "SUCCEEDED"]
    assert any(truth[o["purchase_request_id"]]["is_fraud"] for o in successes)
    assert any(not truth[o["purchase_request_id"]]["is_fraud"] for o in successes)
    assert any(truth[o["purchase_request_id"]]["label_available_at"] is None for o in successes)
    purchases = {p["purchase_request_id"]: p for p in challenge["purchases"]}
    for review in challenge["feedback"]:
        target = truth[review["purchase_request_id"]]
        assert (review["classification"] == "FRAUD_CONFIRMED") == target["is_fraud"]
        assert review["available_at"] == target["label_available_at"]
        assert review["available_at"] >= review["occurred_at"] > purchases[review["purchase_request_id"]]["occurred_at"]


def test_bursts_retries_duplicates_and_missing_collection_are_real(challenge):
    by_scenario = {}
    for target in challenge["truth"]:
        if target["benchmark_role"] == "challenge":
            by_scenario.setdefault(target["scenario"], set()).add(target["purchase_request_id"])
    for scenario in ("frequency_burst", "normal_burst", "combined"):
        assert len(by_scenario[scenario]) == 8
    retry_events = [e for e in challenge["events"] if e["purchase_request_id"] in by_scenario["legitimate_retry"] and e["kind"] == "PAYMENT_ATTEMPTED"]
    assert len({e["attempt_id"] for e in retry_events}) == 4
    repeated = Counter(e["event_id"] for e in challenge["events"])
    assert any(count == 2 for count in repeated.values())
    assert challenge["coverage_gaps"]
    gap_users = {p["user_id"] for p in challenge["purchases"] if p["purchase_request_id"] in by_scenario["collection_gap"]}
    for gap in challenge["coverage_gaps"]:
        if gap["user_id"] in gap_users:
            assert not any(e["user_id"] == gap["user_id"] and gap["start"] <= e["occurred_at"] < gap["end"] for e in challenge["events"])
            assert any(p["user_id"] == gap["user_id"] and gap["start"] <= p["occurred_at"] < gap["end"] for p in challenge["purchases"])


def test_late_events_remain_replayable_and_have_known_coverage_gap(challenge):
    delayed = [t for t in challenge["truth"] if t["scenario"] == "delayed_event"]
    assert len(delayed) == 1
    events = [e for e in challenge["events"] if e["purchase_request_id"] == delayed[0]["purchase_request_id"]]
    assert events
    for event in events:
        assert event["available_at"] > event["occurred_at"]
        assert any(
            gap["user_id"] == event["user_id"] and gap["available_at"] <= event["occurred_at"]
            and gap["start"] <= event["occurred_at"] < event["available_at"] <= gap["end"]
            for gap in challenge["coverage_gaps"]
        )


def test_challenge_targets_have_separate_users(challenge):
    purchases = {p["purchase_request_id"]: p for p in challenge["purchases"]}
    scenarios_by_user = {}
    for target in challenge["truth"]:
        if target["benchmark_role"] == "challenge":
            user_id = purchases[target["purchase_request_id"]]["user_id"]
            scenarios_by_user.setdefault(user_id, set()).add(target["scenario"])
    assert len(scenarios_by_user) == len(CHALLENGE_SCENARIOS)
    assert all(len(scenarios) == 1 for scenarios in scenarios_by_user.values())


def test_stream_is_low_prevalence_and_not_balanced(stream):
    fraction = sum(t["is_fraud"] for t in stream["truth"]) / len(stream["truth"])
    assert 0 < fraction < 0.025
    assert all(t["benchmark_role"] == "background" for t in stream["truth"])


def test_chronological_splits_and_newcomers_have_no_prior_history(config, stream):
    users = {u["user_id"]: u for u in stream["users"]}
    truth = {t["purchase_request_id"]: t for t in stream["truth"]}
    for purchase in stream["purchases"]:
        day = (datetime.fromisoformat(purchase["occurred_at"]).date() - datetime.fromisoformat(config.start).date()).days
        expected = "train" if day < config.train_days else "validation" if day < config.train_days + config.validation_days else "test"
        assert truth[purchase["purchase_request_id"]]["split"] == expected
    newcomers = {p["user_id"] for p in stream["personas"] if p["archetype"] == "newcomer"}
    assert 0.15 <= len(newcomers) / len(users) <= 0.25
    assert all(truth[p["purchase_request_id"]]["split"] == "test" for p in stream["purchases"] if p["user_id"] in newcomers)


def test_collection_start_is_explicit_for_established_new_and_cloned_users(config, challenge):
    first = config.start + "T00:00:00+09:00"
    assert any(user["registered_at"] < first for user in challenge["users"])
    assert all(user["collection_started_at"] == max(first, user["registered_at"]) for user in challenge["users"])
    users = {user["user_id"]: user for user in challenge["users"]}
    assert all(purchase["occurred_at"] >= users[purchase["user_id"]]["collection_started_at"] for purchase in challenge["purchases"])


@pytest.mark.parametrize("field,value", [
    ("seed", True), ("seed", -1), ("seed", 2**64), ("users", False), ("users", 11),
    ("users", 12.0), ("days", 11), ("train_days", 0), ("validation_days", 0),
    ("train_days", 100), ("start", None), ("start", "20260101"),
    ("start", "2026-02-30"), ("start", "9999-12-30"), ("start", "0001-01-01"),
])
def test_invalid_config_is_rejected(field, value):
    with pytest.raises(ValueError):
        GeneratorConfig(**{field: value})


def test_invalid_generator_arguments(config):
    with pytest.raises(ValueError):
        generate({})
    with pytest.raises(ValueError):
        generate(config, suite="balanced")


def test_minimum_supported_challenge_still_covers_all_scenarios():
    data = generate(GeneratorConfig(users=12, days=12, train_days=6, validation_days=5), suite="challenge")
    probes = [t for t in data["truth"] if t["benchmark_role"] == "challenge"]
    assert {t["scenario"] for t in probes} == set(CHALLENGE_SCENARIOS)
    assert all(t["split"] == "test" for t in probes)
