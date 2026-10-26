"""Explicit local dataset I/O, immutable exports, and integrity manifests."""

import csv
import hashlib
import json
import platform
from collections import Counter
from dataclasses import asdict, replace
from datetime import datetime, timedelta
from pathlib import Path

from copenhagen_fds.contracts.features import HistoryEvent

from .generator import GeneratorConfig, generate


DATASET_VERSION = "fds-behavior-synthetic-v1"
TABLES = ("users", "purchases", "events", "outcomes", "feedback", "truth", "personas", "coverage_gaps")
SOURCES = (
    "https://fraud-detection-handbook.github.io/fraud-detection-handbook/Chapter_3_GettingStarted/SimulatedDataset.html",
    "https://albahnsen.github.io/files/Feature%20Engineering%20Strategies%20for%20Credit%20Card%20Fraud%20Detection_published.pdf",
    "https://www.federalreserve.gov/econres/feds/files/2025017pap.pdf",
    "https://fraud-detection-handbook.github.io/fraud-detection-handbook/Chapter_5_ModelValidationAndSelection/ValidationStrategies.html",
    "https://docs.stripe.com/disputes/prevention/card-testing",
)


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summary(data: dict) -> dict:
    truth = data["truth"]
    splits = {}
    for name in ("train", "validation", "test"):
        rows = [row for row in truth if row["split"] == name]
        frauds = sum(row["is_fraud"] for row in rows)
        splits[name] = {"purchases": len(rows), "fraud": frauds,
                        "fraud_fraction": frauds / len(rows) if rows else None}
    return {
        "rows": {name: len(data[name]) for name in TABLES},
        "splits": splits,
        "scenarios": dict(sorted(Counter(row["scenario"] for row in truth).items())),
        "archetypes": dict(sorted(Counter(row["archetype"] for row in data["personas"]).items())),
        "fraud": sum(row["is_fraud"] for row in truth),
        "unique_events": len({row["event_id"] for row in data["events"]}),
    }


def audit_records(data: dict, config: GeneratorConfig) -> dict:
    """Check generated references and time semantics independently of scores."""
    def index(name, key):
        rows = {row[key]: row for row in data[name]}
        if len(rows) != len(data[name]):
            raise ValueError("duplicate dataset identity")
        return rows

    def timestamp(text):
        value = datetime.fromisoformat(text)
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("dataset time must include timezone")
        return value

    def ordered(row):
        if timestamp(row["occurred_at"]) > timestamp(row["available_at"]):
            raise ValueError("dataset availability precedes occurrence")

    users = index("users", "user_id")
    purchases = index("purchases", "purchase_request_id")
    truth = index("truth", "purchase_request_id")
    personas = index("personas", "user_id")
    if set(purchases) != set(truth) or set(users) != set(personas):
        raise ValueError("dataset truth or persona coverage mismatch")
    start = timestamp(config.start + "T00:00:00+09:00")
    for user in users.values():
        if timestamp(user["collection_started_at"]) < max(start, timestamp(user["registered_at"])):
            raise ValueError("collection coverage precedes simulated observations")
    for key, purchase in purchases.items():
        user = users[purchase["user_id"]]
        ordered(purchase)
        occurred = timestamp(purchase["occurred_at"])
        if occurred < timestamp(user["registered_at"]):
            raise ValueError("purchase precedes registration")
        day = (occurred - start).total_seconds() / 86400
        expected_split = "train" if day < config.train_days else "validation" if day < config.train_days + config.validation_days else "test"
        if not 0 <= day < config.days or truth[key]["split"] != expected_split:
            raise ValueError("purchase split or range mismatch")
        if type(truth[key]["is_fraud"]) is not bool or not isinstance(purchase["order_version"], str):
            raise ValueError("invalid label or opaque order version")
        amounts = [purchase["amount_minor"], *(row["amount_minor"] for row in purchase["merchant_orders"])]
        if any(type(value) is not int or value < 0 for value in amounts) or sum(amounts[1:]) != amounts[0]:
            raise ValueError("purchase and merchant amounts disagree")
        snapshot = {"merchant_orders": purchase["merchant_orders"], "amount_minor": purchase["amount_minor"], "currency": purchase["currency"]}
        if hashlib.sha256(canonical(snapshot).encode()).hexdigest() != purchase["snapshot_hash"]:
            raise ValueError("synthetic snapshot content hash mismatch")
    seen_events = {}
    for event in data["events"]:
        HistoryEvent.model_validate(event)
        purchase = purchases[event["purchase_request_id"]]
        if event["user_id"] != purchase["user_id"] or event["currency"] != purchase["currency"]:
            raise ValueError("event purchase context mismatch")
        if seen_events.setdefault(event["event_id"], event) != event:
            raise ValueError("conflicting duplicate event")
        if timestamp(event["occurred_at"]) < timestamp(purchase["occurred_at"]):
            raise ValueError("event precedes purchase")
    feedback = index("feedback", "purchase_request_id")
    for key, label in truth.items():
        row = feedback.get(key)
        if row is None:
            if label["label_available_at"] is not None:
                raise ValueError("truth refers to unavailable feedback")
            continue
        ordered(row)
        expected = "FRAUD_CONFIRMED" if label["is_fraud"] else "NORMAL_CONFIRMED"
        if row["classification"] != expected or row["available_at"] != label["label_available_at"]:
            raise ValueError("feedback and synthetic truth disagree")
    for name in ("outcomes", "feedback"):
        for row in data[name]:
            ordered(row)
            purchase = purchases[row["purchase_request_id"]]
            if row["user_id"] != purchase["user_id"] or timestamp(row["occurred_at"]) < timestamp(purchase["occurred_at"]):
                raise ValueError("follow-up context or time mismatch")
    for gap in data["coverage_gaps"]:
        if gap["user_id"] not in users or timestamp(gap["start"]) >= timestamp(gap["end"]):
            raise ValueError("invalid collection gap")
        timestamp(gap["available_at"])
        if not gap["streams"] or not set(gap["streams"]).issubset({"purchases", "attempts"}):
            raise ValueError("unknown collection stream")
    return {"status": "VALID", "purchases_checked": len(purchases), "event_rows_checked": len(data["events"]),
            "duplicate_event_deliveries": len(data["events"]) - len(seen_events)}


def _write_csv(path: Path, fields, rows):
    # CSVs are convenience views only. JSONL remains the lossless source.
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_dataset(destination: Path, config: GeneratorConfig, *, suite: str) -> dict:
    """Refuse replacement of frozen data; manifest is written only after data."""
    data = generate(config, suite=suite)
    validation = audit_records(data, config)
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    files = {}
    for name in TABLES:
        path = destination / f"{name}.jsonl"
        with path.open("w", encoding="utf-8", newline="\n") as handle:
            for row in data[name]:
                handle.write(canonical(row) + "\n")
        files[path.name] = {"sha256": digest(path), "rows": len(data[name])}
    _write_csv(destination / "purchases.csv",
               ("purchase_request_id", "user_id", "occurred_at", "available_at", "currency", "amount_minor"),
               data["purchases"])
    _write_csv(destination / "truth.csv",
               ("purchase_request_id", "is_fraud", "scenario", "split", "label_available_at", "benchmark_role", "expected_observability"),
               data["truth"])
    for name in ("purchases.csv", "truth.csv"):
        files[name] = {"sha256": digest(destination / name), "rows": len(data["purchases"])}
    start = datetime.fromisoformat(config.start + "T00:00:00+09:00")
    manifest = {
        "dataset_version": DATASET_VERSION, "origin": "SYNTHETIC", "suite": suite,
        "config": asdict(config), "python": platform.python_version(),
        "generator_sha256": digest(Path(__file__).with_name("generator.py")),
        "timezone": "Asia/Seoul (+09:00)", "currency": "KRW",
        "split_by": "purchase.occurred_at; half-open intervals; feedback visibility is independent",
        "boundaries": {"start": start.isoformat(),
                       "validation_start": (start + timedelta(days=config.train_days)).isoformat(),
                       "test_start": (start + timedelta(days=config.train_days + config.validation_days)).isoformat(),
                       "end_exclusive": (start + timedelta(days=config.days)).isoformat()},
        "files": files, "summary": summary(data), "validation": validation, "sources": SOURCES,
        "notes": [
            "Simulation parameters are project assumptions, not measured Korean payment distributions.",
            "Truth and personas are evaluator-only; never include them in model inputs.",
            "Feedback can become available after a split ends; success is not a verified normal label.",
            "Repeatedly inspected test data is a regression benchmark, not an untouched final holdout.",
            "Challenge prevalence is deliberately enriched and is not comparable to stream precision.",
            "No model was trained or scored when producing this dataset.",
        ],
    }
    (destination / "manifest.json").write_text(canonical(manifest) + "\n", encoding="utf-8")
    return manifest


def create_benchmark(destination: Path, config: GeneratorConfig) -> dict:
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=False)
    stream = write_dataset(destination / "stream", config, suite="stream")
    challenge_config = replace(config, users=max(18, config.users // 3), seed=(config.seed + 1) % 2**64)
    challenge = write_dataset(destination / "challenge", challenge_config, suite="challenge")
    overview = {"dataset_version": DATASET_VERSION, "stream": stream["summary"],
                "challenge": challenge["summary"],
                "manifest_sha256": {name: digest(destination / name / "manifest.json")
                                    for name in ("stream", "challenge")}}
    (destination / "benchmark.json").write_text(canonical(overview) + "\n", encoding="utf-8")
    return overview


def read_dataset(destination: Path) -> dict:
    destination = Path(destination)
    return {name: [json.loads(line) for line in (destination / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()]
            for name in TABLES}


def verify_dataset(destination: Path) -> dict:
    destination = Path(destination)
    manifest = json.loads((destination / "manifest.json").read_text(encoding="utf-8"))
    expected = {f"{name}.jsonl" for name in TABLES} | {"purchases.csv", "truth.csv"}
    if manifest["dataset_version"] != DATASET_VERSION or set(manifest["files"]) != expected:
        raise ValueError("unsupported dataset inventory")
    for name, information in manifest["files"].items():
        if digest(destination / name) != information["sha256"]:
            raise ValueError("dataset checksum mismatch")
    data = read_dataset(destination)
    validation = audit_records(data, GeneratorConfig(**manifest["config"]))
    if validation != manifest["validation"]:
        raise ValueError("dataset validation summary mismatch")
    if summary(data) != manifest["summary"]:
        raise ValueError("dataset summary mismatch")
    return {"status": "VERIFIED", "dataset_version": DATASET_VERSION, "suite": manifest["suite"],
            "validation": validation, "summary": manifest["summary"]}
