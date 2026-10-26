"""Behavior profile references and observation metadata, without fitted statistics."""

from enum import StrEnum
from typing import Annotated, Self

from pydantic import BeforeValidator, Field, model_validator

from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import (
    Array,
    CurrencyCode,
    Identifier,
    InputModel,
    enum_parser,
)


class ProfileScope(StrEnum):
    PERSONAL = "PERSONAL"
    POPULATION = "POPULATION"


class ProfileOrigin(StrEnum):
    OBSERVED = "OBSERVED"
    SYNTHETIC = "SYNTHETIC"


ScopeValue = Annotated[ProfileScope, BeforeValidator(enum_parser(ProfileScope))]
OriginValue = Annotated[ProfileOrigin, BeforeValidator(enum_parser(ProfileOrigin))]


class ProfileReference(InputModel):
    """Supplied identity and version; population membership is checked by the BE."""

    profile_id: Identifier
    version: Identifier
    scope: ScopeValue
    user_id: Identifier | None = None
    cohort_id: Identifier | None = None

    @model_validator(mode="after")
    def validate_owner(self) -> Self:
        if self.scope == ProfileScope.PERSONAL:
            if self.user_id is None or self.cohort_id is not None:
                raise ValueError("personal profile requires user_id and forbids cohort_id")
        elif self.cohort_id is None or self.user_id is not None:
            raise ValueError("population profile requires cohort_id and forbids user_id")
        return self


class FeatureObservation(InputModel):
    """Availability metadata for one feature, not its value or confidence."""

    feature_id: Identifier
    valid_sample_count: Annotated[int, Field(ge=0)]
    observed_from: Timestamp | None = None
    last_observed_at: Timestamp | None = None

    @model_validator(mode="after")
    def validate_observation_window(self) -> Self:
        if self.valid_sample_count == 0:
            if self.observed_from is not None or self.last_observed_at is not None:
                raise ValueError("zero samples cannot have observation timestamps")
        elif self.observed_from is None or self.last_observed_at is None:
            raise ValueError("positive samples require both observation timestamps")
        elif self.observed_from > self.last_observed_at:
            raise ValueError("observed_from must not be later than last_observed_at")
        return self


class _ProfileEvaluationContext(InputModel):
    user_id: Identifier
    currency: CurrencyCode
    evaluated_at: Timestamp
    expected_reference: ProfileReference | None
    feature_schema_version: Identifier | None


class BehaviorProfile(InputModel):
    """Provenance and per-feature coverage; no aggregation or scoring is performed."""

    reference: ProfileReference
    origin: OriginValue
    source_reference: Identifier
    feature_schema_version: Identifier
    currency: CurrencyCode
    as_of: Timestamp
    generated_at: Timestamp
    valid_until: Timestamp
    observations: Array[FeatureObservation]

    @model_validator(mode="after")
    def validate_profile_window(self) -> Self:
        if self.generated_at < self.as_of:
            raise ValueError("generated_at must not be earlier than as_of")
        if self.valid_until <= self.generated_at:
            raise ValueError("valid_until must be later than generated_at")
        features = [observation.feature_id for observation in self.observations]
        if len(set(features)) != len(features):
            raise ValueError("feature IDs must be unique within a profile")
        if any(
            observation.last_observed_at is not None
            and observation.last_observed_at >= self.as_of
            for observation in self.observations
        ):
            raise ValueError("observation timestamps must be earlier than as_of")
        return self

    def validate_for_evaluation(
        self,
        *,
        user_id: Identifier,
        currency: CurrencyCode,
        evaluated_at: Timestamp,
        expected_reference: ProfileReference | None = None,
        feature_schema_version: Identifier | None = None,
    ) -> Self:
        """Check supplied evaluation context; this does not authenticate its caller."""

        context = _ProfileEvaluationContext(
            user_id=user_id,
            currency=currency,
            evaluated_at=evaluated_at,
            expected_reference=expected_reference,
            feature_schema_version=feature_schema_version,
        )
        if self.reference.scope == ProfileScope.PERSONAL and self.reference.user_id != context.user_id:
            raise ValueError("personal profile does not match the evaluation user")
        if self.currency != context.currency:
            raise ValueError("profile does not match the evaluation currency")
        if context.expected_reference is not None and self.reference != context.expected_reference:
            raise ValueError("profile does not match the expected reference")
        if context.feature_schema_version is not None and self.feature_schema_version != context.feature_schema_version:
            raise ValueError("profile does not match the expected feature schema version")
        if self.as_of > context.evaluated_at or self.generated_at > context.evaluated_at:
            raise ValueError("profile cannot be based on or generated after the evaluation time")
        if self.valid_until <= context.evaluated_at:
            raise ValueError("profile has expired at the evaluation time")
        return self
