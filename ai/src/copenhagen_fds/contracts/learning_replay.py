"""Separate historical Feature knowledge from later learning eligibility."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.features import FEATURE_SPECS, FeatureTransformInput, RawFeatureVector, StreamCoverage
from copenhagen_fds.contracts.learning import LearningDecision, LearningInput
from copenhagen_fds.contracts.profiles import OriginValue
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Identifier, InputModel


class LearningReplayInput(InputModel):
    feature_input: FeatureTransformInput
    learning_input: LearningInput

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        feature = self.feature_input
        purchase = self.learning_input.purchase
        if set(feature.evaluation.requested_features) != set(FEATURE_SPECS):
            raise ValueError("learning replay requires the complete raw feature schema")
        if (feature.evaluation.target.user_id, feature.evaluation.request.order) != (purchase.user_id, purchase.order):
            raise ValueError("historical features and learning must describe the same purchase snapshot")
        if (feature.current_occurred_at, feature.current_available_at, feature.evaluation.evaluated_at) != (
            purchase.occurred_at, purchase.available_at, purchase.evaluated_at,
        ):
            raise ValueError("historical feature timestamps must match the learning purchase")
        if feature.current_attempt_id is not None:
            raise ValueError("purchase learning uses one purchase evaluation, not repeated retry samples")
        if feature.evaluation.evaluated_at >= self.learning_input.as_of:
            raise ValueError("historical feature evaluation must precede the learning cutoff")
        return self


class LearningSample(InputModel):
    schema_version: Literal["purchase-learning-sample-v1"] = "purchase-learning-sample-v1"
    origin: OriginValue
    source_reference: Identifier
    purchase_occurred_at: Timestamp
    purchase_available_at: Timestamp
    raw_features: RawFeatureVector
    eligibility: LearningDecision
    purchase_coverage: StreamCoverage
    attempt_coverage: StreamCoverage

    @model_validator(mode="after")
    def validate_alignment(self) -> Self:
        vector, decision = self.raw_features, self.eligibility
        if {feature.feature_id for feature in vector.features} != set(FEATURE_SPECS):
            raise ValueError("learning sample requires the complete raw feature schema")
        if (vector.target.user_id, vector.target.purchase_request_id, vector.target.order) != (
            decision.target.user_id, decision.target.purchase_request_id, decision.target.order,
        ):
            raise ValueError("sample features and eligibility must match the same purchase reference")
        if not self.purchase_occurred_at <= self.purchase_available_at <= vector.evaluated_at < decision.as_of:
            raise ValueError("sample must preserve occurrence, observation, evaluation and learning time order")
        for feature in vector.features:
            if feature.currency is not None and feature.currency != decision.target.currency:
                raise ValueError("sample features and learning currency must match")
        if decision.learning_amount is not None:
            amount = next((value for value in vector.features if value.feature_id == "amount_minor"), None)
            if amount is None or amount.value != decision.learning_amount.amount_minor:
                raise ValueError("learning amount must equal the historical whole purchase amount")
        for coverage in (self.purchase_coverage, self.attempt_coverage):
            if coverage.complete_until is not None and coverage.complete_until > vector.evaluated_at:
                raise ValueError("feature coverage cannot include later knowledge")
        return self
