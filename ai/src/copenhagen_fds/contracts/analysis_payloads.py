"""Model-owned content only: no trusted IDs, versions, execution state or scores."""

from typing import Literal, Self

from pydantic import model_validator
from pydantic_core import PydanticCustomError

from copenhagen_fds.contracts.inputs import FraudEvaluationRequest, IntentBudget
from copenhagen_fds.contracts.results import MatchValue
from copenhagen_fds.contracts.statuses import IntentMatch
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, PositiveQuantity, Text


class ExtractedIntentItem(InputModel):
    name: Text
    quantity: PositiveQuantity | None
    brand: Text | None
    category: Text | None


class IntentExtractionOutput(InputModel):
    """Every wire field is required; null records information not supplied."""

    items: Array[ExtractedIntentItem] | None
    budget: IntentBudget | None
    purpose: Text | None
    explicit_conditions: Array[Text] | None
    preferences: Array[Text] | None
    ambiguities: Array[Text] | None


class OrderItemReference(InputModel):
    order_id: Identifier
    item_id: Identifier


class ComparisonFinding(InputModel):
    code: Literal["INTENT_MISMATCH", "INTENT_UNCERTAIN", "INSUFFICIENT_DATA"]
    dimension: Literal[
        "PRODUCT", "BRAND", "CATEGORY", "PURPOSE", "QUANTITY", "BUDGET",
        "CONDITION", "SUBSTITUTION",
    ]
    related_items: Array[OrderItemReference]


class ConfirmationRequest(InputModel):
    code: Literal[
        "CLARIFY_INTENT", "CONFIRM_SUBSTITUTION", "PROVIDE_MISSING_DATA", "RESOLVE_CONFLICT",
    ]
    related_items: Array[OrderItemReference]


class IntentComparisonOutput(InputModel):
    comparison: MatchValue
    findings: Array[ComparisonFinding]
    confirmation_requests: Array[ConfirmationRequest]

    @model_validator(mode="after")
    def validate_findings(self) -> Self:
        codes = {finding.code for finding in self.findings}
        if self.comparison == IntentMatch.MATCH:
            if self.findings or self.confirmation_requests:
                raise PydanticCustomError("comparison_state", "MATCH cannot contain unresolved findings or confirmations")
        elif self.comparison == IntentMatch.MISMATCH:
            if "INTENT_MISMATCH" not in codes:
                raise PydanticCustomError("comparison_state", "MISMATCH requires an intent mismatch finding")
        elif not codes or "INTENT_MISMATCH" in codes:
            raise PydanticCustomError("comparison_state", "UNKNOWN requires uncertainty findings without a definite mismatch")
        for entry in (*self.findings, *self.confirmation_requests):
            if len(set(entry.related_items)) != len(entry.related_items):
                raise PydanticCustomError("duplicate_reference", "related order item references must be unique within each entry")
        return self

    def validate_for_request(self, request: FraudEvaluationRequest) -> Self:
        available = {
            (order.order_id, item.item_id)
            for order in request.order.orders
            for item in order.items
        }
        for entry in (*self.findings, *self.confirmation_requests):
            if any((ref.order_id, ref.item_id) not in available for ref in entry.related_items):
                raise ValueError("analysis references an item outside the supplied order snapshot")
        return self


def analysis_response_format(stage: Literal["extraction", "comparison"]) -> dict:
    """A fresh Chat Completions response_format; gateway support is not assumed."""
    models = {"extraction": IntentExtractionOutput, "comparison": IntentComparisonOutput}
    if stage not in models:
        raise ValueError("analysis stage must be extraction or comparison")
    return {
        "type": "json_schema",
        "json_schema": {
            "name": f"fds_intent_{stage}_v1",
            "strict": True,
            "schema": models[stage].model_json_schema(),
        },
    }
