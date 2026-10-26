"""Caller-owned input and target binding for extraction and comparison."""

from typing import Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.analysis_payloads import IntentExtractionOutput
from copenhagen_fds.contracts.inputs import (
    EvaluationContext, FraudEvaluationRequest, IntentItem, IntentSource,
    OrderSnapshot, PurchaseIntent,
)
from copenhagen_fds.contracts.results import EvaluationTarget
from copenhagen_fds.contracts.values import Identifier, InputModel, Text


class UserRequestText(InputModel):
    purchase_request_id: Identifier
    source: IntentSource
    version: Identifier | None = None
    text: Text | None = Field(default=None, repr=False)

    def with_extracted_intent(self, output: IntentExtractionOutput) -> PurchaseIntent:
        """Bind extracted content without consulting the order or inferring currency."""
        if self.text is None:
            raise ValueError("extracted intent requires supplied request text")
        return PurchaseIntent(
            purchase_request_id=self.purchase_request_id, source=self.source, version=self.version,
            items=None if output.items is None else tuple(
                IntentItem(name=item.name, quantity=item.quantity, brand=item.brand, category=item.category)
                for item in output.items
            ),
            budget=output.budget, purpose=output.purpose,
            explicit_conditions=output.explicit_conditions,
            preferences=output.preferences, ambiguities=output.ambiguities,
        )


class IntentAnalysisRequest(InputModel):
    user_request: UserRequestText
    order: OrderSnapshot
    context: EvaluationContext

    @model_validator(mode="after")
    def validate_references(self) -> Self:
        if not (
            self.user_request.purchase_request_id
            == self.order.purchase_request_id
            == self.context.purchase_request_id
        ):
            raise ValueError("text, order and context must reference the same purchase request")
        return self

    @property
    def target(self) -> EvaluationTarget:
        return EvaluationTarget(
            evaluation_id=self.context.evaluation_id,
            user_id=self.context.user_id,
            purchase_request_id=self.context.purchase_request_id,
            order=self.order.reference,
            intent_version=self.user_request.version,
        )

    def with_extracted_intent(self, output: IntentExtractionOutput) -> FraudEvaluationRequest:
        """Attach content to caller-supplied provenance; this does not authenticate it."""
        intent = self.user_request.with_extracted_intent(output)
        return FraudEvaluationRequest(intent=intent, order=self.order, context=self.context)


def evaluation_target(request: FraudEvaluationRequest) -> EvaluationTarget:
    return EvaluationTarget(
        evaluation_id=request.context.evaluation_id,
        user_id=request.context.user_id,
        purchase_request_id=request.context.purchase_request_id,
        order=request.order.reference,
        intent_version=request.intent.version,
    )
