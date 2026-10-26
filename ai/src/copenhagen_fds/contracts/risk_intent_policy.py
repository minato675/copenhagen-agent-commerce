"""Intent-specific review advice alongside unchanged behavioral rule evidence."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_intent import RiskIntentSignal
from copenhagen_fds.contracts.risk_results import Review
from copenhagen_fds.contracts.risk_rules import RiskRuleResult
from copenhagen_fds.contracts.values import Array, InputModel


def intent_review_fields(intent: RiskIntentSignal, *, required: bool) -> dict:
    signal = intent.signal
    status = "FAILED" if signal == "FAILED" else (
        "INCOMPLETE" if required and signal in {"UNKNOWN", "NOT_RUN"} else "SUCCESS")
    actions = {"MATCH": (), "MISMATCH": ("REVIEW_ORDER", "VERIFY_USER_INTENT"),
               "UNKNOWN": ("CHECK_INPUT", "VERIFY_USER_INTENT"), "NOT_RUN": ("RETRY_EVALUATION",)}
    if signal == "FAILED":
        failure = intent.source.failure
        check_first = failure.retryable is False or failure.kind in {
            "INSUFFICIENT_INPUT", "INVALID_INPUT", "REFUSAL", "SCHEMA_ERROR", "INCOMPLETE_RESPONSE",
        } or failure.api_error in {"AUTHENTICATION", "PERMISSION", "REQUEST"}
        review = ("CHECK_INPUT", "RETRY_EVALUATION") if check_first else ("RETRY_EVALUATION",)
    else:
        review = actions[signal]
    return dict(status=status, review_actions=review)


class RiskIntentPolicyResult(InputModel):
    schema_version: Literal["risk-intent-policy-result-v1"] = "risk-intent-policy-result-v1"
    evaluation_version: Literal["intent-separate-review-v1"] = "intent-separate-review-v1"
    rules: RiskRuleResult
    intent: RiskIntentSignal
    # Intent availability only. Behavior failures and final risk remain in their own evidence.
    status: Literal["SUCCESS", "INCOMPLETE", "FAILED"]
    review_actions: Array[Review]

    @model_validator(mode="after")
    def validate_review(self) -> Self:
        coverage = self.rules.aggregation.assessment.coverage
        if self.intent.input_reference != coverage.input_reference:
            raise ValueError("intent advice and behavioral evidence must belong to the exact same input")
        for field, expected in intent_review_fields(self.intent, required=coverage.policy.intent_required).items():
            if getattr(self, field) != expected:
                raise ValueError("intent advice must preserve required uncertainty, failure and ordered review actions")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        self.rules.validate_for_input(supplied)
        self.intent.validate_for_input(supplied)
        return type(self).model_validate(self.model_dump())
