"""Seeded raw purchase simulation; hidden causes are separate from model inputs.

The policies here describe a benchmark population, not estimated production
distributions. No current FDS score or threshold is used to assign fraud labels.
"""

from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
import random
from typing import Any


KST = timezone(timedelta(hours=9))
GENERATOR_VERSION = "synthetic-behavior-v1"
ARCHETYPES = (
    "routine", "high_spender", "night", "multipeak", "weekend", "sparse",
    "constant", "gradual_drift", "abrupt_drift", "newcomer",
)
CHALLENGE_SCENARIOS = (
    "normal_high_amount", "normal_night", "normal_burst", "legitimate_retry",
    "amount_spike", "frequency_burst", "off_hour", "combined",
    "low_value_attempt_burst", "pattern_mimic", "gradual_drift", "abrupt_drift",
    "multi_merchant", "duplicate_delivery", "delayed_event", "delayed_feedback",
    "collection_gap", "cold_start", "midnight_before", "midnight_after",
)


@dataclass(frozen=True)
class GeneratorConfig:
    seed: int = 20260910
    users: int = 120
    days: int = 120
    start: str = "2026-01-01"
    train_days: int = 60
    validation_days: int = 30

    def __post_init__(self) -> None:
        for name in ("seed", "users", "days", "train_days", "validation_days"):
            if type(getattr(self, name)) is not int:
                raise ValueError(f"{name} must be an integer")
        if not 0 <= self.seed < 2**64:
            raise ValueError("seed must be an unsigned 64-bit integer")
        if self.users < 12 or self.days < 12:
            raise ValueError("at least 12 users and 12 days are required")
        if min(self.train_days, self.validation_days) < 1:
            raise ValueError("training and validation must have positive durations")
        if self.train_days + self.validation_days >= self.days:
            raise ValueError("a nonempty chronological test period is required")
        if type(self.start) is not str:
            raise ValueError("start must be an ISO date")
        try:
            first = date.fromisoformat(self.start)
            if first.isoformat() != self.start:
                raise ValueError
            first - timedelta(days=365)
            first + timedelta(days=self.days + 22)
        except (ValueError, OverflowError) as exc:
            raise ValueError("start and duration must form a supported ISO date range") from exc


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds")


def _poisson(rng: random.Random, rate: float) -> int:
    """Knuth sampler, used only for the small daily rates defined below."""
    limit, product, count = math.exp(-rate), 1.0, 0
    while product > limit:
        count += 1
        product *= rng.random()
    return count - 1


def generate(config: GeneratorConfig, *, suite: str = "stream") -> dict[str, list[dict[str, Any]]]:
    """Return replayable observable records and separate benchmark-only metadata.

    ``stream`` retains a nominal 0.7% independent attack probability. ``challenge``
    adds named, deliberately enriched edge cases and must be reported separately.
    All records stay in memory; writing, train-time visibility and model evaluation
    belong to callers. Records beyond the end date are retained for delayed review.
    """
    if type(config) is not GeneratorConfig:
        raise ValueError("config must be a GeneratorConfig")
    # Validate even if a caller bypassed frozen dataclass construction.
    config = GeneratorConfig(**config.__dict__)
    if suite not in ("stream", "challenge"):
        raise ValueError("suite must be stream or challenge")
    seed_material = f"{GENERATOR_VERSION}:{config.seed}:{suite}"
    rng = random.Random(int(hashlib.sha256(seed_material.encode()).hexdigest(), 16))
    start = datetime.combine(date.fromisoformat(config.start), datetime.min.time(), KST)
    test_day = config.train_days + config.validation_days
    result: dict[str, list[dict[str, Any]]] = {
        name: [] for name in (
            "users", "purchases", "events", "outcomes", "feedback", "truth",
            "personas", "coverage_gaps",
        )
    }
    serial = 0

    def identity(kind: str, *parts: object) -> str:
        text = json.dumps([seed_material, kind, *parts], separators=(",", ":"))
        return hashlib.sha256(text.encode()).hexdigest()[:32]

    def split(occurred: datetime) -> str:
        day = (occurred.date() - start.date()).days
        return "train" if day < config.train_days else "validation" if day < test_day else "test"

    def base_amount(persona: dict, day: int) -> int:
        median = persona["amount_median_minor"]
        if persona["archetype"] == "gradual_drift":
            median *= 1 + 1.5 * max(0, day - config.train_days) / max(1, config.days - config.train_days - 1)
        if persona["archetype"] == "abrupt_drift" and day >= test_day:
            median *= 2.4
        if persona["amount_log_sigma"] == 0:
            return round(median)
        return max(100, round(rng.lognormvariate(math.log(median), persona["amount_log_sigma"])))

    def base_time(persona: dict, day: int) -> datetime:
        center = rng.choice(persona["hour_centers_kst"])
        if persona["archetype"] == "gradual_drift":
            center += 4 * max(0, day - config.train_days) / max(1, config.days - config.train_days - 1)
        if persona["archetype"] == "abrupt_drift" and day >= test_day:
            center += 5
        seconds = int(rng.gauss(center * 3600, persona["hour_sigma"] * 3600)) % 86400
        return start + timedelta(days=day, seconds=seconds)

    def add_purchase(
        persona: dict, occurred: datetime, *, amount: int | None = None,
        fraud: bool = False, scenario: str = "ordinary", axes: tuple[str, ...] = (),
        challenge: bool = False, merchants: int = 1, attempts: int | None = None,
        event_delay: int = 0, duplicate: bool = False, feedback_delay: int | None = None,
        force_feedback: bool = False, all_failed: bool = False,
    ) -> str:
        nonlocal serial
        serial += 1
        user_id = persona["user_id"]
        purchase_id = identity("purchase", serial)
        day = (occurred.date() - start.date()).days
        amount = base_amount(persona, day) if amount is None else amount
        available = occurred
        shares = [amount // merchants] * merchants
        shares[-1] += amount - sum(shares)
        orders = [
            {"order_id": identity("order", serial, i), "merchant_id": identity("merchant", rng.randrange(18)), "amount_minor": share}
            for i, share in enumerate(shares)
        ]
        snapshot_hash = hashlib.sha256(json.dumps(
            {"merchant_orders": orders, "amount_minor": amount, "currency": "KRW"},
            sort_keys=True, separators=(",", ":"),
        ).encode()).hexdigest()
        result["purchases"].append({
            "purchase_request_id": purchase_id, "user_id": user_id, "currency": "KRW",
            "amount_minor": amount, "occurred_at": _iso(occurred), "available_at": _iso(available),
            "order_snapshot_id": identity("snapshot", serial), "order_version": "v1",
            "snapshot_hash": snapshot_hash, "merchant_orders": orders,
        })
        for i, order in enumerate(orders):
            event = {
                "event_id": identity("purchase_event", serial, i), "kind": "PURCHASE_REQUESTED",
                "user_id": user_id, "purchase_request_id": purchase_id, "order_id": order["order_id"],
                "attempt_id": None, "occurred_at": _iso(occurred),
                "available_at": _iso(available + timedelta(seconds=event_delay)), "currency": "KRW",
            }
            result["events"].append(event)
            if duplicate and i == 0:
                result["events"].append(event.copy())
        attempts = (2 if rng.random() < 0.045 else 1) if attempts is None else attempts
        for index in range(attempts):
            attempt_id = identity("attempt", serial, index)
            attempt_at = available + timedelta(seconds=5 + index * 30)
            result["events"].append({
                "event_id": identity("attempt_event", serial, index), "kind": "PAYMENT_ATTEMPTED",
                "user_id": user_id, "purchase_request_id": purchase_id, "order_id": None,
                "attempt_id": attempt_id, "occurred_at": _iso(attempt_at),
                "available_at": _iso(attempt_at + timedelta(seconds=event_delay)), "currency": "KRW",
            })
            status = "FAILED" if all_failed or index < attempts - 1 or rng.random() < 0.06 else "SUCCEEDED"
            outcome_at = attempt_at + timedelta(seconds=2)
            result["outcomes"].append({
                "outcome_id": identity("outcome", serial, index), "user_id": user_id,
                "purchase_request_id": purchase_id, "attempt_id": attempt_id, "status": status,
                "occurred_at": _iso(outcome_at), "available_at": _iso(outcome_at + timedelta(seconds=1)),
            })
        if event_delay:
            # Collector health is observable immediately, while event payloads
            # remain hidden until their actual availability timestamps.
            result["coverage_gaps"].append({
                "user_id": user_id, "start": _iso(occurred),
                "end": _iso(available + timedelta(seconds=5 + (attempts - 1) * 30 + event_delay)),
                "streams": ["purchases", "attempts"], "available_at": _iso(occurred),
            })
        # Delayed ground truth remains separate from payment success/failure.
        label_available = None
        if fraud or force_feedback or rng.random() < 0.25:
            delay_days = rng.randint(1, 21) if feedback_delay is None else feedback_delay
            review_at = occurred + timedelta(days=delay_days)
            label_available = _iso(review_at + timedelta(hours=rng.randrange(0, 7)))
            result["feedback"].append({
                "feedback_id": identity("feedback", serial), "user_id": user_id,
                "purchase_request_id": purchase_id,
                "classification": "FRAUD_CONFIRMED" if fraud else "NORMAL_CONFIRMED",
                "occurred_at": _iso(review_at), "available_at": label_available,
                "source": "SYNTHETIC_REVIEW",
            })
        observability = (
            "indistinguishable" if fraud and scenario == "pattern_mimic"
            else "behavioral_signal" if fraud
            else "legitimate_confuser" if scenario in {
                "normal_high_amount", "normal_night", "normal_burst", "legitimate_retry",
                "gradual_drift", "abrupt_drift", "midnight_before", "midnight_after",
            } else "ordinary"
        )
        result["truth"].append({
            "purchase_request_id": purchase_id, "is_fraud": fraud, "scenario": scenario,
            "behavioral_deviation_axes": list(axes), "split": split(occurred),
            "label_available_at": label_available, "benchmark_role": "challenge" if challenge else "background",
            "expected_observability": observability,
        })
        return purchase_id

    established_index = 0
    for index in range(config.users):
        newcomer = index % 5 == 0
        archetype = "newcomer" if newcomer else ARCHETYPES[established_index % (len(ARCHETYPES) - 1)]
        if not newcomer:
            established_index += 1
        median = rng.uniform(12000, 60000)
        sigma, rate, centers, hour_sigma = 0.30, rng.uniform(0.5, 1.5), [12.0], 1.0
        if archetype == "high_spender":
            median, centers = rng.uniform(250000, 650000), [14.0]
        elif archetype == "night":
            centers, hour_sigma = [23.5, 0.5], 0.6
        elif archetype == "multipeak":
            centers, hour_sigma = [8.0, 19.0], 0.7
        elif archetype == "weekend":
            centers = [15.0]
        elif archetype == "sparse":
            rate = 0.055
        elif archetype == "constant":
            median, sigma = 15000, 0.0
        elif archetype in ("gradual_drift", "abrupt_drift"):
            centers = [10.0]
        entry_day = test_day + rng.randrange(max(1, (config.days - test_day) // 3)) if newcomer else 0
        registered = start + timedelta(days=entry_day) if newcomer else start - timedelta(days=rng.randint(30, 365))
        user_id = identity("user", index)
        persona = {
            "user_id": user_id, "archetype": archetype, "amount_median_minor": round(median),
            "amount_log_sigma": sigma, "daily_purchase_rate": rate, "hour_centers_kst": centers,
            "hour_sigma": hour_sigma, "active_from_day": entry_day,
            "weekend_multiplier": 2.8 if archetype == "weekend" else 1.15,
            "weekday_multiplier": 0.18 if archetype == "weekend" else 1.0,
        }
        result["users"].append({
            "user_id": user_id, "currency": "KRW", "registered_at": _iso(registered),
            "collection_started_at": _iso(max(start, registered)),
        })
        result["personas"].append(persona)
        for day in range(entry_day, config.days):
            weekday = (start + timedelta(days=day)).weekday()
            multiplier = persona["weekend_multiplier"] if weekday >= 5 else persona["weekday_multiplier"]
            count = _poisson(rng, rate * multiplier)
            if newcomer and day == entry_day:
                count = max(count, 1)
            for _ in range(count):
                occurred = base_time(persona, day)
                amount = base_amount(persona, day)
                fraud = rng.random() < 0.007
                scenario, axes = "ordinary", ()
                if archetype in ("gradual_drift", "abrupt_drift") and day >= (config.train_days if archetype == "gradual_drift" else test_day):
                    scenario, axes = archetype, ("amount", "time")
                if fraud:
                    axes = ()
                    scenario = rng.choice(("amount_spike", "off_hour", "combined", "pattern_mimic"))
                    if scenario in ("amount_spike", "combined"):
                        amount = max(amount * rng.randint(6, 14), amount + 50000)
                        axes = ("amount",)
                    if scenario in ("off_hour", "combined"):
                        occurred = occurred.replace(hour=(occurred.hour + 10) % 24)
                        axes += ("time",)
                    if scenario == "pattern_mimic":
                        axes = ()
                add_purchase(persona, occurred, amount=amount, fraud=fraud, scenario=scenario, axes=axes)

    if suite == "challenge":
        by_archetype = {p["archetype"]: p for p in result["personas"]}
        source_purchases = list(result["purchases"])
        source_truth = {t["purchase_request_id"]: t for t in result["truth"]}
        # Put all explicit probes in test, retaining an actual day around midnight.
        probe_day = config.days - 1
        noon = start + timedelta(days=probe_day, hours=12)
        for scenario in CHALLENGE_SCENARIOS:
            archetype = {
                "normal_night": "night",
                "gradual_drift": "gradual_drift", "abrupt_drift": "abrupt_drift",
                "cold_start": "newcomer", "midnight_before": "night", "midnight_after": "night",
            }.get(scenario, "routine")
            original = by_archetype[archetype]
            persona = {**original, "user_id": identity("probe_user", scenario)}
            result["personas"].append(persona)
            registration = next(u["registered_at"] for u in result["users"] if u["user_id"] == original["user_id"])
            result["users"].append({
                "user_id": persona["user_id"], "currency": "KRW", "registered_at": registration,
                "collection_started_at": _iso(max(start, datetime.fromisoformat(registration))),
            })
            # Each scenario has a separate cloned user. A frequency attack in
            # one scenario cannot create a spurious signal in a mimic scenario.
            cutoff = start + timedelta(days=probe_day)
            if scenario != "cold_start":
                for historical in source_purchases:
                    source_label = source_truth[historical["purchase_request_id"]]
                    if historical["user_id"] == original["user_id"] and not source_label["is_fraud"] and historical["occurred_at"] < _iso(cutoff):
                        add_purchase(
                            persona, datetime.fromisoformat(historical["occurred_at"]),
                            amount=historical["amount_minor"], scenario=source_label["scenario"],
                            axes=tuple(source_label["behavioral_deviation_axes"]),
                        )
            when, normal_amount = noon, persona["amount_median_minor"]
            params: dict[str, Any] = {"scenario": scenario, "challenge": True, "force_feedback": True}
            if scenario == "normal_high_amount":
                # A confirmed legitimate exceptional purchase has the same
                # amount intervention as a fraudulent spike. Habitual high
                # spenders are represented separately in the base population.
                params.update(amount=normal_amount * 12, axes=("amount",))
            elif scenario == "normal_night":
                when = noon.replace(hour=23)
            elif scenario in ("normal_burst", "frequency_burst", "combined"):
                fraud = scenario != "normal_burst"
                params.update(fraud=fraud, axes=("frequency",))
                if scenario == "combined":
                    when = noon.replace(hour=2)
                    params.update(amount=normal_amount * 10, axes=("amount", "frequency", "time"))
                for step in range(8):
                    add_purchase(persona, when + timedelta(seconds=step * 40), **params)
                continue
            elif scenario == "legitimate_retry":
                params.update(attempts=4, axes=("attempt_frequency",))
            elif scenario == "amount_spike":
                params.update(fraud=True, amount=normal_amount * 12, axes=("amount",))
            elif scenario == "off_hour":
                when = noon.replace(hour=3)
                params.update(fraud=True, amount=normal_amount, axes=("time",))
            elif scenario == "low_value_attempt_burst":
                params.update(fraud=True, amount=100, attempts=12, all_failed=True, axes=("attempt_frequency",))
            elif scenario == "pattern_mimic":
                params.update(fraud=True, amount=normal_amount)
            elif scenario in ("gradual_drift", "abrupt_drift"):
                when = base_time(persona, probe_day)
                params.update(axes=("amount", "time"))
            elif scenario == "multi_merchant":
                params["merchants"] = 3
            elif scenario == "duplicate_delivery":
                params["duplicate"] = True
            elif scenario == "delayed_event":
                params["event_delay"] = 3 * 86400
            elif scenario == "delayed_feedback":
                params.update(fraud=True, amount=normal_amount * 8, feedback_delay=21, axes=("amount",))
            elif scenario == "collection_gap":
                gap_start, gap_end = noon - timedelta(hours=10), noon - timedelta(minutes=30)
                add_purchase(persona, gap_start + timedelta(hours=1))
                result["coverage_gaps"].append({
                    "user_id": persona["user_id"], "start": _iso(gap_start), "end": _iso(gap_end),
                    "streams": ["purchases", "attempts"], "available_at": _iso(gap_start),
                })
            elif scenario == "cold_start":
                when = start + timedelta(days=persona["active_from_day"])
            elif scenario in ("midnight_before", "midnight_after"):
                when = start + timedelta(days=probe_day, seconds=-30 if scenario == "midnight_before" else 30)
                # A one-day test must keep both boundary probes inside that split.
                if (when - start).days < test_day:
                    when = start + timedelta(days=probe_day, seconds=86370)
            add_purchase(persona, when, **params)

    # Incomplete collection is an observable property, never inferred as zero.
    # Only the explicit lost-collection scenario drops payloads. Delay gaps
    # describe late visibility and must retain the records for later replay.
    missing_targets = {
        t["purchase_request_id"] for t in result["truth"]
        if t["scenario"] == "collection_gap" and t["benchmark_role"] == "challenge"
    }
    missing_users = {p["user_id"] for p in result["purchases"] if p["purchase_request_id"] in missing_targets}
    for gap in result["coverage_gaps"]:
        if gap["user_id"] not in missing_users:
            continue
        result["events"] = [event for event in result["events"] if not (
            event["user_id"] == gap["user_id"] and gap["start"] <= event["occurred_at"] < gap["end"]
            and ("purchases" if event["kind"] == "PURCHASE_REQUESTED" else "attempts") in gap["streams"]
        )]
    for name, records in result.items():
        records.sort(key=lambda row: (
            row.get("available_at", row.get("registered_at", "")),
            row.get("occurred_at", ""),
            json.dumps(row, sort_keys=True, separators=(",", ":")),
        ))
    return result
