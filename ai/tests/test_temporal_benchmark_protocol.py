"""Frozen evaluation conditions and invalid settings (S15P21A207-356)."""

from copy import deepcopy
from datetime import timedelta
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan, SimulationConfig


@pytest.fixture
def payload():
    settings = Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json"
    return {"settings": json.loads(settings.read_text(encoding="utf-8")),
            "simulation": {"users": 12, "days": 12, "train_days": 4, "validation_days": 4}}


@pytest.mark.parametrize("field,value", [
    ("seed", -1), ("seed", 2**64), ("seed", True), ("seed", "123"),
    ("users", 11), ("users", 1001), ("days", 11), ("days", 367),
    ("days", 12.0), ("train_days", 0), ("validation_days", 0),
    ("start", "2026-02-30"), ("start", "20260101"),
    ("start", "2026-01-01T00:00:00+09:00"), ("start", "9999-12-31"),
])
def test_simulation_rejects_invalid_bounds_and_coercion(payload, field, value):
    payload["simulation"][field] = value
    with pytest.raises(ValidationError):
        BenchmarkPlan.model_validate(payload)


@pytest.mark.parametrize("train_days,validation_days", [(8, 4), (9, 4)])
def test_split_requires_a_nonempty_final_period(payload, train_days, validation_days):
    payload["simulation"].update(train_days=train_days, validation_days=validation_days)
    with pytest.raises(ValidationError, match="nonempty chronological test period"):
        BenchmarkPlan.model_validate(payload)


@pytest.mark.parametrize("field,value", [
    ("purchase_limit", 0), ("purchase_limit", 10001), ("purchase_limit", True),
    ("semantic_seed", -1), ("semantic_seed", 2**64), ("semantic_seed", "123"),
    ("split", "train"), ("selection", "BY_FRAUD_LABEL"),
    ("data_role", "VERIFIED_UNTOUCHED_HOLDOUT"),
    ("threshold_tuning", "TEST_SET"), ("unknown_parameter", 1),
])
def test_plan_rejects_unsupported_or_ambiguous_experimental_choices(payload, field, value):
    payload[field] = value
    with pytest.raises(ValidationError):
        BenchmarkPlan.model_validate(payload)


def test_intervals_are_adjacent_timezone_aware_and_do_not_overlap(payload):
    test_plan = BenchmarkPlan.model_validate(payload)
    validation_plan = BenchmarkPlan.model_validate({**payload, "split": "validation"})
    validation_start, validation_end = validation_plan.interval()
    test_start, test_end = test_plan.interval()
    assert validation_start.isoformat() == "2026-01-05T00:00:00+09:00"
    assert validation_end == test_start
    assert test_start.isoformat() == "2026-01-09T00:00:00+09:00"
    assert test_end.isoformat() == "2026-01-13T00:00:00+09:00"
    assert test_end - test_start == timedelta(days=4)
    assert test_start.utcoffset() == timedelta(hours=9)


@pytest.mark.parametrize("seed,variant", [
    (20260910, "DOUBLE_AMOUNTS"), (20260911, "DOUBLE_AMOUNTS"), (20260916, "BASE"),
])
def test_candidate_holdout_requires_new_seed_and_distribution_change(payload, seed, variant):
    payload.update(data_role="CANDIDATE_HOLDOUT", distribution_variant=variant)
    payload["simulation"]["seed"] = seed
    with pytest.raises(ValidationError, match="new seed and declared distribution variation"):
        BenchmarkPlan.model_validate(payload)


def test_candidate_holdout_remains_a_candidate_and_json_roundtrip_is_exact(payload):
    payload.update(data_role="CANDIDATE_HOLDOUT", distribution_variant="DOUBLE_AMOUNTS")
    plan = BenchmarkPlan.model_validate(payload)
    assert plan.data_role == "CANDIDATE_HOLDOUT"
    assert BenchmarkPlan.model_validate_json(plan.model_dump_json()) == plan
    with pytest.raises(ValidationError, match="frozen"):
        plan.purchase_limit = 1


@pytest.mark.parametrize("field", ["available_at", "valid_from"])
def test_cold_policy_cannot_be_published_after_simulated_history(payload, field):
    payload["settings"]["cold_policy"][field] = "2026-01-02T00:00:00+09:00"
    with pytest.raises(ValidationError):
        BenchmarkPlan.model_validate(payload)


def test_cold_policy_can_be_absent_to_measure_missing_baseline(payload):
    payload["settings"]["cold_policy"] = None
    assert BenchmarkPlan.model_validate(payload).settings.cold_policy is None


def test_declared_settings_and_risk_policy_cannot_silently_coerce_or_normalize(payload):
    payload["settings"]["profile_valid_for_seconds"] = "604800"
    with pytest.raises(ValidationError):
        BenchmarkPlan.model_validate(payload)
    payload["settings"]["profile_valid_for_seconds"] = 604800
    plan = BenchmarkPlan.model_validate(payload)
    altered = deepcopy(plan.model_dump(mode="json"))
    altered["risk_policy"]["groups"][0]["weight"] = 0.99
    with pytest.raises(ValidationError, match="sum to one"):
        BenchmarkPlan.model_validate(altered)


def test_generator_config_preserves_all_frozen_simulation_choices(payload):
    simulation = SimulationConfig.model_validate(payload["simulation"])
    assert simulation.generator_config().__dict__ == simulation.model_dump()
