"""Checks of frozen artifacts and observable-time projection, not model accuracy."""

import json
from copy import deepcopy
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.features import HistoryEvent
from copenhagen_fds.features import transform_features
from copenhagen_fds.synthetic.__main__ import export_inputs, main
from copenhagen_fds.synthetic.generator import GeneratorConfig, generate
from copenhagen_fds.synthetic.replay import ReplayIndex, instant
from copenhagen_fds.synthetic.storage import read_dataset, write_dataset


@pytest.fixture(scope="module")
def small_config():
    return GeneratorConfig(users=18, days=18, train_days=8, validation_days=5)


@pytest.fixture(scope="module")
def generated(small_config):
    return generate(small_config, suite="challenge")


@pytest.fixture
def sample():
    return {
        "users": [{"user_id": "u", "registered_at": "2026-01-01T00:00:00+09:00",
                   "collection_started_at": "2026-01-01T00:00:00+09:00", "currency": "KRW"}],
        "purchases": [{"purchase_request_id": "p", "user_id": "u", "currency": "KRW", "amount_minor": 10000,
                       "occurred_at": "2026-01-03T12:00:00+09:00", "available_at": "2026-01-03T12:00:00+09:00",
                       "order_snapshot_id": "s", "order_version": "v1", "snapshot_hash": "a" * 64,
                       "merchant_orders": [{"order_id": "o", "merchant_id": "m", "amount_minor": 10000}]}],
        "events": [], "coverage_gaps": [],
    }


def event(identity, occurred_at, *, available_at=None, kind="PURCHASE_REQUESTED", purchase="prior", attempt=None):
    return {"event_id": identity, "kind": kind, "user_id": "u", "purchase_request_id": purchase,
            "order_id": "o", "attempt_id": attempt, "currency": "KRW",
            "occurred_at": occurred_at, "available_at": available_at or occurred_at}


def raw(data, *, attempt_id=None):
    return {row.feature_id: row for row in transform_features(ReplayIndex(data).feature_input("p", attempt_id=attempt_id)).features}


def test_generated_events_and_targets_satisfy_existing_contracts(generated):
    for row in generated["events"]:
        HistoryEvent.model_validate(row)
    index = ReplayIndex(generated)
    targets = [row for row in generated["truth"] if row["benchmark_role"] == "challenge"]
    assert targets
    for target in targets:
        purchase = index.purchases[target["purchase_request_id"]]
        for supplied in index.evaluation_points(target["purchase_request_id"]):
            result = transform_features(supplied)
            assert len(result.features) == 9
            amount = next(row for row in result.features if row.feature_id == "amount_minor")
            assert amount.value == purchase["amount_minor"]
            assert all(row.occurred_at < supplied.evaluation.evaluated_at
                       and row.available_at <= supplied.history.as_of for row in supplied.history.events)


def test_projection_does_not_read_evaluator_truth_or_feedback(generated):
    observable = {name: generated[name] for name in ("users", "purchases", "events", "coverage_gaps")}
    private = deepcopy(observable)
    private.update(truth=object(), personas=object(), feedback=object(), outcomes=object())
    purchase_id = generated["purchases"][-1]["purchase_request_id"]
    expected = ReplayIndex(observable).feature_input(purchase_id).model_dump_json()
    assert ReplayIndex(private).feature_input(purchase_id).model_dump_json() == expected
    for key in ("is_fraud", "scenario", "archetype", "classification", "behavioral_deviation_axes", "split"):
        assert f'"{key}"' not in expected


@pytest.mark.parametrize("seconds", [600, 3600, 86400])
@pytest.mark.parametrize("offset,expected", [(-1, 0), (0, 1), (1, 1)])
def test_window_boundary_is_inclusive_and_excludes_older_events(sample, seconds, offset, expected):
    now = ReplayIndex(sample).feature_input("p").evaluation.evaluated_at
    when = now - timedelta(seconds=seconds) + timedelta(microseconds=offset)
    sample["events"] = [event("e", when.isoformat())]
    name = {600: "purchase_count_10m", 3600: "purchase_count_1h", 86400: "purchase_count_24h"}[seconds]
    assert raw(sample)[name].value == expected


def test_future_occurrence_and_not_yet_available_events_are_not_inputs(sample):
    now = ReplayIndex(sample).feature_input("p").evaluation.evaluated_at
    sample["events"] = [event("future", now.isoformat()),
                        event("delayed", (now - timedelta(minutes=1)).isoformat(),
                              available_at=(now + timedelta(minutes=1)).isoformat())]
    sample["coverage_gaps"] = [{"user_id": "u", "start": (now - timedelta(minutes=1)).isoformat(),
                                "end": (now + timedelta(minutes=1)).isoformat(),
                                "streams": ["purchases"], "available_at": (now - timedelta(minutes=1)).isoformat()}]
    assert not ReplayIndex(sample).feature_input("p").history.events
    value = raw(sample)["purchase_count_10m"]
    assert value.status == "INSUFFICIENT_HISTORY" and value.value is None


def test_empty_complete_history_is_zero(sample):
    assert raw(sample)["purchase_count_24h"].value == 0


def test_first_collection_day_does_not_invent_pre_dataset_history(sample):
    sample["users"][0]["collection_started_at"] = "2026-01-03T11:30:00+09:00"
    result = raw(sample)
    assert result["purchase_count_10m"].value == 0
    assert result["purchase_count_1h"].status == "INSUFFICIENT_HISTORY"
    assert result["purchase_count_24h"].value is None


def test_future_coverage_notice_is_not_read(sample):
    expected = ReplayIndex(sample).feature_input("p").model_dump_json()
    sample["coverage_gaps"] = [{"user_id": "u", "start": "2026-01-03T11:00:00+09:00",
                                "end": "2026-01-03T13:00:00+09:00", "streams": ["purchases"],
                                "available_at": "2026-01-03T13:00:00+09:00"}]
    assert ReplayIndex(sample).feature_input("p").model_dump_json() == expected


def test_duplicates_and_merchant_copies_do_not_inflate_purchase_counts(sample):
    one = event("e", "2026-01-03T11:55:00+09:00")
    sample["events"] = [one, dict(one), {**one, "event_id": "e2", "order_id": "other-order"},
                        event("current", "2026-01-03T12:00:00+09:00", purchase="p")]
    assert raw(sample)["purchase_count_10m"].value == 1


def test_previous_attempts_of_same_purchase_count_but_current_one_does_not(sample):
    sample["events"] = [event(str(i), f"2026-01-03T12:0{i}:00+09:00", kind="PAYMENT_ATTEMPTED",
                              purchase="p", attempt=f"a{i}") for i in range(3)]
    assert raw(sample, attempt_id="a2")["attempt_count_10m"].value == 2
    assert len(list(ReplayIndex(sample).evaluation_points("p"))) == 2


def test_multimerchant_amount_is_purchase_total_and_hour_uses_occurrence(sample):
    sample["purchases"][0]["merchant_orders"] = [{"order_id": "a", "merchant_id": "x", "amount_minor": 4000},
                                                   {"order_id": "b", "merchant_id": "y", "amount_minor": 6000}]
    sample["purchases"][0]["available_at"] = "2026-01-03T14:00:00+09:00"
    result = raw(sample)
    assert result["amount_minor"].value == 10000
    assert result["kst_hour"].value == 12


def test_export_separates_inputs_from_truth_and_never_scores(tmp_path, small_config):
    write_dataset(tmp_path / "set", small_config, suite="challenge")
    report = export_inputs(tmp_path / "set", tmp_path / "export", split="test", limit=100)
    assert report["model_scored"] is False
    inputs = (tmp_path / "export" / "feature_inputs.jsonl").read_text(encoding="utf-8").splitlines()
    targets = (tmp_path / "export" / "targets.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(inputs) == len(targets) == report["evaluation_points"]
    assert all("is_fraud" not in row and '"scenario"' not in row for row in inputs)
    assert all('"is_fraud"' in row for row in targets)
    assert read_dataset(tmp_path / "set")["purchases"]


def test_export_excludes_retry_points_at_next_split_boundary(tmp_path, small_config, monkeypatch):
    manifest = write_dataset(tmp_path / "set", small_config, suite="stream")
    upper = manifest["boundaries"]["validation_start"]
    original = ReplayIndex.evaluation_points

    def with_late_point(self, purchase_id):
        supplied = next(original(self, purchase_id))
        yield supplied
        changed = supplied.model_dump(mode="json")
        changed["evaluation"]["evaluated_at"] = upper
        yield type(supplied).model_validate(changed)

    monkeypatch.setattr(ReplayIndex, "evaluation_points", with_late_point)
    report = export_inputs(tmp_path / "set", tmp_path / "export", split="train", limit=20)
    assert report["outside_interval_points_skipped"] == report["selected_purchases"] == 20
    targets = [json.loads(line) for line in (tmp_path / "export" / "targets.jsonl").read_text().splitlines()]
    assert all(instant(row["evaluated_at"]) < instant(upper) for row in targets)
    assert all(row["purchase_split"] == row["evaluation_split"] == "train" for row in targets)


def test_cli_bad_path_redacts_details(tmp_path, capsys):
    assert main(["verify", str(tmp_path / "private-path")]) == 2
    captured = capsys.readouterr()
    assert not captured.out and "private-path" not in captured.err


@pytest.mark.parametrize("limit", [0, -1])
def test_export_rejects_invalid_limit_without_creating_output(tmp_path, limit):
    with pytest.raises(ValueError):
        export_inputs(tmp_path / "missing", tmp_path / "out", split="test", limit=limit)
    assert not (tmp_path / "out").exists()
