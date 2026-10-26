"""S15P21A207-357: preserve prepared raw duplicate evidence at purchase arrival."""

from copy import deepcopy
from datetime import timedelta
from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.native_cli.scenarios import observable_demo
from copenhagen_fds.synthetic.replay import ReplayIndex, instant
from copenhagen_fds.temporal_benchmark.profiles import ProfileTimeline
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan
from copenhagen_fds.temporal_benchmark.runner import build_request, feature_at_arrival
from copenhagen_fds.temporal_benchmark.semantic_cases import build_semantic_case


def test_duplicate_delivery_preserves_prepared_semantic_binding_and_profile_input():
    data = observable_demo()
    current = data["purchases"][-1]["purchase_request_id"]
    data["events"].append(deepcopy(data["events"][-1]))
    prepared = ReplayIndex(data).feature_input(current)
    semantic, _ = build_semantic_case(prepared, "MATCH")
    actual = feature_at_arrival(data, current)
    assert actual == prepared
    ids = [row.event_id for row in actual.history.events]
    assert ids.count(data["events"][-1]["event_id"]) == 2
    settings = PersonalReplaySettings.model_validate_json(
        (Path(__file__).parents[1] / "examples/personal-replay-settings.json").read_text(encoding="utf-8"))
    plan = BenchmarkPlan(settings=settings)
    publications = ProfileTimeline(data, settings, "duplicate-delivery-regression").before(actual)
    for profiles in (None, publications):
        request = build_request(plan, actual, semantic, profiles)
        assert request.analysis.target.purchase_request_id == current
    for publication in (publications.personal, publications.population):
        assert current not in {row.sample.eligibility.target.purchase_request_id for row in publication.state.observations}


def test_visible_conflicting_duplicate_is_rejected():
    data = observable_demo()
    event = deepcopy(data["events"][-1])
    event["order_id"] = "conflicting-order"
    data["events"].append(event)
    with pytest.raises(ValueError, match="conflicting contents"):
        feature_at_arrival(data, data["purchases"][-1]["purchase_request_id"])


def test_future_conflicting_delivery_does_not_change_current_input():
    data = observable_demo()
    current = data["purchases"][-1]["purchase_request_id"]
    expected = feature_at_arrival(data, current)
    event = {**data["events"][-1], "order_id": "future-change", "available_at": "2030-01-01T00:00:00+09:00"}
    data["events"].append(event)
    assert feature_at_arrival(data, current) == expected


def test_arrival_projection_includes_events_available_at_the_declared_evaluation_instant():
    data = observable_demo()
    purchase = data["purchases"][-1]
    arrival = instant(purchase["available_at"])
    data["events"].append({**data["events"][-1], "event_id": "just-available", "purchase_request_id": "prior-purchase",
        "occurred_at": (arrival - timedelta(minutes=5)).isoformat(),
        "available_at": (arrival + timedelta(microseconds=1)).isoformat()})
    expected = ReplayIndex(data).feature_input(purchase["purchase_request_id"])
    actual = feature_at_arrival(data, purchase["purchase_request_id"])
    assert actual == expected
    assert "just-available" in {event.event_id for event in actual.history.events}
