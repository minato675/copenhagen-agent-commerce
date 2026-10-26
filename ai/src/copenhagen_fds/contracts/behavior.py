"""Behavior evaluation boundaries; no feature extraction, scoring or policy."""

from enum import StrEnum
from typing import Annotated, Literal, Self

from pydantic import BeforeValidator, Field, model_validator

from copenhagen_fds.contracts.inputs import FraudEvaluationRequest
from copenhagen_fds.contracts.profiles import BehaviorProfile, ProfileReference, ProfileScope
from copenhagen_fds.contracts.results import ComponentResult, EvaluationTarget, Timestamp
from copenhagen_fds.contracts.statuses import EvaluationStatus, ReasonCode
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, enum_parser


class FeatureScoreStatus(StrEnum):
    SCORED = "SCORED"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    NOT_COLLECTED = "NOT_COLLECTED"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    FAILED = "FAILED"
    NOT_RUN = "NOT_RUN"


class BehaviorCoverage(StrEnum):
    """Availability of requested feature scores, never a confidence probability."""

    COMPLETE = "COMPLETE"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"


class BasisKind(StrEnum):
    INITIAL_POLICY = "INITIAL_POLICY"
    POPULATION_PROFILE = "POPULATION_PROFILE"
    PERSONAL_PROFILE = "PERSONAL_PROFILE"


class FeatureReasonCode(StrEnum):
    WITHIN_REFERENCE = "WITHIN_REFERENCE"
    PATTERN_DEVIATION = "PATTERN_DEVIATION"
    INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY"
    MISSING_INPUT = "MISSING_INPUT"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    REFERENCE_UNAVAILABLE = "REFERENCE_UNAVAILABLE"
    CALCULATION_FAILED = "CALCULATION_FAILED"
    NOT_EXECUTED = "NOT_EXECUTED"


FeatureStatusValue = Annotated[FeatureScoreStatus, BeforeValidator(enum_parser(FeatureScoreStatus))]
CoverageValue = Annotated[BehaviorCoverage, BeforeValidator(enum_parser(BehaviorCoverage))]
BasisValue = Annotated[BasisKind, BeforeValidator(enum_parser(BasisKind))]
FeatureReasonValue = Annotated[FeatureReasonCode, BeforeValidator(enum_parser(FeatureReasonCode))]


class AnomalyScore(InputModel):
    """Feature deviation on an explicit 0..100 scale, not fraud probability."""

    value: Annotated[float, Field(ge=0.0, le=100.0, allow_inf_nan=False)]
    unit: Literal["BEHAVIOR_ANOMALY_0_100"]


class ScoringBasis(InputModel):
    kind: BasisValue
    profile: ProfileReference | None = None
    policy_version: Identifier | None = None

    @model_validator(mode="after")
    def validate_basis(self) -> Self:
        if self.kind == BasisKind.INITIAL_POLICY:
            if self.profile is not None or self.policy_version is None:
                raise ValueError("initial policy requires a policy version and no profile")
        else:
            if self.profile is None or self.policy_version is not None:
                raise ValueError("profile basis requires a profile reference and no policy version")
            expected = (
                ProfileScope.PERSONAL
                if self.kind == BasisKind.PERSONAL_PROFILE else ProfileScope.POPULATION
            )
            if self.profile.scope != expected:
                raise ValueError("basis kind must match the profile scope")
        return self


class FeatureScoreResult(InputModel):
    feature_id: Identifier
    status: FeatureStatusValue
    score: AnomalyScore | None = None
    bases: Array[ScoringBasis] = ()
    reasons: Annotated[Array[FeatureReasonValue], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_score_state(self) -> Self:
        scored = self.status == FeatureScoreStatus.SCORED
        if scored != (self.score is not None):
            raise ValueError("feature score is required exactly for SCORED")
        if scored and not self.bases:
            raise ValueError("scored feature requires an explicit scoring basis")
        if self.status in {FeatureScoreStatus.NOT_RUN, FeatureScoreStatus.NOT_APPLICABLE} and self.bases:
            raise ValueError("unexecuted or inapplicable feature cannot claim an applied scoring basis")
        if len(set(self.bases)) != len(self.bases) or len(set(self.reasons)) != len(self.reasons):
            raise ValueError("feature bases and reason codes must be unique")
        expected_reasons = {
            FeatureScoreStatus.SCORED: {
                FeatureReasonCode.WITHIN_REFERENCE, FeatureReasonCode.PATTERN_DEVIATION,
            },
            FeatureScoreStatus.INSUFFICIENT_DATA: {
                FeatureReasonCode.INSUFFICIENT_HISTORY, FeatureReasonCode.REFERENCE_UNAVAILABLE,
            },
            FeatureScoreStatus.NOT_COLLECTED: {FeatureReasonCode.MISSING_INPUT},
            FeatureScoreStatus.NOT_APPLICABLE: {FeatureReasonCode.NOT_APPLICABLE},
            FeatureScoreStatus.FAILED: {FeatureReasonCode.CALCULATION_FAILED},
            FeatureScoreStatus.NOT_RUN: {FeatureReasonCode.NOT_EXECUTED},
        }
        if not set(self.reasons).issubset(expected_reasons[self.status]):
            raise ValueError("feature reasons must match the score status")
        if scored and len(self.reasons) != 1:
            raise ValueError("scored feature must identify either reference match or deviation")
        return self


class BehaviorEvaluationRequest(InputModel):
    """A supplied purchase and profile snapshots, without querying their source."""

    request: FraudEvaluationRequest
    evaluated_at: Timestamp
    feature_schema_version: Identifier
    requested_features: Annotated[Array[Identifier], Field(min_length=1)]
    profiles: Array[BehaviorProfile] = ()

    @model_validator(mode="after")
    def validate_profiles(self) -> Self:
        if len(set(self.requested_features)) != len(self.requested_features):
            raise ValueError("requested feature IDs must be unique")
        _unique_profiles(self.profiles)
        for profile in self.profiles:
            profile.validate_for_evaluation(
                user_id=self.request.context.user_id,
                currency=self.request.order.total.currency,
                evaluated_at=self.evaluated_at,
                feature_schema_version=self.feature_schema_version,
            )
        return self

    @property
    def target(self) -> EvaluationTarget:
        return EvaluationTarget(
            evaluation_id=self.request.context.evaluation_id,
            user_id=self.request.context.user_id,
            purchase_request_id=self.request.context.purchase_request_id,
            order=self.request.order.reference,
            intent_version=self.request.intent.version,
        )


def _unique_profiles(profiles: tuple[BehaviorProfile, ...]) -> None:
    if len({p.reference.profile_id for p in profiles}) != len(profiles):
        raise ValueError("profile IDs must be unique within an evaluation")


class BehaviorEvaluationResult(ComponentResult):
    """Component execution and score coverage; contains no risk decision."""

    feature_schema_version: Identifier
    requested_features: Annotated[Array[Identifier], Field(min_length=1)]
    scoring_version: Identifier | None = None
    coverage: CoverageValue
    features: Array[FeatureScoreResult] = ()
    profiles: Array[BehaviorProfile] = ()

    @model_validator(mode="after")
    def validate_behavior(self) -> Self:
        requested = set(self.requested_features)
        returned = {f.feature_id for f in self.features}
        if len(requested) != len(self.requested_features):
            raise ValueError("requested feature IDs must be unique")
        if len(returned) != len(self.features):
            raise ValueError("feature IDs must be unique")
        if not returned.issubset(requested):
            raise ValueError("returned features must belong to the requested feature set")
        _unique_profiles(self.profiles)
        scored = sum(f.status == FeatureScoreStatus.SCORED for f in self.features)
        expected_coverage = (
            BehaviorCoverage.UNAVAILABLE if not scored
            else BehaviorCoverage.COMPLETE if scored == len(requested)
            else BehaviorCoverage.PARTIAL
        )
        if self.coverage != expected_coverage:
            raise ValueError("coverage must reflect the available feature scores")
        if self.status == EvaluationStatus.SUCCESS:
            if returned != requested or self.scoring_version is None:
                raise ValueError("successful execution requires every requested feature and a scoring version")
            if any(f.status in {FeatureScoreStatus.FAILED, FeatureScoreStatus.NOT_RUN} for f in self.features):
                raise ValueError("successful execution cannot contain failed or unexecuted features")
            if self.coverage != BehaviorCoverage.COMPLETE:
                if ReasonCode.INSUFFICIENT_DATA not in {r.code for r in self.reasons}:
                    raise ValueError("incomplete coverage requires INSUFFICIENT_DATA")
                if self.metadata.valid_until is not None:
                    raise ValueError("incomplete coverage cannot advertise a validity window")
        elif self.status == EvaluationStatus.NOT_RUN:
            if self.scoring_version is not None or self.profiles:
                raise ValueError("unexecuted evaluation cannot claim applied versions or profiles")
            if any(f.status != FeatureScoreStatus.NOT_RUN or f.bases for f in self.features):
                raise ValueError("unexecuted evaluation cannot contain executed features or bases")
        if scored and self.scoring_version is None:
            raise ValueError("computed scores require a scoring version even on partial failure")

        by_reference = {p.reference: p for p in self.profiles}
        used = set()
        for profile in self.profiles:
            profile.validate_for_evaluation(
                user_id=self.metadata.target.user_id,
                currency=profile.currency,
                evaluated_at=self.metadata.evaluated_at,
                feature_schema_version=self.feature_schema_version,
            )
        for feature in self.features:
            for basis in feature.bases:
                if basis.profile is None:
                    continue
                profile = by_reference.get(basis.profile)
                if profile is None:
                    raise ValueError("feature basis requires the exact referenced profile version")
                used.add(basis.profile)
                observation = next((o for o in profile.observations if o.feature_id == feature.feature_id), None)
                if observation is None:
                    raise ValueError("profile basis must describe the referenced feature")
                if feature.status == FeatureScoreStatus.SCORED and observation.valid_sample_count == 0:
                    raise ValueError("profile scoring requires observed samples")
                until = self.metadata.valid_until
                if until is not None and until > profile.valid_until:
                    raise ValueError("evaluation validity cannot outlive a used profile")
        if used != set(by_reference):
            raise ValueError("result profiles must be exactly those used by its feature bases")
        return self

    def validate_for_request(self, request: BehaviorEvaluationRequest) -> Self:
        """Check the separately decoded target and the exact supplied snapshots."""

        if self.metadata.target != request.target:
            raise ValueError("behavior result must match the supplied evaluation target")
        if self.feature_schema_version != request.feature_schema_version:
            raise ValueError("behavior result must match the requested feature schema version")
        if set(self.requested_features) != set(request.requested_features):
            raise ValueError("behavior result must match the requested feature set")
        if self.status != EvaluationStatus.NOT_RUN and self.metadata.evaluated_at != request.evaluated_at:
            raise ValueError("behavior result must match the requested evaluation time")
        supplied = {p.reference: p for p in request.profiles}
        for profile in self.profiles:
            if supplied.get(profile.reference) != profile:
                raise ValueError("behavior result must use the exact supplied profile snapshot")
        return self
