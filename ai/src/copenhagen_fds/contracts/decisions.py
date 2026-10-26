"""Consistency checks for supplied FDS advice; no risk policy is executed."""

from typing import Annotated, Self

from pydantic import BeforeValidator, Field, model_validator

from copenhagen_fds.contracts.inputs import FraudEvaluationRequest
from copenhagen_fds.contracts.results import (
    ComponentResult,
    EvaluationMetadata,
    EvaluationReason,
    EvaluationTarget,
    IntentAnalysisResult,
    RiskValue,
    RuleResult,
    SiftFraudResult,
)
from copenhagen_fds.contracts.statuses import (
    DecisionStatus,
    EvaluationComponent,
    EvaluationStatus,
    FdsRecommendation,
    IntentMatch,
    ReasonCode,
    ReviewAction,
    RiskLevel,
)
from copenhagen_fds.contracts.values import Array, InputModel, enum_parser

DecisionStatusValue = Annotated[DecisionStatus, BeforeValidator(enum_parser(DecisionStatus))]
ComponentValue = Annotated[EvaluationComponent, BeforeValidator(enum_parser(EvaluationComponent))]
RecommendationValue = Annotated[FdsRecommendation, BeforeValidator(enum_parser(FdsRecommendation))]
ReviewValue = Annotated[ReviewAction, BeforeValidator(enum_parser(ReviewAction))]


class FraudDecision(InputModel):
    """A scoped evaluation recommendation; BE owns authorization and execution."""

    status: DecisionStatusValue
    metadata: EvaluationMetadata
    required_components: Annotated[Array[ComponentValue], Field(min_length=1)]
    intent: IntentAnalysisResult
    sift: SiftFraudResult
    rules: Array[RuleResult]
    risk_level: RiskValue
    recommendation: RecommendationValue | None = None
    reasons: Array[EvaluationReason]
    review_actions: Array[ReviewValue]

    def validate_for_request(self, request: FraudEvaluationRequest) -> Self:
        """Bind separately decoded contracts by supplied IDs and versions only."""

        expected = EvaluationTarget(
            evaluation_id=request.context.evaluation_id,
            user_id=request.context.user_id,
            purchase_request_id=request.context.purchase_request_id,
            order=request.order.reference,
            intent_version=request.intent.version,
        )
        if self.metadata.target != expected:
            raise ValueError("decision target must match the supplied evaluation request")
        return self

    @model_validator(mode="after")
    def validate_consistency(self) -> Self:
        components = (self.intent, self.sift, *self.rules)
        if len(set(self.required_components)) != len(self.required_components):
            raise ValueError("required_components must be unique")
        if len({rule.rule_id for rule in self.rules}) != len(self.rules):
            raise ValueError("rule IDs must be unique")
        for component in components:
            if component.metadata.target != self.metadata.target:
                raise ValueError("component results must share the decision evaluation target")
            if component.metadata.trace_id != self.metadata.trace_id:
                raise ValueError("component results must share the decision trace")
            evaluated_at = component.metadata.evaluated_at
            if evaluated_at is not None and self.metadata.evaluated_at is not None:
                if evaluated_at > self.metadata.evaluated_at:
                    raise ValueError("component cannot be evaluated after the decision")

        def is_available(result: ComponentResult) -> bool:
            until = result.metadata.valid_until
            return result.status == EvaluationStatus.SUCCESS and (
                until is None
                or self.metadata.evaluated_at is None
                or self.metadata.evaluated_at < until
            )

        ready = {
            EvaluationComponent.INTENT: (
                is_available(self.intent)
                and self.intent.comparison != IntentMatch.UNKNOWN
            ),
            EvaluationComponent.SIFT: is_available(self.sift),
            EvaluationComponent.RULES: bool(self.rules) and all(
                is_available(rule) and rule.risk_level != RiskLevel.UNKNOWN
                for rule in self.rules
            ),
        }
        all_required_ready = all(ready[component] for component in self.required_components)
        not_run = self.status == DecisionStatus.NOT_RUN
        if not_run != (self.metadata.evaluated_at is None):
            raise ValueError("evaluated_at is required exactly when decision has run")
        if not_run and any(c.status != EvaluationStatus.NOT_RUN for c in components):
            raise ValueError("NOT_RUN decision cannot contain evaluated components")
        if not not_run and self.metadata.policy_version is None:
            raise ValueError("decision requires the applied policy version")

        if self.status == DecisionStatus.SUCCESS:
            if not all_required_ready:
                raise ValueError("SUCCESS requires all required evaluations to be available and known")
            if self.risk_level == RiskLevel.UNKNOWN or self.recommendation is None:
                raise ValueError("SUCCESS requires known risk and an explicit recommendation")
            required_results = {
                EvaluationComponent.INTENT: (self.intent,),
                EvaluationComponent.SIFT: (self.sift,),
                EvaluationComponent.RULES: self.rules,
            }
            for key in self.required_components:
                for result in required_results[key]:
                    until = result.metadata.valid_until
                    if until is not None:
                        if self.metadata.evaluated_at >= until:
                            raise ValueError("required evaluation has expired at decision time")
                        if self.metadata.valid_until is None or self.metadata.valid_until > until:
                            raise ValueError("decision validity cannot outlive required evaluations")
        else:
            if self.risk_level != RiskLevel.UNKNOWN or self.recommendation is not None:
                raise ValueError("unresolved decision requires UNKNOWN risk and no recommendation")
            if not self.reasons or self.metadata.valid_until is not None:
                raise ValueError("unresolved decision requires reasons and no validity window")
            if self.status == DecisionStatus.INCOMPLETE and all_required_ready:
                raise ValueError("INCOMPLETE requires unavailable or unknown required evaluations")
            codes = {reason.code for reason in self.reasons}
            failures = {ReasonCode.EVALUATION_FAILED, ReasonCode.TIMEOUT, ReasonCode.INVALID_RESPONSE}
            if self.status == DecisionStatus.FAILED and not codes.intersection(failures):
                raise ValueError("FAILED decision requires a failure reason code")
            if not_run and (ReasonCode.NOT_EXECUTED not in codes or codes.intersection(failures)):
                raise ValueError("NOT_RUN decision requires NOT_EXECUTED without failure reasons")
        return self
