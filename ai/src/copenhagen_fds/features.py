"""Deterministic amount, initiation frequency and KST features; no I/O or scoring."""

from datetime import timedelta, timezone

from copenhagen_fds.contracts.features import (
    FEATURE_SPECS, FeatureTransformInput, HistoryEvent, RawFeatureValue, RawFeatureVector,
)


KST = timezone(timedelta(hours=9))


def _known_events(data: FeatureTransformInput) -> tuple[HistoryEvent, ...]:
    """Filter by knowledge time before comparing logical facts or deduplicating."""
    if data.history is None:
        return ()
    now = data.evaluation.evaluated_at
    by_event = {}
    by_fact = {}
    for event in data.history.events:
        if event.occurred_at >= now or event.available_at > data.history.as_of:
            continue
        if event.kind == "PURCHASE_REQUESTED" and event.purchase_request_id == data.evaluation.target.purchase_request_id:
            if event.currency != data.evaluation.request.order.total.currency:
                raise ValueError("current purchase history must retain its currency")
            if data.current_occurred_at is not None and event.occurred_at != data.current_occurred_at:
                raise ValueError("current purchase history must match its supplied initiation time")
        previous = by_event.setdefault(event.event_id, event)
        if previous != event:
            raise ValueError("duplicate event ID has conflicting contents")
        # Merchant copies may have different order/event IDs, but the original
        # purchase initiation time and currency must remain identical.
        key = (event.kind, event.purchase_request_id, event.attempt_id)
        fact = (event.occurred_at, event.currency)
        if event.kind == "PAYMENT_ATTEMPTED":
            fact += (event.order_id,)
        if by_fact.setdefault(key, fact) != fact:
            raise ValueError("logical initiation identity has conflicting contents")
    return tuple(by_event.values())


def _count(data: FeatureTransformInput, events: tuple[HistoryEvent, ...], unit: str, seconds: int):
    if data.history is None:
        return "NOT_COLLECTED", None
    stream = data.history.purchases if unit == "PURCHASE_COUNT" else data.history.attempts
    if stream.status != "COLLECTED":
        return stream.status, None
    now = data.evaluation.evaluated_at
    lower = now - timedelta(seconds=seconds)
    if stream.complete_from > lower or stream.complete_until < now:
        return "INSUFFICIENT_HISTORY", None
    kind = "PURCHASE_REQUESTED" if unit == "PURCHASE_COUNT" else "PAYMENT_ATTEMPTED"
    currency = data.evaluation.request.order.total.currency
    current_purchase = data.evaluation.target.purchase_request_id
    identities = set()
    for event in events:
        if event.kind != kind or event.currency != currency or event.occurred_at < lower:
            continue
        if kind == "PURCHASE_REQUESTED":
            if event.purchase_request_id != current_purchase:
                identities.add(event.purchase_request_id)
        elif not (event.purchase_request_id == current_purchase and event.attempt_id == data.current_attempt_id):
            identities.add((event.purchase_request_id, event.attempt_id))
    return "AVAILABLE", len(identities)


def transform_features(data: FeatureTransformInput) -> RawFeatureVector:
    """Use validated, supplied facts only; never read the clock, DB or environment."""
    events = _known_events(data)
    values = []
    currency = data.evaluation.request.order.total.currency
    for feature_id, (unit, seconds) in FEATURE_SPECS.items():
        if feature_id not in data.evaluation.requested_features:
            continue
        if unit == "MINOR_UNIT":
            status, value = "AVAILABLE", data.evaluation.request.order.total.amount_minor
        elif seconds is not None:
            status, value = _count(data, events, unit, seconds)
        elif data.current_occurred_at is None:
            status, value = "NOT_COLLECTED", None
        else:
            local = data.current_occurred_at.astimezone(KST)
            status = "AVAILABLE"
            value = local.hour if unit == "KST_HOUR" else local.weekday()
        values.append(RawFeatureValue(
            feature_id=feature_id, status=status, value=value, unit=unit,
            currency=currency if unit in {"MINOR_UNIT", "PURCHASE_COUNT", "ATTEMPT_COUNT"} else None,
            window_seconds=seconds,
        ))
    return RawFeatureVector(
        target=data.evaluation.target, evaluated_at=data.evaluation.evaluated_at, features=values,
    ).validate_for_request(data.evaluation)
