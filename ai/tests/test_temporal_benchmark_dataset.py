"""Chronological selection and evaluator separation (S15P21A207-356)."""

from copy import deepcopy
from datetime import timedelta
import hashlib
import json
from pathlib import Path
import random

import pytest
from pydantic import ValidationError

from copenhagen_fds.synthetic.generator import generate
from copenhagen_fds.synthetic.learning import OBSERVABLE_TABLES
from copenhagen_fds.synthetic.storage import canonical
from copenhagen_fds.temporal_benchmark import dataset
from copenhagen_fds.temporal_benchmark.dataset import build_dataset, choose_recipe, select_purchases
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan


@pytest.fixture(scope="module")
def plan():
    settings = Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json"
    return BenchmarkPlan.model_validate({
        "settings": json.loads(settings.read_text(encoding="utf-8")),
        "simulation": {"users": 12, "days": 12, "train_days": 4, "validation_days": 4},
        "purchase_limit": 30,
    })


@pytest.fixture(scope="module")
def built(plan):
    return build_dataset(plan)


def purchase(pid, occurred, available=None):
    return {"purchase_request_id": pid, "occurred_at": occurred.isoformat(),
            "available_at": (available or occurred).isoformat()}


def test_selection_uses_arrival_order_and_id_tiebreak_before_applying_limit(plan):
    start, _ = plan.interval()
    rows = [purchase("early-occurrence-late-arrival", start, start + timedelta(hours=3)),
            purchase("b", start + timedelta(hours=1)),
            purchase("a", start + timedelta(hours=1)),
            purchase("last", start + timedelta(hours=4))]
    limited = BenchmarkPlan.model_validate({**plan.model_dump(), "purchase_limit": 2})
    assert select_purchases({"purchases": rows}, limited) == ("a", "b")
    assert select_purchases({"purchases": rows[::-1]}, limited) == ("a", "b")


def test_selection_excludes_purchases_and_arrivals_outside_half_open_interval(plan):
    start, end = plan.interval()
    rows = [purchase("start", start), purchase("last", end - timedelta(microseconds=1)),
            purchase("previous-split", start - timedelta(seconds=1), start),
            purchase("too-late", start, end), purchase("next-split", end)]
    assert select_purchases({"purchases": rows}, plan) == ("start", "last")


def test_selection_deduplicates_identical_purchase_but_rejects_conflicting_identity(plan):
    start, _ = plan.interval()
    row = purchase("same", start)
    assert select_purchases({"purchases": [row, deepcopy(row)]}, plan) == ("same",)
    conflict = {**row, "available_at": (start + timedelta(seconds=1)).isoformat()}
    with pytest.raises(ValueError, match="conflicting visible purchase identity"):
        select_purchases({"purchases": [row, conflict]}, plan)


def test_selection_rejects_arrival_before_occurrence_and_timezone_free_dates(plan):
    start, _ = plan.interval()
    with pytest.raises(ValueError, match="availability cannot precede"):
        select_purchases({"purchases": [purchase("bad-time", start + timedelta(seconds=1), start)]}, plan)
    row = purchase("naive", start)
    row["available_at"] = "2026-01-09T00:00:00"
    with pytest.raises(ValueError, match="timezone"):
        select_purchases({"purchases": [row]}, plan)


def test_selection_does_not_read_labels_outcomes_or_scenarios(plan):
    start, _ = plan.interval()
    rows = [purchase("a", start), purchase("b", start + timedelta(hours=1))]
    class Forbidden:
        def __iter__(self):
            pytest.fail("selection must not inspect evaluator or outcome tables")
    observations = {"purchases": rows, **{name: Forbidden() for name in ("truth", "personas", "outcomes", "feedback")}}
    assert select_purchases(observations, plan) == ("a", "b")


def test_dataset_is_reproducible_without_mutating_global_random_state(plan, built):
    before = random.getstate()
    assert build_dataset(plan) == built
    assert random.getstate() == before


def test_hidden_truth_is_separate_and_only_selected_targets_are_exported(plan, built):
    observations, evaluator = built
    assert set(observations) == set(OBSERVABLE_TABLES)
    assert set(observations).isdisjoint(evaluator)
    forbidden = {"is_fraud", "archetype", "scenario", "expected_observability", "behavioral_deviation_axes"}
    assert all(forbidden.isdisjoint(row) for rows in observations.values() for row in rows)
    assert set(evaluator["selected_purchase_ids"]) == {t["purchase_request_id"] for t in evaluator["truth"]}
    assert tuple(evaluator["selected_purchase_ids"]) == select_purchases(observations, plan)
    assert len(evaluator["truth"]) == plan.purchase_limit
    assert len(observations["purchases"]) > plan.purchase_limit
    assert evaluator["label_origin"] == "SYNTHETIC_GENERATOR_CAUSE_NOT_MODEL_PREDICTION"


def test_outputs_do_not_alias_generator_records_or_each_other(plan, monkeypatch):
    raw = generate(plan.simulation.generator_config(), suite=plan.suite)
    original = deepcopy(raw)
    monkeypatch.setattr(dataset, "generate", lambda *args, **kwargs: raw)
    observations, evaluator = build_dataset(plan)
    observations["purchases"][0]["amount_minor"] = -999
    evaluator["truth"][0]["is_fraud"] = "changed"
    evaluator["personas"][0]["amount_median_minor"] = -999
    assert raw == original


def test_amount_variant_preserves_identity_truth_and_merchant_totals_with_new_hashes(plan, built):
    base_observations, base_evaluator = built
    altered = BenchmarkPlan.model_validate({**plan.model_dump(), "distribution_variant": "DOUBLE_AMOUNTS"})
    observations, evaluator = build_dataset(altered)
    assert evaluator["truth"] == base_evaluator["truth"]
    assert evaluator["selected_purchase_ids"] == base_evaluator["selected_purchase_ids"]
    for base, varied in zip(base_observations["purchases"], observations["purchases"], strict=True):
        assert base["purchase_request_id"] == varied["purchase_request_id"]
        assert varied["amount_minor"] == base["amount_minor"] * 2
        assert sum(m["amount_minor"] for m in varied["merchant_orders"]) == varied["amount_minor"]
        assert [m["amount_minor"] for m in varied["merchant_orders"]] == [m["amount_minor"] * 2 for m in base["merchant_orders"]]
        snapshot = {key: varied[key] for key in ("merchant_orders", "amount_minor", "currency")}
        assert varied["snapshot_hash"] == hashlib.sha256(canonical(snapshot).encode()).hexdigest()
        assert varied["snapshot_hash"] != base["snapshot_hash"]
        assert varied["order_version"] != base["order_version"]
    for base, varied in zip(base_evaluator["personas"], evaluator["personas"], strict=True):
        assert varied["amount_median_minor"] == base["amount_median_minor"] * 2


def test_semantic_assignment_changes_only_semantics_not_behavior_or_selected_targets(plan, built):
    changed = BenchmarkPlan.model_validate({**plan.model_dump(), "semantic_seed": plan.semantic_seed + 1})
    assert build_dataset(changed) == built
    ids = built[1]["selected_purchase_ids"]
    original = {pid: choose_recipe(pid, plan.semantic_seed) for pid in ids}
    assert original == {pid: choose_recipe(pid, plan.semantic_seed) for pid in reversed(ids)}
    assert original != {pid: choose_recipe(pid, changed.semantic_seed) for pid in ids}


def test_label_changes_do_not_change_observations_selection_or_semantic_recipe(plan, monkeypatch, built):
    raw = generate(plan.simulation.generator_config(), suite=plan.suite)
    feedback_ids = {row["purchase_request_id"] for row in raw["feedback"]}
    changed_ids = set()
    for row in raw["truth"]:
        if row["purchase_request_id"] not in feedback_ids:
            row["is_fraud"] = not row["is_fraud"]
            row["scenario"] = "independent-evaluator-change"
            changed_ids.add(row["purchase_request_id"])
    assert changed_ids.intersection(built[1]["selected_purchase_ids"])
    monkeypatch.setattr(dataset, "generate", lambda *args, **kwargs: raw)
    observations, evaluator = build_dataset(plan)
    assert observations == built[0]
    assert evaluator["selected_purchase_ids"] == built[1]["selected_purchase_ids"]
    assert evaluator["truth"] != built[1]["truth"]
    assert [choose_recipe(pid, plan.semantic_seed) for pid in evaluator["selected_purchase_ids"]] == [
        choose_recipe(pid, plan.semantic_seed) for pid in built[1]["selected_purchase_ids"]]


def test_corrupted_model_copy_is_revalidated_before_generation(plan):
    corrupt = plan.model_copy(update={"purchase_limit": False})
    with pytest.raises(ValidationError):
        build_dataset(corrupt)


def test_dataset_audit_rejects_corrupt_snapshot_instead_of_exporting_it(plan, monkeypatch):
    raw = generate(plan.simulation.generator_config(), suite=plan.suite)
    raw["purchases"][0]["snapshot_hash"] = "0" * 64
    monkeypatch.setattr(dataset, "generate", lambda *args, **kwargs: raw)
    with pytest.raises(ValueError, match="snapshot content hash mismatch"):
        build_dataset(plan)
