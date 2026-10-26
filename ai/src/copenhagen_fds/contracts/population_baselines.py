"""Auditable, provisional conversion of learned distributions to Story 4 parameters."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.baselines import FeatureReference, PopulationBaseline, parameter_error
from copenhagen_fds.contracts.features import FEATURE_SCHEMA_VERSION, FEATURE_SPECS
from copenhagen_fds.contracts.profiles import ProfileReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, CurrencyCode, Identifier, InputModel, Sha256


class PopulationBaselinePolicy(InputModel):
    version: Literal["population-cold-quantiles-v1"] = "population-cold-quantiles-v1"
    window: Literal["LONG_TERM"] = "LONG_TERM"
    numeric_method: Literal["UPPER_DISTANCE"] = "UPPER_DISTANCE"
    numeric_center: Literal["MEDIAN"] = "MEDIAN"
    numeric_scale: Literal["Q95_MINUS_MEDIAN"] = "Q95_MINUS_MEDIAN"
    circular_support: Literal["CONTIGUOUS_UNIMODAL_LESS_THAN_HALF_PERIOD"] = "CONTIGUOUS_UNIMODAL_LESS_THAN_HALF_PERIOD"
    circular_scale: Literal["WEIGHTED_DISTANCE_Q95"] = "WEIGHTED_DISTANCE_Q95"
    zero_scale: Literal["UNAVAILABLE"] = "UNAVAILABLE"


class PopulationBaselineRequest(InputModel):
    expected_source_reference: ProfileReference
    currency: CurrencyCode
    feature_schema_version: Identifier = FEATURE_SCHEMA_VERSION
    available_at: Timestamp


class DerivedFeatureReference(InputModel):
    feature_id: Identifier
    parameter: FeatureReference | None = None
    reason: Literal["EMPTY", "INSUFFICIENT_SAMPLES", "INSUFFICIENT_USERS", "ZERO_SCALE", "CIRCULAR_NOT_REPRESENTABLE"] | None = None

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if (self.parameter is None) == (self.reason is None):
            raise ValueError("each feature requires a valid parameter or an explicit unavailable reason")
        if self.parameter is not None and (self.parameter.feature_id != self.feature_id or parameter_error((self.parameter,))):
            raise ValueError("derived parameter must match its feature and the Story 4 contract")
        return self


class PopulationBaselineResult(InputModel):
    schema_version: Literal["population-baseline-derivation-v1"] = "population-baseline-derivation-v1"
    request: PopulationBaselineRequest
    policy: PopulationBaselinePolicy = PopulationBaselinePolicy()
    source_profile_reference: ProfileReference
    source_profile_sha256: Sha256
    status: Literal["COMPATIBLE", "PARTIAL", "UNAVAILABLE"]
    issue: Literal["REFERENCE_MISMATCH", "VERSION_MISMATCH", "CURRENCY_MISMATCH", "INVALID_AVAILABILITY"] | None = None
    features: Array[DerivedFeatureReference] = ()
    baseline: PopulationBaseline | None = None

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if self.issue is not None:
            if self.status != "UNAVAILABLE" or self.features or self.baseline is not None:
                raise ValueError("invalid context must not produce usable parameters")
            return self
        if [feature.feature_id for feature in self.features] != list(FEATURE_SPECS):
            raise ValueError("derivation must account for every Feature")
        parameters = tuple(feature.parameter for feature in self.features if feature.parameter is not None)
        expected = "COMPATIBLE" if len(parameters) == len(FEATURE_SPECS) else ("PARTIAL" if parameters else "UNAVAILABLE")
        if self.status != expected or bool(parameters) != (self.baseline is not None):
            raise ValueError("conversion status must reflect available parameters")
        if self.source_profile_reference != self.request.expected_source_reference:
            raise ValueError("successful conversion must match the pinned source reference")
        if self.baseline is not None:
            baseline = self.baseline
            profile = baseline.profile
            if baseline.parameters != parameters or parameter_error(parameters):
                raise ValueError("baseline must contain exactly the compatible parameters")
            if profile.reference.scope != "POPULATION" or profile.reference.cohort_id != self.source_profile_reference.cohort_id:
                raise ValueError("derived baseline must preserve its source cohort")
            if profile.currency != self.request.currency or profile.feature_schema_version != self.request.feature_schema_version:
                raise ValueError("baseline must match the requested currency and feature version")
            if not profile.generated_at <= baseline.available_at == self.request.available_at < profile.valid_until:
                raise ValueError("derived baseline availability must precede expiry")
            observations = {item.feature_id: item for item in profile.observations}
            if any(p.feature_id not in observations or not observations[p.feature_id].valid_sample_count for p in parameters):
                raise ValueError("each baseline parameter requires actual observation support")
        return self
