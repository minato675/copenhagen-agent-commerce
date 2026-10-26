"""Self-consistent native FDS advice and evidence, without executing a classifier."""

import math
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import AnomalyScore, CoverageValue, FeatureScoreResult
from copenhagen_fds.contracts.decisions import DecisionStatusValue
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.results import RiskValue, Timestamp
from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput
from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES, GroupId, RiskPolicyConfig, ScoreValue, Weight
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


Advice = Literal["NO_ADDITIONAL_REVIEW", "REVIEW_ORDER", "VERIFY_USER_INTENT", "REQUIRE_AUTH"]
Review = Literal["CHECK_INPUT", "RETRY_EVALUATION", "REVIEW_ORDER", "VERIFY_USER_INTENT", "REQUIRE_AUTH"]
RiskReason = Literal[
    "WITHIN_POLICY", "BEHAVIOR_DEVIATION", "INSUFFICIENT_DATA", "EVALUATION_FAILED",
    "NOT_EXECUTED", "INTENT_MISMATCH", "INTENT_UNKNOWN", "INTENT_FAILED", "INTENT_NOT_RUN", "RULE_TRIGGERED",
]
RISK_RANK = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def _equal(left: float, right: float) -> bool:
    # Zero is semantically distinct from any positive deviation.
    return (left == 0) == (right == 0) and math.isclose(left, right, rel_tol=1e-12, abs_tol=0)


class FeatureContribution(InputModel):
    feature: FeatureScoreResult
    weighted_points: ScoreValue | None

    @model_validator(mode="after")
    def validate_availability(self) -> Self:
        if self.feature.score is None and self.weighted_points is not None:
            raise ValueError("unscored features cannot claim even zero contribution")
        return self


class GroupContribution(InputModel):
    group_id: GroupId
    weight: Weight
    features: Annotated[Array[FeatureContribution], Field(min_length=1, max_length=6)]
    score: AnomalyScore | None
    weighted_points: ScoreValue | None

    @model_validator(mode="after")
    def validate_accounting(self) -> Self:
        if tuple(row.feature.feature_id for row in self.features) != GROUP_FEATURES[self.group_id]:
            raise ValueError("group evidence must cover its exact canonical feature set")
        complete = all(row.feature.score is not None for row in self.features)
        if complete != (self.score is not None) or complete != (self.weighted_points is not None):
            raise ValueError("group score and contribution require all member scores")
        if not complete:
            if any(row.weighted_points is not None for row in self.features):
                raise ValueError("partial group retains feature scores without fabricated contributions")
            return self
        representative = max(row.feature.score.value for row in self.features)
        if self.score.value != representative:
            raise ValueError("group score must equal its declared MAX representative")
        if any(row.weighted_points is None for row in self.features):
            raise ValueError("complete group requires explicit contribution for every member")
        expected_points = representative * self.weight
        if representative > 0 and expected_points == 0:
            expected_points = math.nextafter(0.0, 1.0)
        if not _equal(self.weighted_points, expected_points):
            raise ValueError("group weighted points must preserve its score and configured weight")
        if not _equal(math.fsum(row.weighted_points for row in self.features), self.weighted_points):
            raise ValueError("feature contributions must sum to their group contribution")
        if any(row.weighted_points > 0 and row.feature.score.value != representative for row in self.features):
            raise ValueError("only MAX representative features may contribute; ties may share points")
        return self


class PolicyReason(InputModel):
    code: RiskReason
    group_id: GroupId | None = None
    feature_id: Identifier | None = None
    rule_id: Identifier | None = None

    @model_validator(mode="after")
    def validate_references(self) -> Self:
        if self.feature_id is not None and self.feature_id not in GROUP_FEATURES.get(self.group_id, ()):
            raise ValueError("feature reasons require their containing group")
        if (self.code == "RULE_TRIGGERED") != (self.rule_id is not None):
            raise ValueError("only rule reasons require a rule identity")
        return self


class RuleObservation(InputModel):
    group_id: GroupId
    score: AnomalyScore


class RiskRuleEvidence(InputModel):
    rule_id: Identifier
    observations: Annotated[Array[RuleObservation], Field(min_length=1, max_length=3)]
    minimum_risk: Literal["MEDIUM", "HIGH"]

    @model_validator(mode="after")
    def validate_groups(self) -> Self:
        ids = [row.group_id for row in self.observations]
        if ids != [name for name in GROUP_FEATURES if name in ids]:
            raise ValueError("rule observations must use distinct ordered groups")
        return self


class NativeFraudDecision(InputModel):
    """FDS review advice only; it neither grants nor overrides BE authorization."""

    schema_version: Literal["native-fraud-decision-v1"] = "native-fraud-decision-v1"
    input_reference: RiskInputReference
    policy: RiskPolicyConfig
    policy_sha256: Sha256
    status: DecisionStatusValue
    evaluated_at: Timestamp | None
    evaluation_scope: Literal["FULL", "PARTIAL", "UNAVAILABLE"]
    score_coverage: CoverageValue
    groups: Array[GroupContribution]
    behavior_score: AnomalyScore | None
    risk_level: RiskValue
    recommendation: Advice | None
    reasons: Annotated[Array[PolicyReason], Field(min_length=1)]
    review_actions: Array[Review]
    rule_evidence: Array[RiskRuleEvidence] = ()

    @model_validator(mode="after")
    def validate_state(self) -> Self:
        if self.policy_sha256 != personalization_hash(self.policy):
            raise ValueError("decision must retain the exact policy content hash")
        if len(set(self.reasons)) != len(self.reasons) or len(set(self.review_actions)) != len(self.review_actions):
            raise ValueError("decision reasons and review actions must be unique")
        codes = {reason.code for reason in self.reasons}
        not_run = self.status == "NOT_RUN"
        if not_run != (self.evaluated_at is None):
            raise ValueError("decision timestamp is required exactly when policy has run")
        if not_run:
            if self.groups or self.rule_evidence or codes != {"NOT_EXECUTED"}:
                raise ValueError("NOT_RUN cannot contain computed policy evidence")
        elif self.evaluated_at != self.input_reference.evaluated_at:
            raise ValueError("decision must use its pinned evaluation time")
        if not not_run and [g.group_id for g in self.groups] != list(GROUP_FEATURES):
            raise ValueError("executed decision must retain all configured groups, including missing ones")
        for group, setting in zip(self.groups, self.policy.groups):
            if group.weight != setting.weight:
                raise ValueError("contribution weight must match its configured group weight")
        rows = [row for group in self.groups for row in group.features]
        scored = sum(row.feature.score is not None for row in rows)
        coverage = "UNAVAILABLE" if not scored else "COMPLETE" if scored == 9 else "PARTIAL"
        if self.score_coverage != coverage:
            raise ValueError("score coverage must describe the nine configured feature scores")
        complete = coverage == "COMPLETE"
        if complete != (self.behavior_score is not None):
            raise ValueError("total score requires complete coverage; missing is never zero or renormalized")
        if complete and not _equal(self.behavior_score.value, min(100.0, math.fsum(g.weighted_points for g in self.groups))):
            raise ValueError("total behavior score must equal the recorded group contributions")
        required = {f.feature_id for g in self.policy.groups for f in g.features if f.required}
        available = {row.feature.feature_id for row in rows if row.feature.score is not None}
        scope = "FULL" if required <= available else "PARTIAL" if available else "UNAVAILABLE"
        if self.evaluation_scope != scope:
            raise ValueError("evaluation scope must reflect required behavior signal availability")
        failures = {"EVALUATION_FAILED", "INTENT_FAILED"}
        if self.status == "SUCCESS":
            if scope != "FULL" or not complete or self.risk_level == "UNKNOWN" or self.recommendation is None:
                raise ValueError("SUCCESS requires complete scores, required behavior signals, known risk and advice")
            if codes & (failures | {"NOT_EXECUTED"}):
                raise ValueError("SUCCESS cannot hide execution failures")
            if self.recommendation == "NO_ADDITIONAL_REVIEW":
                if self.risk_level != "LOW" or self.review_actions or codes & {"INTENT_MISMATCH", "INTENT_UNKNOWN", "INTENT_NOT_RUN"}:
                    raise ValueError("no additional review requires LOW without unresolved intent or review actions")
            elif self.recommendation not in self.review_actions:
                raise ValueError("review recommendation must appear in review actions")
        else:
            if self.risk_level != "UNKNOWN" or self.recommendation is not None:
                raise ValueError("unresolved decisions require UNKNOWN risk and no conclusive recommendation")
            if not self.review_actions:
                raise ValueError("unresolved decisions require a next review action")
            if self.status == "FAILED" and not codes & failures:
                raise ValueError("FAILED requires an explicit execution failure reason")
            if self.status == "INCOMPLETE" and (codes & failures or not codes & {
                "INSUFFICIENT_DATA", "INTENT_UNKNOWN", "INTENT_NOT_RUN"
            }):
                raise ValueError("INCOMPLETE requires unresolved data or intent without hidden failures")
        self._validate_rules()
        return self

    def _validate_rules(self) -> None:
        ids = [row.rule_id for row in self.rule_evidence]
        if len(set(ids)) != len(ids) or set(ids) != {r.rule_id for r in self.reasons if r.code == "RULE_TRIGGERED"}:
            raise ValueError("rule evidence must uniquely match the reported rule reasons")
        configured = {rule.rule_id: rule for rule in self.policy.floor_rules}
        groups = {group.group_id: group for group in self.groups}
        for evidence in self.rule_evidence:
            rule = configured.get(evidence.rule_id)
            if rule is None or evidence.minimum_risk != rule.minimum_risk:
                raise ValueError("rule evidence must identify a configured risk floor")
            if [o.group_id for o in evidence.observations] != [c.group_id for c in rule.conditions]:
                raise ValueError("rule evidence must cover every configured condition")
            for observation, condition in zip(evidence.observations, rule.conditions):
                group = groups.get(observation.group_id)
                if group is None or group.score != observation.score or observation.score.value < condition.minimum_score:
                    raise ValueError("rule observation must preserve a qualifying group score")
            if self.status == "SUCCESS" and RISK_RANK[self.risk_level] < RISK_RANK[rule.minimum_risk]:
                raise ValueError("known risk cannot be below an evidenced rule floor")

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        if self.input_reference != RiskInputReference.from_input(supplied) or self.policy != supplied.policy:
            raise ValueError("decision must bind the exact input, profile versions and policy configuration")
        if self.status == "NOT_RUN":
            return self
        behavior = supplied.behavior.result.behavior if supplied.behavior is not None else None
        originals = {f.feature_id: f for f in behavior.features} if behavior is not None else {}
        for group in self.groups:
            for row in group.features:
                original = originals.get(row.feature.feature_id)
                if original is not None and original != row.feature:
                    raise ValueError("decision must preserve the original final feature score and evidence")
                if original is None and row.feature.status != "NOT_RUN":
                    raise ValueError("unexecuted features must remain NOT_RUN without invented scores")
        failed = (behavior is not None and behavior.status == "FAILED") or supplied.intent.status == "FAILED"
        codes = {r.code for r in self.reasons}
        if (self.status == "FAILED") != failed:
            raise ValueError("decision must preserve component execution failure independently of coverage")
        if behavior is not None and behavior.status == "FAILED" and "EVALUATION_FAILED" not in codes:
            raise ValueError("behavior failure requires its explicit reason")
        intent_code = {"FAILED": "INTENT_FAILED", "NOT_RUN": "INTENT_NOT_RUN"}.get(supplied.intent.status)
        if supplied.intent.payload is not None:
            intent_code = {"MISMATCH": "INTENT_MISMATCH", "UNKNOWN": "INTENT_UNKNOWN"}.get(supplied.intent.payload.comparison)
        expected_codes = set() if intent_code is None else {intent_code}
        if codes & {"INTENT_MISMATCH", "INTENT_UNKNOWN", "INTENT_FAILED", "INTENT_NOT_RUN"} != expected_codes:
            raise ValueError("decision intent reasons must preserve the supplied semantic and execution state")
        if self.status == "SUCCESS" and self.policy.intent_required and intent_code in {"INTENT_UNKNOWN", "INTENT_NOT_RUN"}:
            raise ValueError("required unresolved intent prevents a complete decision")
        if self.status == "INCOMPLETE" and self.score_coverage == "COMPLETE" and not (
            self.policy.intent_required and intent_code in {"INTENT_UNKNOWN", "INTENT_NOT_RUN"}
        ):
            raise ValueError("INCOMPLETE requires an unavailable score or unresolved required intent")
        if self.status == "SUCCESS" and self.behavior_score is not None:
            value = self.behavior_score.value
            floor = "HIGH" if value >= self.policy.thresholds.high_from else (
                "MEDIUM" if value >= self.policy.thresholds.medium_from else "LOW")
            if RISK_RANK[self.risk_level] < RISK_RANK[floor]:
                raise ValueError("known risk cannot discount the recorded behavior score")
        return self
