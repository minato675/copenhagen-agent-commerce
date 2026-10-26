"""Input shapes for FDS analysis, without authentication or payment policy."""

from collections.abc import Iterable
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.values import (
    Array,
    Identifier,
    InputModel,
    Money,
    PositiveQuantity,
    Sha256,
    Text,
)


IntentSource = Literal["user_direct", "agent_summary", "unknown"]


def _same_currency(currency: str, amounts: Iterable[Money | None]) -> None:
    if any(amount is not None and amount.currency != currency for amount in amounts):
        raise ValueError("mixed currencies are unsupported; no currency conversion is performed")


def _unique(values: Iterable[str], label: str) -> None:
    items = tuple(values)
    if len(set(items)) != len(items):
        raise ValueError(f"{label} must be unique within their parent")


class IntentBudget(InputModel):
    amount: Money
    kind: Literal["maximum", "approximate"]


class IntentItem(InputModel):
    """Only supplied intent is recorded; unknown attributes remain absent."""

    name: Text
    quantity: PositiveQuantity | None = None
    product_id: Identifier | None = None
    merchant_id: Identifier | None = None
    brand: Text | None = None
    category: Text | None = None


class PurchaseIntent(InputModel):
    """Interpreted intent is evidence for analysis, never user approval."""

    purchase_request_id: Identifier
    source: IntentSource
    version: Identifier | None = None
    items: Array[IntentItem] | None = None
    budget: IntentBudget | None = None
    explicit_conditions: Array[Text] | None = None
    preferences: Array[Text] | None = None
    ambiguities: Array[Text] | None = None
    purpose: Text | None = None


class SnapshotReference(InputModel):
    """BE-provided opaque identity/version and SHA-256 text, not a verified digest."""

    snapshot_id: Identifier
    version: Identifier
    snapshot_hash: Sha256


class OrderItem(InputModel):
    item_id: Identifier
    product_id: Identifier | None = None
    name: Text
    quantity: PositiveQuantity
    unit_price: Money
    line_total: Money
    brand: Text | None = None
    category: Text | None = None
    description: Text | None = None

    @model_validator(mode="after")
    def validate_currency(self) -> Self:
        _same_currency(self.unit_price.currency, (self.line_total,))
        return self


class MerchantOrder(InputModel):
    order_id: Identifier
    merchant_id: Identifier
    items: Annotated[Array[OrderItem], Field(min_length=1)]
    total: Money
    shipping: Money | None = None
    tax: Money | None = None
    discount: Money | None = None

    @model_validator(mode="after")
    def validate_structure(self) -> Self:
        _unique((item.item_id for item in self.items), "item IDs")
        _same_currency(self.total.currency, (self.shipping, self.tax, self.discount))
        _same_currency(self.total.currency, (item.line_total for item in self.items))
        return self


class OrderSnapshot(InputModel):
    """One purchase request may contain orders from multiple merchants."""

    purchase_request_id: Identifier
    reference: SnapshotReference
    orders: Annotated[Array[MerchantOrder], Field(min_length=1)]
    total: Money
    shipping: Money | None = None
    tax: Money | None = None
    discount: Money | None = None

    @model_validator(mode="after")
    def validate_structure(self) -> Self:
        _unique((order.order_id for order in self.orders), "order IDs")
        _same_currency(self.total.currency, (order.total for order in self.orders))
        _same_currency(self.total.currency, (self.shipping, self.tax, self.discount))
        return self


class ApprovalReference(InputModel):
    """Minimal supplied reference; its presence does not establish approval."""

    approval_id: Identifier
    snapshot: SnapshotReference


class EvaluationContext(InputModel):
    """References supplied by the BE caller; this model does not authenticate it."""

    evaluation_id: Identifier
    user_id: Identifier
    purchase_request_id: Identifier
    approval: ApprovalReference | None = None


class FraudEvaluationRequest(InputModel):
    intent: PurchaseIntent
    order: OrderSnapshot
    context: EvaluationContext

    @model_validator(mode="after")
    def validate_request_relationship(self) -> Self:
        request_id = self.context.purchase_request_id
        if self.intent.purchase_request_id != request_id or self.order.purchase_request_id != request_id:
            raise ValueError("intent, order and context must reference the same purchase request")
        if self.intent.budget is not None:
            _same_currency(self.order.total.currency, (self.intent.budget.amount,))
        return self
