"""Supplied Cold reference parameters, never fitted distributions or risk policy."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest, ScoringBasis
from copenhagen_fds.contracts.features import FEATURE_SCHEMA_VERSION, FEATURE_SPECS
from copenhagen_fds.contracts.profiles import BehaviorProfile, ProfileReference
from copenhagen_fds.contracts.results import EvaluationTarget, Timestamp
from copenhagen_fds.contracts.values import Array, CurrencyCode, Identifier, InputModel


class FeatureReference(InputModel):
    """Versioned inputs to a later deviation scorer; no score is computed here."""

    feature_id: Identifier
    unit: Identifier
    window_seconds: Annotated[int, Field(gt=0)] | None = None
    method: Literal["UPPER_DISTANCE", "ABSOLUTE_DISTANCE", "CIRCULAR_DISTANCE"]
    center: Annotated[float, Field(ge=0, allow_inf_nan=False)]
    scale: Annotated[float, Field(gt=0, allow_inf_nan=False)]
    period: Annotated[int, Field(gt=0)] | None = None


Parameters = Annotated[Array[FeatureReference], Field(min_length=1)]


def parameter_error(parameters: tuple[FeatureReference, ...]):
    """Shared by candidate diagnostics and strict selected-result validation."""
    if len({p.feature_id for p in parameters}) != len(parameters):
        return "INVALID_CONFIG"
    for parameter in sorted(parameters, key=lambda item: item.feature_id):
        spec = FEATURE_SPECS.get(parameter.feature_id)
        if spec is None:
            return "UNSUPPORTED_FEATURE"
        if (parameter.unit, parameter.window_seconds) != spec:
            return "UNIT_MISMATCH"
        period = {"kst_hour": 24, "kst_day_of_week": 7}.get(parameter.feature_id)
        if period is not None:
            if parameter.method != "CIRCULAR_DISTANCE" or parameter.period != period or parameter.center >= period:
                return "INVALID_CONFIG"
        elif parameter.method == "CIRCULAR_DISTANCE" or parameter.period is not None:
            return "INVALID_CONFIG"
    return None


class PopulationBaseline(InputModel):
    format_version: Literal["cold-reference-v1"] = "cold-reference-v1"
    profile: BehaviorProfile
    available_at: Timestamp
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    parameters: Parameters

    @model_validator(mode="after")
    def validate_availability(self) -> Self:
        if self.available_at < self.profile.generated_at:
            raise ValueError("population reference cannot be available before generation")
        return self


class InitialPolicy(InputModel):
    format_version: Literal["cold-reference-v1"] = "cold-reference-v1"
    policy_id: Identifier
    version: Identifier
    source_reference: Identifier
    origin: Literal["INITIAL_POLICY"] = "INITIAL_POLICY"
    validation_status: Literal["UNVALIDATED"] = "UNVALIDATED"
    currency: CurrencyCode
    feature_schema_version: Identifier
    generated_at: Timestamp
    available_at: Timestamp
    valid_from: Timestamp
    valid_until: Timestamp
    parameters: Parameters

    @model_validator(mode="after")
    def validate_dates(self) -> Self:
        if self.available_at < self.generated_at:
            raise ValueError("policy cannot be available before generation")
        if self.valid_until <= self.valid_from or self.valid_until <= self.generated_at:
            raise ValueError("policy validity interval must be ordered")
        return self


class BaselineSelectionRequest(InputModel):
    evaluation: BehaviorEvaluationRequest
    # Explicit null means this source is not configured; versions are never guessed.
    population_reference: ProfileReference | None
    policy_id: Identifier | None
    policy_version: Identifier | None

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        if self.evaluation.feature_schema_version != FEATURE_SCHEMA_VERSION:
            raise ValueError("unsupported Cold feature schema")
        if not set(self.evaluation.requested_features).issubset(FEATURE_SPECS):
            raise ValueError("unsupported requested Cold feature")
        if self.population_reference is not None and self.population_reference.scope != "POPULATION":
            raise ValueError("expected population reference must identify a population")
        if (self.policy_id is None) != (self.policy_version is None):
            raise ValueError("policy identity requires both ID and version or explicit nulls")
        return self


IssueCode = Literal[
    "MISSING", "INVALID_FORMAT", "INVALID_CONFIG", "UNSUPPORTED_FEATURE",
    "UNIT_MISMATCH", "VERSION_MISMATCH", "CURRENCY_MISMATCH", "REFERENCE_MISMATCH",
    "FUTURE", "EXPIRED", "FEATURE_MISSING", "INSUFFICIENT_SAMPLES",
]


class BaselineIssue(InputModel):
    source: Literal["POPULATION", "INITIAL_POLICY"]
    code: IssueCode


class SelectedFeatureBasis(InputModel):
    feature_id: Identifier
    status: Literal["SELECTED", "UNAVAILABLE"]
    parameter: FeatureReference | None = None
    basis: ScoringBasis | None = None
    origin: Literal["OBSERVED", "SYNTHETIC", "INITIAL_POLICY"] | None = None
    source_reference: Identifier | None = None
    validation_status: Literal["UNVALIDATED"] | None = None
    policy_id: Identifier | None = None
    generated_at: Timestamp | None = None
    available_at: Timestamp | None = None
    valid_from: Timestamp | None = None
    valid_until: Timestamp | None = None
    issues: Array[BaselineIssue] = ()

    @model_validator(mode="after")
    def validate_selection(self) -> Self:
        selected = self.status == "SELECTED"
        fields = (self.parameter, self.basis, self.origin, self.source_reference,
                  self.validation_status, self.generated_at, self.available_at,
                  self.valid_from, self.valid_until)
        if selected and any(value is None for value in fields):
            raise ValueError("selected reference requires parameters and complete provenance")
        if not selected and (any(value is not None for value in fields) or self.policy_id is not None or not self.issues):
            raise ValueError("unavailable reference requires diagnostics and no applied basis")
        if selected:
            if self.parameter.feature_id != self.feature_id:
                raise ValueError("selected parameter must match the feature")
            if parameter_error((self.parameter,)) is not None:
                raise ValueError("selected parameter must use supported feature units and method")
            if self.basis.kind not in {"POPULATION_PROFILE", "INITIAL_POLICY"}:
                raise ValueError("Cold selection cannot apply a personal profile")
            if (self.basis.kind == "INITIAL_POLICY") != (self.origin == "INITIAL_POLICY"):
                raise ValueError("selected origin must match its basis kind")
            if (self.basis.kind == "INITIAL_POLICY") != (self.policy_id is not None):
                raise ValueError("selected policy must preserve its identity")
            if self.generated_at > self.available_at or self.valid_from >= self.valid_until:
                raise ValueError("selected source dates must be ordered")
        return self


class BaselineSelection(InputModel):
    selection_version: Literal["cold-basis-selection-v1"] = "cold-basis-selection-v1"
    target: EvaluationTarget
    evaluated_at: Timestamp
    feature_schema_version: Literal["cold-features-v1"] = FEATURE_SCHEMA_VERSION
    currency: CurrencyCode
    features: Annotated[Array[SelectedFeatureBasis], Field(min_length=1)]
    profiles: Array[BehaviorProfile] = ()

    @model_validator(mode="after")
    def validate_profiles(self) -> Self:
        if len({f.feature_id for f in self.features}) != len(self.features):
            raise ValueError("selected feature IDs must be unique")
        if len({p.reference for p in self.profiles}) != len(self.profiles):
            raise ValueError("selected profile references must be unique")
        by_reference = {p.reference: p for p in self.profiles}
        used = set()
        for feature in self.features:
            if feature.status == "SELECTED":
                if feature.valid_until <= self.evaluated_at or max(feature.available_at, feature.valid_from) > self.evaluated_at:
                    raise ValueError("selected reference must be known and valid at evaluation")
            if feature.basis is None or feature.basis.profile is None:
                continue
            reference = feature.basis.profile
            profile = by_reference.get(reference)
            if profile is None or feature.origin != profile.origin or feature.source_reference != profile.source_reference:
                raise ValueError("selected population must retain its profile provenance")
            profile.validate_for_evaluation(user_id=self.target.user_id, currency=self.currency,
                                            evaluated_at=self.evaluated_at,
                                            feature_schema_version=self.feature_schema_version)
            if feature.valid_until != profile.valid_until:
                raise ValueError("selected population must retain its expiry")
            if feature.generated_at != profile.generated_at or feature.valid_from != profile.generated_at:
                raise ValueError("selected population must retain its generation time")
            observation = next((o for o in profile.observations if o.feature_id == feature.feature_id), None)
            if observation is None or observation.valid_sample_count == 0:
                raise ValueError("selected population requires observations for its feature")
            used.add(reference)
        if used != set(by_reference):
            raise ValueError("selected profiles must be exactly those used")
        return self

    def validate_for_request(self, request: BaselineSelectionRequest) -> Self:
        evaluation = request.evaluation
        if self.target != evaluation.target or self.evaluated_at != evaluation.evaluated_at:
            raise ValueError("selection must match the evaluation target and time")
        if self.currency != evaluation.request.order.total.currency or self.feature_schema_version != evaluation.feature_schema_version:
            raise ValueError("selection must match the evaluation currency and schema")
        if {f.feature_id for f in self.features} != set(evaluation.requested_features):
            raise ValueError("selection must cover exactly the requested features")
        for feature in self.features:
            if feature.basis is None:
                continue
            if feature.basis.profile is not None and feature.basis.profile != request.population_reference:
                raise ValueError("selection must match the pinned population reference")
            if feature.basis.policy_version is not None and feature.basis.policy_version != request.policy_version:
                raise ValueError("selection must match the pinned policy version")
            if feature.policy_id is not None and feature.policy_id != request.policy_id:
                raise ValueError("selection must match the pinned policy identity")
        supplied = {p.reference.profile_id: p for p in evaluation.profiles}
        if any(p.reference.profile_id in supplied and supplied[p.reference.profile_id] != p for p in self.profiles):
            raise ValueError("selected profile conflicts with an already supplied snapshot")
        return self
