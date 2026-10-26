"""Project observable synthetic records into the existing raw Feature contract.

This adapter never reads truth, personas, payment outcomes, or future feedback.
It does not build a learned profile or invoke an FDS scoring policy.
"""

import hashlib
from collections import defaultdict
from datetime import datetime, timedelta

from copenhagen_fds.contracts.features import FEATURE_SPECS, FeatureTransformInput


def instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("timestamp requires a timezone")
    return parsed


class ReplayIndex:
    def __init__(self, data: dict):
        self.users = {row["user_id"]: row for row in data["users"]}
        self.purchases = {row["purchase_request_id"]: row for row in data["purchases"]}
        self.events = defaultdict(list)
        self.attempts = defaultdict(dict)
        self.gaps = defaultdict(list)
        for row in data["events"]:
            self.events[row["user_id"]].append(row)
            if row["kind"] == "PAYMENT_ATTEMPTED":
                self.attempts[row["purchase_request_id"]][row["attempt_id"]] = row
        for row in data["coverage_gaps"]:
            self.gaps[row["user_id"]].append(row)

    def feature_input(self, purchase_id: str, *, attempt_id: str | None = None) -> FeatureTransformInput:
        purchase = self.purchases[purchase_id]
        user_id = purchase["user_id"]
        now = instant(purchase["available_at"])
        if attempt_id is not None:
            attempt = self.attempts[purchase_id][attempt_id]
            now = max(now, instant(attempt["available_at"]))
        # Strict upper-bound windows exclude events at evaluated_at. The selected
        # triggering event is available just before this fixed evaluation instant.
        now += timedelta(microseconds=1)
        lower = now - timedelta(days=1)
        events = [row for row in self.events[user_id]
                  if lower <= instant(row["occurred_at"]) < now
                  and instant(row["available_at"]) <= now]
        coverage = {}
        for stream in ("purchases", "attempts"):
            complete_from = max(lower, instant(self.users[user_id]["registered_at"]),
                                instant(self.users[user_id]["collection_started_at"]))
            for gap in self.gaps[user_id]:
                if stream not in gap["streams"] or instant(gap["available_at"]) > now:
                    continue
                if instant(gap["start"]) < now and instant(gap["end"]) > complete_from:
                    complete_from = max(complete_from, min(instant(gap["end"]), now))
            coverage[stream] = {"status": "COLLECTED", "complete_from": complete_from.isoformat(),
                                "complete_until": now.isoformat()}
        merchants = []
        for merchant in purchase["merchant_orders"]:
            money = {"amount_minor": merchant["amount_minor"], "currency": purchase["currency"]}
            merchants.append({
                "order_id": merchant["order_id"], "merchant_id": merchant["merchant_id"],
                "items": [{"item_id": hashlib.sha256(merchant["order_id"].encode()).hexdigest()[:24],
                           "name": "Synthetic purchase", "quantity": 1,
                           "unit_price": money, "line_total": money}], "total": money,
            })
        evaluation_id = hashlib.sha256((purchase_id + now.isoformat() + str(attempt_id)).encode()).hexdigest()[:32]
        request = {
            "context": {"evaluation_id": evaluation_id, "user_id": user_id, "purchase_request_id": purchase_id},
            "intent": {"purchase_request_id": purchase_id, "source": "unknown", "version": None},
            "order": {"purchase_request_id": purchase_id,
                      "reference": {"snapshot_id": purchase["order_snapshot_id"],
                                    "version": purchase["order_version"], "snapshot_hash": purchase["snapshot_hash"]},
                      "orders": merchants,
                      "total": {"amount_minor": purchase["amount_minor"], "currency": purchase["currency"]}},
        }
        return FeatureTransformInput.model_validate({
            "evaluation": {"request": request, "evaluated_at": now.isoformat(),
                           "feature_schema_version": "cold-features-v1", "requested_features": list(FEATURE_SPECS)},
            "current_occurred_at": purchase["occurred_at"], "current_available_at": purchase["available_at"],
            "current_attempt_id": attempt_id,
            "history": {"as_of": now.isoformat(), "events": events, **coverage},
        })

    def evaluation_points(self, purchase_id: str):
        """Purchase arrival plus the last retry, when the purchase has retries."""
        yield self.feature_input(purchase_id)
        attempts = self.attempts[purchase_id]
        if len(attempts) > 1:
            last = max(attempts.values(), key=lambda row: (instant(row["available_at"]), row["attempt_id"]))
            yield self.feature_input(purchase_id, attempt_id=last["attempt_id"])
