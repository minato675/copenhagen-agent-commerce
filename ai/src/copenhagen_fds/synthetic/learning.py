"""Observable-only adapter and frozen learning sample export for the benchmark."""

import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Annotated

from pydantic import Field

from copenhagen_fds.contracts.features import HistoryEvent
from copenhagen_fds.contracts.learning import LearningInput
from copenhagen_fds.contracts.learning_replay import LearningReplayInput
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Identifier, InputModel
from copenhagen_fds.learning_replay import replay_learning_samples

from .replay import ReplayIndex, instant
from .storage import DATASET_VERSION, canonical, digest


OBSERVABLE_TABLES = ("users", "purchases", "events", "outcomes", "feedback", "coverage_gaps")


class _ReplayOptions(InputModel):
    as_of: Timestamp
    current_purchase_request_id: Identifier | None = None
    limit: Annotated[int, Field(gt=0)] | None = None


def read_learning_observations(dataset: Path) -> tuple[dict, str]:
    """Verify only observable file hashes; truth/personas are never opened."""

    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    if manifest["dataset_version"] != DATASET_VERSION or manifest["origin"] != "SYNTHETIC":
        raise ValueError("unsupported synthetic dataset version or origin")
    data, hashes = {}, {}
    for name in OBSERVABLE_TABLES:
        path = dataset / f"{name}.jsonl"
        expected = manifest["files"][path.name]
        hashes[name] = digest(path)
        if hashes[name] != expected["sha256"]:
            raise ValueError("observable dataset hash mismatch")
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        if len(rows) != expected["rows"]:
            raise ValueError("observable dataset row count mismatch")
        data[name] = rows
    reference = "synthetic-observations-" + hashlib.sha256(canonical(hashes).encode()).hexdigest()
    return data, reference


def _visible_unique(rows, id_field, cutoff):
    """Ignore unavailable versions before checking conflicts or choosing facts."""

    unique = {}
    for row in rows:
        occurred, available = instant(row["occurred_at"]), instant(row["available_at"])
        if available > cutoff:
            continue
        if available < occurred:
            raise ValueError("record availability cannot precede occurrence")
        key = row[id_field]
        if unique.setdefault(key, row) != row:
            raise ValueError("visible duplicate identity has conflicting contents")
    return list(unique.values())


def _canonical_events(rows, cutoff):
    facts, attempts, purchases = {}, {}, []
    for row in rows:
        event = HistoryEvent.model_validate(row)
        if event.occurred_at >= cutoff:
            continue
        key = (event.user_id, event.kind, event.purchase_request_id, event.attempt_id)
        fact = (event.occurred_at, event.currency)
        if event.kind == "PAYMENT_ATTEMPTED":
            fact += (event.order_id,)
        if facts.setdefault(key, fact) != fact:
            raise ValueError("visible logical initiation has conflicting contents")
        if event.kind == "PURCHASE_REQUESTED":
            purchases.append(event)
        else:
            prior = attempts.get(key)
            if prior is None or (event.available_at, event.event_id) < (prior.available_at, prior.event_id):
                attempts[key] = event
    return sorted([*purchases, *attempts.values()], key=lambda event: (event.occurred_at, event.event_id))


def _latest_review(rows, value_field):
    if not rows:
        return None
    newest = max(instant(row["occurred_at"]) for row in rows)
    latest = [row for row in rows if instant(row["occurred_at"]) == newest]
    if len({row[value_field] for row in latest}) != 1:
        raise ValueError("simultaneous follow-up records disagree")
    return max(latest, key=lambda row: (instant(row["available_at"]), canonical(row)))


def synthetic_learning_inputs(
    data: dict, *, as_of: Timestamp, current_purchase_request_id: Identifier | None = None,
    limit: int | None = None,
) -> tuple[LearningReplayInput, ...]:
    """Reuse purchase-time Feature snapshots; attach only reviews known for learning."""

    options = _ReplayOptions(as_of=as_of, current_purchase_request_id=current_purchase_request_id, limit=limit)
    cutoff = options.as_of
    purchases = _visible_unique(data["purchases"], "purchase_request_id", cutoff)
    events = _visible_unique(data["events"], "event_id", cutoff)
    outcomes = _visible_unique(data["outcomes"], "outcome_id", cutoff)
    feedback = _visible_unique(data["feedback"], "feedback_id", cutoff)
    index = ReplayIndex({"users": data["users"], "purchases": purchases, "events": events,
                         "coverage_gaps": data["coverage_gaps"]})
    by_purchase = defaultdict(list)
    for event in _canonical_events(events, cutoff):
        by_purchase[event.purchase_request_id].append(event)
    followups, reviews = defaultdict(list), defaultdict(list)
    for rows, destination in ((outcomes, followups), (feedback, reviews)):
        for row in rows:
            purchase = index.purchases.get(row["purchase_request_id"])
            if purchase is None:
                continue  # Its snapshot is not yet available for learning.
            if row["user_id"] != purchase["user_id"]:
                raise ValueError("follow-up must match its purchase user")
            if instant(row["occurred_at"]) < instant(purchase["occurred_at"]):
                raise ValueError("follow-up cannot precede its purchase")
            destination[row["purchase_request_id"]].append(row)
    items = []
    for purchase in sorted(purchases, key=lambda row: (instant(row["available_at"]), row["user_id"], row["purchase_request_id"])):
        pid = purchase["purchase_request_id"]
        if pid == options.current_purchase_request_id:
            continue
        own_events = by_purchase[pid]
        if not any(event.kind == "PURCHASE_REQUESTED" for event in own_events):
            continue  # Do not invent the mandatory initiation event.
        feature_input = index.feature_input(pid)
        evaluation_time = feature_input.evaluation.evaluated_at
        if evaluation_time >= cutoff:
            continue
        order = feature_input.evaluation.request.order
        target = {"user_id": purchase["user_id"], "purchase_request_id": pid,
                  "order": order.reference, "currency": order.total.currency}
        by_attempt = defaultdict(list)
        for row in followups[pid]:
            by_attempt[row["attempt_id"]].append(row)
        known_attempts = {event.attempt_id for event in own_events if event.kind == "PAYMENT_ATTEMPTED"}
        selected_outcomes = []
        for attempt_id, rows in sorted(by_attempt.items()):
            if attempt_id not in known_attempts:
                continue  # Result may arrive before the initiation record.
            row = _latest_review(rows, "status")
            selected_outcomes.append({**{key: row[key] for key in (
                "outcome_id", "attempt_id", "status", "occurred_at", "available_at",
            )}, "target": target})
        row = _latest_review(reviews[pid], "classification")
        selected_feedback = None
        if row is not None:
            if row["source"] != "SYNTHETIC_REVIEW":
                raise ValueError("synthetic adapter requires explicit synthetic review provenance")
            selected_feedback = {**{key: row[key] for key in (
                "feedback_id", "classification", "source", "occurred_at", "available_at",
            )}, "target": target, "verification": "VERIFIED"}
        learning = LearningInput.model_validate({
            "user_id": purchase["user_id"], "currency": order.total.currency,
            "as_of": cutoff, "requested_at": cutoff, "current_purchase_request_id": options.current_purchase_request_id,
            "purchase_coverage": feature_input.history.purchases,
            "attempt_coverage": feature_input.history.attempts,
            "purchase": {"user_id": purchase["user_id"], "order": order,
                         "occurred_at": purchase["occurred_at"], "available_at": purchase["available_at"],
                         "evaluated_at": evaluation_time, "events": own_events,
                         "outcomes": selected_outcomes, "feedback": selected_feedback},
        })
        items.append(LearningReplayInput(feature_input=feature_input, learning_input=learning))
        if options.limit is not None and len(items) == options.limit:
            break
    return tuple(items)


def export_learning_samples(dataset: Path, output: Path, *, as_of: Timestamp, limit: int | None = None) -> dict:
    options = _ReplayOptions(as_of=as_of, limit=limit)
    data, source_reference = read_learning_observations(dataset)
    inputs = synthetic_learning_inputs(data, as_of=options.as_of, limit=options.limit)
    samples = replay_learning_samples(inputs, origin="SYNTHETIC", source_reference=source_reference)
    if not samples:
        raise ValueError("no observable purchase samples before the learning cutoff")
    output.mkdir(parents=True, exist_ok=False)
    path = output / "learning_samples.jsonl"
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for sample in samples:
            handle.write(canonical(sample.model_dump(mode="json")) + "\n")
    report = {
        "status": "EXPORTED", "schema_version": "purchase-learning-sample-v1",
        "feature_schema_version": "cold-features-v1", "policy_version": "confirmed-normal-v1",
        "source_reference": source_reference, "as_of": options.as_of.isoformat(),
        "limit": options.limit, "samples": len(samples),
        "eligibility": dict(sorted(Counter(sample.eligibility.status for sample in samples).items())),
        "feature_availability": dict(sorted(Counter(feature.status for sample in samples for feature in sample.raw_features.features).items())),
        "samples_sha256": digest(path), "profiles_fitted": False, "model_scored": False,
        "note": "Historical raw features and later eligibility use separate knowledge times; no truth/persona files are read.",
    }
    (output / "report.json").write_text(canonical(report) + "\n", encoding="utf-8")
    return report
