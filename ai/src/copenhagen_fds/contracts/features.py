"""Supplied event history and raw features, distinct from anomaly scores."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest
from copenhagen_fds.contracts.results import EvaluationTarget, Timestamp
from copenhagen_fds.contracts.values import Array, CurrencyCode, Identifier, InputModel


FEATURE_SCHEMA_VERSION = "cold-features-v1"
# feature_id: (unit, window in seconds). Counts describe prior initiation facts.
FEATURE_SPECS = {
    "amount_minor": ("MINOR_UNIT", None),
    "purchase_count_10m": ("PURCHASE_COUNT", 600),
    "purchase_count_1h": ("PURCHASE_COUNT", 3600),
    "purchase_count_24h": ("PURCHASE_COUNT", 86400),
    "attempt_count_10m": ("ATTEMPT_COUNT", 600),
    "attempt_count_1h": ("ATTEMPT_COUNT", 3600),
    "attempt_count_24h": ("ATTEMPT_COUNT", 86400),
    "kst_hour": ("KST_HOUR", None),
    "kst_day_of_week": ("KST_WEEKDAY", None),
}
FeatureState = Literal["AVAILABLE", "NOT_COLLECTED", "INSUFFICIENT_HISTORY", "NOT_APPLICABLE"]
EventKind = Literal["PURCHASE_REQUESTED", "PAYMENT_ATTEMPTED"]


class HistoryEvent(InputModel):
    """Immutable initiation fact; later outcomes belong to separate records."""

    event_id: Identifier
    kind: EventKind
    user_id: Identifier
    purchase_request_id: Identifier
    order_id: Identifier | None = None
    attempt_id: Identifier | None = None
    occurred_at: Timestamp
    available_at: Timestamp
    currency: CurrencyCode

    @model_validator(mode="after")
    def validate_event(self) -> Self:
        if (self.kind == "PAYMENT_ATTEMPTED") != (self.attempt_id is not None):
            raise ValueError("attempt identity is required exactly for payment attempt events")
        if self.available_at < self.occurred_at:
            raise ValueError("event cannot be available before it occurred")
        return self


class StreamCoverage(InputModel):
    """Caller-declared completeness, not evidence that collection really occurred."""

    status: Literal["COLLECTED", "NOT_COLLECTED", "NOT_APPLICABLE"] = "NOT_COLLECTED"
    complete_from: Timestamp | None = None
    complete_until: Timestamp | None = None

    @model_validator(mode="after")
    def validate_coverage(self) -> Self:
        if self.status == "COLLECTED":
            if self.complete_from is None or self.complete_until is None:
                raise ValueError("collected stream requires an explicit complete interval")
            if self.complete_from > self.complete_until:
                raise ValueError("complete interval must be ordered")
        elif self.complete_from is not None or self.complete_until is not None:
            raise ValueError("uncollected or inapplicable stream cannot claim completeness")
        return self


class HistorySnapshot(InputModel):
    as_of: Timestamp
    purchases: StreamCoverage = StreamCoverage()
    attempts: StreamCoverage = StreamCoverage()
    events: Array[HistoryEvent] = ()

    @model_validator(mode="after")
    def validate_snapshot(self) -> Self:
        for coverage in (self.purchases, self.attempts):
            if coverage.complete_until is not None and coverage.complete_until > self.as_of:
                raise ValueError("complete interval cannot extend beyond snapshot as_of")
        for event in self.events:
            coverage = self.purchases if event.kind == "PURCHASE_REQUESTED" else self.attempts
            if coverage.status != "COLLECTED":
                raise ValueError("event stream must be marked collected when events are supplied")
        return self


class FeatureTransformInput(InputModel):
    evaluation: BehaviorEvaluationRequest
    current_occurred_at: Timestamp | None = None
    current_available_at: Timestamp | None = None
    # Explicit null declares no current attempt. Omission must not silently
    # treat an unknown current attempt as a historical one.
    current_attempt_id: Identifier | None
    history: HistorySnapshot | None = None

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        evaluation = self.evaluation
        if evaluation.feature_schema_version != FEATURE_SCHEMA_VERSION:
            raise ValueError("unsupported raw feature schema version")
        if not set(evaluation.requested_features).issubset(FEATURE_SPECS):
            raise ValueError("unsupported requested raw feature")
        if (self.current_occurred_at is None) != (self.current_available_at is None):
            raise ValueError("current event requires both occurrence and availability timestamps")
        if self.current_occurred_at is not None:
            if not self.current_occurred_at <= self.current_available_at <= evaluation.evaluated_at:
                raise ValueError("current event must have occurred and been available at evaluation")
        if self.history is not None:
            if self.history.as_of > evaluation.evaluated_at:
                raise ValueError("future history snapshot cannot be used for evaluation")
            for event in self.history.events:
                if event.user_id != evaluation.target.user_id:
                    raise ValueError("history must belong to the evaluated user")
        return self


class RawFeatureValue(InputModel):
    feature_id: Identifier
    status: FeatureState
    value: Annotated[int, Field(ge=0)] | None = None
    unit: Literal["MINOR_UNIT", "PURCHASE_COUNT", "ATTEMPT_COUNT", "KST_HOUR", "KST_WEEKDAY"]
    currency: CurrencyCode | None = None
    window_seconds: Annotated[int, Field(gt=0)] | None = None

    @model_validator(mode="after")
    def validate_value(self) -> Self:
        if self.feature_id not in FEATURE_SPECS:
            raise ValueError("unknown raw feature")
        if (self.unit, self.window_seconds) != FEATURE_SPECS[self.feature_id]:
            raise ValueError("raw feature unit or time window does not match its definition")
        if (self.status == "AVAILABLE") != (self.value is not None):
            raise ValueError("raw value is required exactly when available")
        needs_currency = self.unit in {"MINOR_UNIT", "PURCHASE_COUNT", "ATTEMPT_COUNT"}
        if needs_currency != (self.currency is not None):
            raise ValueError("amount and currency-scoped counts require currency; time features do not")
        limit = {"KST_HOUR": 23, "KST_WEEKDAY": 6}.get(self.unit)
        if limit is not None and self.value is not None and self.value > limit:
            raise ValueError("time feature is outside its range")
        return self


class RawFeatureVector(InputModel):
    target: EvaluationTarget
    evaluated_at: Timestamp
    feature_schema_version: Literal["cold-features-v1"] = FEATURE_SCHEMA_VERSION
    features: Annotated[Array[RawFeatureValue], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_unique(self) -> Self:
        if len({value.feature_id for value in self.features}) != len(self.features):
            raise ValueError("raw feature IDs must be unique")
        return self

    def validate_for_request(self, request: BehaviorEvaluationRequest) -> Self:
        if self.target != request.target or self.evaluated_at != request.evaluated_at:
            raise ValueError("raw features must match the evaluation target and time")
        if self.feature_schema_version != request.feature_schema_version:
            raise ValueError("raw feature schema must match the request")
        if {f.feature_id for f in self.features} != set(request.requested_features):
            raise ValueError("raw feature set must match every requested feature")
        currency = request.request.order.total.currency
        if any(f.currency is not None and f.currency != currency for f in self.features):
            raise ValueError("raw feature currency must match the current purchase")
        return self
