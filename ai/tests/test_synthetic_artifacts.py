"""Independent checks of content, reproducibility, and frozen artifact writes."""

import json
from copy import deepcopy
from dataclasses import replace

import pytest

from copenhagen_fds.synthetic.generator import GeneratorConfig, generate
from copenhagen_fds.synthetic.storage import audit_records, create_benchmark, verify_dataset, write_dataset


@pytest.fixture(scope="module")
def config():
    return GeneratorConfig(users=18, days=18, train_days=8, validation_days=5)


def test_frozen_files_reproduce_and_refuse_replacement(tmp_path, config):
    left = write_dataset(tmp_path / "left", config, suite="challenge")
    right = write_dataset(tmp_path / "right", config, suite="challenge")
    assert left == right
    assert verify_dataset(tmp_path / "left")["status"] == "VERIFIED"
    with pytest.raises(FileExistsError):
        write_dataset(tmp_path / "left", config, suite="challenge")


def test_tampered_data_fails_verification(tmp_path, config):
    write_dataset(tmp_path / "set", config, suite="stream")
    target = tmp_path / "set" / "purchases.jsonl"
    target.write_bytes(target.read_bytes() + b"{}\n")
    with pytest.raises(ValueError, match="checksum"):
        verify_dataset(tmp_path / "set")


def test_manifest_cannot_request_reading_unlisted_paths(tmp_path, config):
    manifest = write_dataset(tmp_path / "set", config, suite="stream")
    manifest["files"]["../unrelated"] = {"sha256": "a" * 64}
    (tmp_path / "set" / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="inventory"):
        verify_dataset(tmp_path / "set")


def test_maximum_valid_seed_builds_both_suites(tmp_path, config):
    report = create_benchmark(tmp_path / "benchmark", replace(config, seed=2**64 - 1))
    assert report["stream"]["rows"]["users"] == config.users
    manifest = json.loads((tmp_path / "benchmark" / "challenge" / "manifest.json").read_text())
    assert manifest["config"]["seed"] == 0
    assert verify_dataset(tmp_path / "benchmark" / "challenge")["status"] == "VERIFIED"


@pytest.fixture(scope="module")
def data(config):
    return generate(config, suite="challenge")


@pytest.mark.parametrize("damage", ["wrong_user", "future_event", "wrong_split", "wrong_amount", "early_feedback", "missing_truth", "conflicting_delivery", "wrong_hash"])
def test_semantic_audit_rejects_broken_reference_time_and_money(data, config, damage):
    invalid = deepcopy(data)
    if damage == "wrong_user":
        invalid["events"][0]["user_id"] = "not-the-purchaser"
    elif damage == "future_event":
        invalid["events"][0]["occurred_at"] = "2027-01-01T00:00:00+09:00"
    elif damage == "wrong_split":
        invalid["truth"][0]["split"] = "not-a-split"
    elif damage == "wrong_amount":
        invalid["purchases"][0]["amount_minor"] += 1
    elif damage == "early_feedback":
        invalid["feedback"][0]["available_at"] = "2020-01-01T00:00:00+09:00"
    elif damage == "missing_truth":
        invalid["truth"].pop()
    elif damage == "conflicting_delivery":
        invalid["events"].append({**invalid["events"][0], "event_id": invalid["events"][1]["event_id"]})
    else:
        invalid["purchases"][0]["snapshot_hash"] = "0" * 64
    with pytest.raises((ValueError, KeyError)):
        audit_records(invalid, config)
