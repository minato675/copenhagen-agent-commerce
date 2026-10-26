"""Observable history replay through the real synthetic adapter and CLI."""

import json
from copy import deepcopy
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from copenhagen_fds.learning_replay import replay_learning_samples
from copenhagen_fds.synthetic.__main__ import main
from copenhagen_fds.synthetic.generator import GeneratorConfig
from copenhagen_fds.synthetic.learning import export_learning_samples, read_learning_observations, synthetic_learning_inputs
from copenhagen_fds.synthetic.storage import write_dataset


CUTOFF = "2026-09-10T11:00:00+09:00"


@pytest.fixture
def observations():
    data = {name: [] for name in ("purchases", "events", "outcomes", "feedback", "coverage_gaps")}
    data["users"] = [{"user_id": "u", "currency": "KRW", "registered_at": "2026-09-01T00:00:00+09:00",
                      "collection_started_at": "2026-09-01T00:00:00+09:00"}]
    for index, minute in enumerate((0, 5, 8), start=1):
        pid = f"p{index}"
        at = datetime.fromisoformat(f"2026-09-10T10:0{minute}:00+09:00")
        orders = [{"order_id": f"{pid}-o{n}", "merchant_id": f"m{n}", "amount_minor": 5000 * index} for n in range(2)]
        data["purchases"].append({"purchase_request_id": pid, "user_id": "u", "currency": "KRW",
            "amount_minor": 10000 * index, "occurred_at": at.isoformat(), "available_at": at.isoformat(),
            "order_snapshot_id": f"s{index}", "order_version": "v1", "snapshot_hash": str(index) * 64,
            "merchant_orders": orders})
        for order in orders:
            data["events"].append({"event_id": f"{order['order_id']}-event", "kind": "PURCHASE_REQUESTED",
                "user_id": "u", "purchase_request_id": pid, "currency": "KRW", "order_id": order["order_id"],
                "attempt_id": None, "occurred_at": at.isoformat(), "available_at": at.isoformat()})
        for number in range(2 if index == 1 else 1):
            attempt = f"{pid}-a{number}"
            attempted = at + timedelta(seconds=5 + 30 * number)
            data["events"].append({"event_id": f"{attempt}-event", "kind": "PAYMENT_ATTEMPTED",
                "user_id": "u", "purchase_request_id": pid, "currency": "KRW", "order_id": None,
                "attempt_id": attempt, "occurred_at": attempted.isoformat(), "available_at": attempted.isoformat()})
            data["outcomes"].append({"outcome_id": f"{attempt}-result", "user_id": "u", "purchase_request_id": pid,
                "attempt_id": attempt, "status": "FAILED" if number == 0 else "SUCCEEDED",
                "occurred_at": (attempted + timedelta(seconds=2)).isoformat(),
                "available_at": (attempted + timedelta(seconds=3)).isoformat()})
    data["feedback"] = [
        {"feedback_id": "r1", "user_id": "u", "purchase_request_id": "p1", "classification": "FRAUD_CONFIRMED",
         "source": "SYNTHETIC_REVIEW", "occurred_at": "2026-09-10T10:06:00+09:00", "available_at": "2026-09-10T10:06:00+09:00"},
        {"feedback_id": "r2", "user_id": "u", "purchase_request_id": "p2", "classification": "NORMAL_CONFIRMED",
         "source": "SYNTHETIC_REVIEW", "occurred_at": "2026-09-10T10:10:00+09:00", "available_at": "2026-09-10T10:11:00+09:00"},
    ]
    return data


def replay(data, as_of=CUTOFF, **kwargs):
    inputs = synthetic_learning_inputs(data, as_of=as_of, **kwargs)
    return replay_learning_samples(inputs, origin="SYNTHETIC", source_reference="observable-test-source")


def feature(sample, name):
    return next(value for value in sample.raw_features.features if value.feature_id == name)


def test_ineligible_history_still_counts_and_merchant_amount_is_not_added_twice(observations):
    samples = replay(observations)
    assert [sample.eligibility.status for sample in samples] == ["EXCLUDE", "INCLUDE", "DEFER"]
    assert [feature(sample, "purchase_count_10m").value for sample in samples] == [0, 1, 2]
    assert [feature(sample, "attempt_count_10m").value for sample in samples] == [0, 2, 3]
    assert samples[1].eligibility.learning_amount.amount_minor == 20000
    assert [feature(sample, "kst_hour").value for sample in samples] == [10, 10, 10]


def test_duplicates_alias_attempts_and_input_order_do_not_change_samples(observations):
    expected = replay(observations)
    observations["events"].append(deepcopy(observations["events"][0]))
    attempt = next(row for row in observations["events"] if row["kind"] == "PAYMENT_ATTEMPTED")
    observations["events"].append({**attempt, "event_id": "alias-attempt-event"})
    for rows in observations.values():
        rows.reverse()
    assert replay(observations) == expected


def test_late_feedback_changes_eligibility_without_rewriting_historical_features(observations):
    before = replay(observations, "2026-09-10T10:09:00+09:00")
    after = replay(observations, "2026-09-10T10:11:00+09:00")
    assert before[1].eligibility.status == "DEFER"
    assert after[1].eligibility.status == "INCLUDE"
    assert [sample.raw_features for sample in before] == [sample.raw_features for sample in after]


def test_unavailable_conflicting_event_does_not_poison_the_past(observations):
    expected = replay(observations)
    observations["events"].append({**observations["events"][0], "currency": "USD", "available_at": "2026-09-10T12:00:00+09:00"})
    assert replay(observations) == expected
    with pytest.raises(ValueError, match="conflicting contents"):
        replay(observations, "2026-09-10T12:00:00+09:00")


def test_known_conflicting_attempt_identity_fails_without_inventing_a_count(observations):
    attempt = next(row for row in observations["events"] if row["kind"] == "PAYMENT_ATTEMPTED")
    observations["events"].append({**attempt, "event_id": "conflict", "order_id": "p1-o0"})
    with pytest.raises(ValueError, match="logical initiation"):
        replay(observations)


def test_late_event_is_counted_only_at_evaluations_that_knew_it(observations):
    for row in observations["events"]:
        if row["purchase_request_id"] == "p1" and row["kind"] == "PURCHASE_REQUESTED":
            row["available_at"] = "2026-09-10T10:06:00+09:00"
    samples = replay(observations)
    assert feature(samples[1], "purchase_count_10m").value == 0
    assert feature(samples[2], "purchase_count_10m").value == 2


def test_collection_gap_and_late_gap_notice_do_not_retroactively_invent_zero(observations):
    expected = replay(observations)
    gap = {"user_id": "u", "start": "2026-09-10T09:55:00+09:00", "end": "2026-09-10T10:03:00+09:00",
           "streams": ["purchases"], "available_at": "2026-09-10T11:30:00+09:00"}
    observations["coverage_gaps"] = [gap]
    assert replay(observations) == expected
    gap["available_at"] = gap["start"]
    value = feature(replay(observations)[1], "purchase_count_10m")
    assert value.status == "INSUFFICIENT_HISTORY" and value.value is None


def test_new_collection_start_does_not_claim_a_day_of_history(observations):
    observations["users"][0]["collection_started_at"] = "2026-09-10T09:55:00+09:00"
    sample = replay(observations)[0]
    assert feature(sample, "purchase_count_24h").value is None
    assert feature(sample, "purchase_count_24h").status == "INSUFFICIENT_HISTORY"


def test_current_purchase_and_cutoff_purchase_are_not_training_rows(observations):
    assert [sample.eligibility.target.purchase_request_id for sample in replay(observations, current_purchase_request_id="p2")] == ["p1", "p3"]
    assert len(replay(observations, "2026-09-10T10:05:00.000001+09:00")) == 1


def test_late_old_review_does_not_override_a_newer_known_review(observations):
    observations["feedback"].append({**observations["feedback"][1], "feedback_id": "older-review",
        "classification": "SUSPECTED", "occurred_at": "2026-09-10T10:09:00+09:00", "available_at": "2026-09-10T10:59:00+09:00"})
    assert replay(observations)[1].eligibility.status == "INCLUDE"
    observations["feedback"][-1]["occurred_at"] = "2026-09-10T10:10:00+09:00"
    with pytest.raises(ValueError, match="simultaneous follow-up"):
        replay(observations)


def test_private_truth_objects_are_not_consulted_or_serialized(observations):
    expected = replay(observations)
    observations.update(truth=object(), personas=object(), scenario=object())
    assert replay(observations) == expected
    serialized = expected[0].model_dump_json()
    assert all(f'"{key}"' not in serialized for key in ("is_fraud", "scenario", "persona", "archetype"))


@pytest.fixture(scope="module")
def frozen_set(tmp_path_factory):
    path = tmp_path_factory.mktemp("learning-source") / "set"
    write_dataset(path, GeneratorConfig(users=12, days=12, train_days=4, validation_days=4), suite="stream")
    return path


def test_observable_only_loader_and_repeated_exports_are_identical(frozen_set, tmp_path, monkeypatch):
    read_text, read_bytes = Path.read_text, Path.read_bytes
    def deny_private(original):
        def read(path, *args, **kwargs):
            assert path.name not in {"truth.jsonl", "personas.jsonl", "truth.csv"}
            return original(path, *args, **kwargs)
        return read
    monkeypatch.setattr(Path, "read_text", deny_private(read_text))
    monkeypatch.setattr(Path, "read_bytes", deny_private(read_bytes))
    one = export_learning_samples(frozen_set, tmp_path / "one", as_of="2026-01-07T00:00:00+09:00")
    two = export_learning_samples(frozen_set, tmp_path / "two", as_of="2026-01-07T00:00:00+09:00")
    assert one == two and one["samples"] > 0
    assert (tmp_path / "one/learning_samples.jsonl").read_bytes() == (tmp_path / "two/learning_samples.jsonl").read_bytes()
    assert not one["profiles_fitted"] and not one["model_scored"]
    with pytest.raises(FileExistsError):
        export_learning_samples(frozen_set, tmp_path / "one", as_of="2026-01-07T00:00:00+09:00")


def test_cli_export_uses_chronological_limit(frozen_set, tmp_path, capsys):
    assert main(["export-learning", str(frozen_set), "--as-of", "2026-01-07T00:00:00+09:00", "--limit", "3", "--output", str(tmp_path / "out")]) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["samples"] == 3


def test_observable_hash_mismatch_fails_before_creating_output(frozen_set, tmp_path):
    manifest = json.loads((frozen_set / "manifest.json").read_text())
    manifest["files"]["users.jsonl"]["sha256"] = "0" * 64
    for path in frozen_set.iterdir():
        (tmp_path / path.name).write_bytes(path.read_bytes())
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="hash mismatch"):
        export_learning_samples(tmp_path, tmp_path / "out", as_of=CUTOFF)
    assert not (tmp_path / "out").exists()


@pytest.mark.parametrize("cutoff,limit", [("2026-01-07T00:00:00", None), (CUTOFF, 0)])
def test_invalid_replay_options_do_not_create_output(frozen_set, tmp_path, cutoff, limit):
    with pytest.raises(ValueError):
        export_learning_samples(frozen_set, tmp_path / "out", as_of=cutoff, limit=limit)
    assert not (tmp_path / "out").exists()
