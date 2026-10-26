"""Canonical purchase learning records; no history replay or fitted statistics."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.features import HistoryEvent, StreamCoverage
from copenhagen_fds.contracts.inputs import OrderSnapshot, SnapshotReference
from copenhagen_fds.contracts.profiles import BehaviorProfile, ProfileReference, ProfileScope
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, CurrencyCode, Identifier, InputModel, Money


LearningPolicyVersion = Literal["confirmed-normal-v1"]
LearningStatus = Literal["INCLUDE", "DEFER", "EXCLUDE"]
LearningReason = Literal[
    "NORMAL_CONFIRMED", "UNCONFIRMED", "SUSPECTED", "FRAUD_CONFIRMED",
    "UNVERIFIED_FEEDBACK", "PURCHASE_NOT_OBSERVED", "CURRENT_PURCHASE",
]
_STATUS_BY_REASON = {
    "NORMAL_CONFIRMED": "INCLUDE", "FRAUD_CONFIRMED": "EXCLUDE",
    "UNCONFIRMED": "DEFER", "SUSPECTED": "DEFER", "UNVERIFIED_FEEDBACK": "DEFER",
    "PURCHASE_NOT_OBSERVED": "DEFER", "CURRENT_PURCHASE": "DEFER",
}


class LearningPurchaseReference(InputModel):
    user_id: Identifier
    purchase_request_id: Identifier
    order: SnapshotReference
    currency: CurrencyCode


class PaymentOutcome(InputModel):
    """One supplied result per attempt; success is not a normality label."""

    outcome_id: Identifier
    target: LearningPurchaseReference
    attempt_id: Identifier
    status: Literal["SUCCEEDED", "FAILED"]
    occurred_at: Timestamp
    available_at: Timestamp

    @model_validator(mode="after")
    def validate_time(self) -> Self:
        if self.available_at < self.occurred_at:
            raise ValueError("outcome cannot be available before it occurred")
        return self


class LearningFeedback(InputModel):
    """Caller-selected review fact, never a benchmark truth row or authentication."""

    feedback_id: Identifier
    target: LearningPurchaseReference
    classification: Literal["UNCONFIRMED", "SUSPECTED", "NORMAL_CONFIRMED", "FRAUD_CONFIRMED"]
    source: Identifier
    verification: Literal["VERIFIED", "UNVERIFIED"]
    occurred_at: Timestamp
    available_at: Timestamp

    @model_validator(mode="after")
    def validate_time(self) -> Self:
        if self.available_at < self.occurred_at:
            raise ValueError("feedback cannot be available before it occurred")
        return self


class LearningPurchase(InputModel):
    """One whole purchase/order version, with canonical event and review records."""

    user_id: Identifier
    order: OrderSnapshot
    occurred_at: Timestamp
    available_at: Timestamp
    evaluated_at: Timestamp | None
    events: Annotated[Array[HistoryEvent], Field(min_length=1)]
    outcomes: Array[PaymentOutcome] = ()
    feedback: LearningFeedback | None = None

    @property
    def reference(self) -> LearningPurchaseReference:
        return LearningPurchaseReference(
            user_id=self.user_id, purchase_request_id=self.order.purchase_request_id,
            order=self.order.reference, currency=self.order.total.currency,
        )

    @model_validator(mode="after")
    def validate_purchase(self) -> Self:
        if self.available_at < self.occurred_at:
            raise ValueError("purchase cannot be available before it occurred")
        if self.evaluated_at is not None and self.evaluated_at < self.available_at:
            raise ValueError("purchase evaluation cannot precede its availability")
        order_ids = {order.order_id for order in self.order.orders}
        event_ids, attempts = set(), {}
        purchase_events = []
        for event in self.events:
            if (event.user_id, event.purchase_request_id, event.currency) != (
                self.user_id, self.order.purchase_request_id, self.order.total.currency,
            ):
                raise ValueError("event must match the purchase user, identity and currency")
            if event.order_id is not None and event.order_id not in order_ids:
                raise ValueError("event order must belong to the purchase snapshot")
            if event.event_id in event_ids:
                raise ValueError("canonical event IDs must be unique")
            event_ids.add(event.event_id)
            if event.occurred_at < self.occurred_at:
                raise ValueError("event cannot precede the purchase")
            if event.kind == "PURCHASE_REQUESTED":
                if event.occurred_at != self.occurred_at:
                    raise ValueError("purchase initiation events must share the purchase time")
                purchase_events.append(event)
            else:
                if event.attempt_id in attempts:
                    raise ValueError("canonical attempt IDs must be unique")
                attempts[event.attempt_id] = event
        if not purchase_events:
            raise ValueError("purchase requires an initiation event")
        outcome_ids, outcome_attempts = set(), set()
        for outcome in self.outcomes:
            if outcome.target != self.reference:
                raise ValueError("outcome must match the complete purchase reference")
            if outcome.attempt_id not in attempts:
                raise ValueError("outcome must reference a supplied attempt")
            if outcome.occurred_at < attempts[outcome.attempt_id].occurred_at:
                raise ValueError("outcome cannot precede its attempt")
            if outcome.outcome_id in outcome_ids or outcome.attempt_id in outcome_attempts:
                raise ValueError("supply one canonical outcome per attempt")
            outcome_ids.add(outcome.outcome_id)
            outcome_attempts.add(outcome.attempt_id)
        if self.feedback is not None:
            if self.feedback.target != self.reference:
                raise ValueError("feedback must match the complete purchase reference")
            if self.feedback.occurred_at < self.occurred_at:
                raise ValueError("feedback cannot precede the purchase")
        return self


class LearningInput(InputModel):
    schema_version: Literal["profile-learning-input-v1"] = "profile-learning-input-v1"
    feature_schema_version: Literal["cold-features-v1"] = "cold-features-v1"
    policy_version: LearningPolicyVersion = "confirmed-normal-v1"
    user_id: Identifier
    currency: CurrencyCode
    as_of: Timestamp
    requested_at: Timestamp
    current_purchase_request_id: Identifier | None
    purchase_coverage: StreamCoverage
    attempt_coverage: StreamCoverage
    purchase: LearningPurchase

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if (self.purchase.user_id, self.purchase.order.total.currency) != (self.user_id, self.currency):
            raise ValueError("learning context must match the purchase user and currency")
        if self.requested_at < self.as_of:
            raise ValueError("learning request cannot precede its information cutoff")
        for coverage in (self.purchase_coverage, self.attempt_coverage):
            if coverage.complete_until is not None and coverage.complete_until > self.as_of:
                raise ValueError("collection coverage cannot extend beyond the information cutoff")
        for event in self.purchase.events:
            coverage = self.purchase_coverage if event.kind == "PURCHASE_REQUESTED" else self.attempt_coverage
            if coverage.status != "COLLECTED":
                raise ValueError("supplied events require a collected stream")
        return self


class LearningDecision(InputModel):
    target: LearningPurchaseReference
    as_of: Timestamp
    policy_version: LearningPolicyVersion = "confirmed-normal-v1"
    status: LearningStatus
    reason: LearningReason
    observed_purchase: bool
    observed_attempt_ids: Array[Identifier] = ()
    feedback_id: Identifier | None = None
    learning_amount: Money | None = None

    @model_validator(mode="after")
    def validate_decision(self) -> Self:
        if self.status != _STATUS_BY_REASON[self.reason]:
            raise ValueError("learning status must match its policy reason")
        if (self.status == "INCLUDE") != (self.learning_amount is not None):
            raise ValueError("learning amount is required exactly for included purchases")
        if self.learning_amount is not None and self.learning_amount.currency != self.target.currency:
            raise ValueError("learning amount must match the target currency")
        if len(set(self.observed_attempt_ids)) != len(self.observed_attempt_ids):
            raise ValueError("observed attempt IDs must be unique")
        unobserved = self.reason in {"PURCHASE_NOT_OBSERVED", "CURRENT_PURCHASE"}
        if self.observed_purchase == unobserved:
            raise ValueError("purchase observation must match the decision reason")
        if unobserved and (self.observed_attempt_ids or self.feedback_id is not None):
            raise ValueError("unobserved purchase cannot expose attempts or feedback")
        if self.reason in {"NORMAL_CONFIRMED", "FRAUD_CONFIRMED", "SUSPECTED", "UNVERIFIED_FEEDBACK"}:
            if self.feedback_id is None:
                raise ValueError("review-based decision requires its feedback reference")
        return self


class _LearningStateContext(InputModel):
    user_id: Identifier
    currency: CurrencyCode
    expected_state_version: Identifier
    expected_profile_reference: ProfileReference | None


class ProfileLearningState(InputModel):
    """Per-user learning ledger envelope; statistics and durable updates come later."""

    schema_version: Literal["profile-learning-state-v1"] = "profile-learning-state-v1"
    feature_schema_version: Literal["cold-features-v1"] = "cold-features-v1"
    policy_version: LearningPolicyVersion = "confirmed-normal-v1"
    state_version: Identifier
    user_id: Identifier
    currency: CurrencyCode
    as_of: Timestamp
    profile: BehaviorProfile | None
    decisions: Array[LearningDecision] = ()

    @model_validator(mode="after")
    def validate_state(self) -> Self:
        purchase_ids = set()
        for decision in self.decisions:
            if (decision.target.user_id, decision.target.currency) != (self.user_id, self.currency):
                raise ValueError("learning decisions must match the state user and currency")
            if decision.as_of > self.as_of or decision.policy_version != self.policy_version:
                raise ValueError("learning decision must match the state policy and cutoff")
            if decision.target.purchase_request_id in purchase_ids:
                raise ValueError("state permits only one decision per whole purchase")
            purchase_ids.add(decision.target.purchase_request_id)
        if self.profile is not None:
            profile = self.profile
            if profile.reference.scope != ProfileScope.PERSONAL or profile.reference.user_id != self.user_id:
                raise ValueError("per-user learning state requires its personal profile")
            if profile.currency != self.currency or profile.feature_schema_version != self.feature_schema_version:
                raise ValueError("profile must match the state currency and feature schema")
            if profile.as_of > self.as_of or profile.generated_at > self.as_of:
                raise ValueError("profile cannot be from after the state cutoff")
        return self

    def validate_for_update(
        self, *, user_id: Identifier, currency: CurrencyCode,
        expected_state_version: Identifier, expected_profile_reference: ProfileReference | None,
    ) -> Self:
        """Compare supplied versions; this does not perform a DB compare-and-swap."""

        context = _LearningStateContext(
            user_id=user_id, currency=currency, expected_state_version=expected_state_version,
            expected_profile_reference=expected_profile_reference,
        )
        if (self.user_id, self.currency, self.state_version) != (
            context.user_id, context.currency, context.expected_state_version,
        ):
            raise ValueError("learning state does not match the expected owner, currency or version")
        actual_reference = None if self.profile is None else self.profile.reference
        if actual_reference != context.expected_profile_reference:
            raise ValueError("learning profile does not match the expected reference")
        return self
