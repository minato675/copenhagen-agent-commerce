"""Explainable feature mixtures; validates outputs without executing scoring or transition policy."""

import math
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest, BehaviorEvaluationResult, FeatureScoreResult
from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.personal_profiles import FeatureDistribution
from copenhagen_fds.contracts.personalization_config import FractionValue, PersonalizationConfig
from copenhagen_fds.contracts.personalization_inputs import (
    PersonalizedScoringInput, ProfileAvailability, personalization_hash,
)
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.scoring import ColdScoringResult, UNAVAILABLE_STATES
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


class FeatureWindowEvidence(InputModel):
    window: Literal["RECENT", "LONG_TERM"]
    start_inclusive: Timestamp
    end_exclusive: Timestamp
    distribution: FeatureDistribution

    @model_validator(mode="after")
    def validate_window(self) -> Self:
        observation = self.distribution.observation
        if self.start_inclusive >= self.end_exclusive:
            raise ValueError("statistical evidence requires an ordered window")
        if observation.valid_sample_count and not (
            self.start_inclusive <= observation.observed_from <= observation.last_observed_at < self.end_exclusive
        ):
            raise ValueError("feature observations must lie within the declared window")
        if observation.valid_sample_count and self.distribution.recency_seconds != (
            self.end_exclusive - observation.last_observed_at
        ).total_seconds():
            raise ValueError("stored recency is measured at the profile cutoff")
        return self


def personal_feature_evidence(supplied: PersonalizedScoringInput, feature_id: str) -> tuple[FeatureWindowEvidence, ...]:
    if feature_id not in supplied.cold.request.evaluation.requested_features:
        raise ValueError("feature evidence must belong to this evaluation")
    if supplied.personal is None:
        return ()
    profile = supplied.personal.state.published
    return tuple(FeatureWindowEvidence(window=name, start_inclusive=window.start_inclusive,
        end_exclusive=window.end_exclusive,
        distribution=next(f for f in window.features if f.feature_id == feature_id))
        for name, window in (("RECENT", profile.recent), ("LONG_TERM", profile.long_term)))


class PersonalizedFeatureResult(InputModel):
    cold: FeatureScoreResult
    personal: FeatureScoreResult
    combined: FeatureScoreResult
    stage: Literal["COLD", "TRANSITION", "PERSONALIZED", "UNAVAILABLE"]
    cold_weight: FractionValue | None = None
    personal_weight: FractionValue | None = None
    personal_window: Literal["RECENT", "LONG_TERM"] | None = None
    personal_score_version: Identifier | None = None
    evidence: Array[FeatureWindowEvidence] = ()
    stage_reasons: Annotated[Array[Identifier], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_mixture(self) -> Self:
        name = self.combined.feature_id
        if name not in FEATURE_SPECS or self.cold.feature_id != name or self.personal.feature_id != name:
            raise ValueError("all score components must describe the same supported feature")
        if any(b.kind == "PERSONAL_PROFILE" for b in self.cold.bases) or any(
            b.kind != "PERSONAL_PROFILE" for b in self.personal.bases
        ):
            raise ValueError("Cold and personal components must preserve their basis scopes")
        if len(self.personal.bases) > 1:
            raise ValueError("one personal component uses one published profile")
        if self.personal.status == "SCORED" and self.personal.reasons != (
            "WITHIN_REFERENCE" if self.personal.score.value == 0 else "PATTERN_DEVIATION",
        ):
            raise ValueError("personal score reason must match its value")
        attempted = bool(self.personal.bases)
        if attempted != (self.personal_window is not None) or attempted != (self.personal_score_version is not None):
            raise ValueError("personal calculation requires its window and score version")
        if self.evidence:
            if [e.window for e in self.evidence] != ["RECENT", "LONG_TERM"] or any(
                e.distribution.feature_id != name for e in self.evidence
            ):
                raise ValueError("personal evidence requires both ordered windows for this feature")
        if attempted:
            selected = next((e for e in self.evidence if e.window == self.personal_window), None)
            if selected is None or selected.distribution.observation.valid_sample_count == 0:
                raise ValueError("personal calculation needs observed evidence in its selected window")
        if len(set(self.stage_reasons)) != len(self.stage_reasons):
            raise ValueError("stage reasons must be unique")
        if self.combined.status != "SCORED":
            if self.stage != "UNAVAILABLE" or self.cold_weight is not None or self.personal_weight is not None:
                raise ValueError("unscored features cannot claim a mixture or a scoring stage")
            if self.combined.bases or self.combined.status == "NOT_RUN":
                raise ValueError("unscored combined output retains attempts in components, not applied bases")
            return self
        if self.cold_weight is None or self.personal_weight is None:
            raise ValueError("scored features require both explicit mixture weights")
        if self.cold_weight != 1.0 - self.personal_weight:
            raise ValueError("Cold weight must be the complement of personal weight")
        expected_stage = "COLD" if self.personal_weight == 0 else (
            "PERSONALIZED" if self.personal_weight == 1 else "TRANSITION"
        )
        if self.stage != expected_stage:
            raise ValueError("stage must describe the applied mixture weights")
        parts, bases = [], []
        for weight, component in ((self.cold_weight, self.cold), (self.personal_weight, self.personal)):
            if weight > 0:
                if component.status != "SCORED":
                    raise ValueError("a positive mixture weight requires an available component score")
                parts.append(weight * component.score.value)
                bases.extend(component.bases)
        # Convex mixtures stay in range even when binary multiplication rounds up.
        expected_score = min(100.0, math.fsum(parts))
        if expected_score == 0 and any(weight > 0 and component.score.value > 0
            for weight, component in ((self.cold_weight, self.cold), (self.personal_weight, self.personal))
            if component.score is not None):
            expected_score = math.nextafter(0.0, 1.0)
        if self.combined.score.value != expected_score or self.combined.bases != tuple(bases):
            raise ValueError("combined score and bases must equal the declared component mixture")
        expected_reason = "WITHIN_REFERENCE" if expected_score == 0 else "PATTERN_DEVIATION"
        if self.combined.reasons != (expected_reason,):
            raise ValueError("combined score reason must match its value")
        return self


class PersonalizedScoringResult(InputModel):
    schema_version: Literal["personalized-scoring-result-v1"] = "personalized-scoring-result-v1"
    input_sha256: Sha256
    config: PersonalizationConfig
    config_sha256: Sha256
    cold: ColdScoringResult
    behavior: BehaviorEvaluationResult
    profiles: Array[ProfileAvailability]
    features: Annotated[Array[PersonalizedFeatureResult], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_result(self) -> Self:
        if self.config_sha256 != personalization_hash(self.config):
            raise ValueError("personalization configuration must match its recorded hash")
        behavior, cold = self.behavior, self.cold.behavior
        if (behavior.metadata.target, behavior.metadata.evaluated_at, behavior.metadata.trace_id,
            behavior.feature_schema_version, behavior.requested_features) != (
            cold.metadata.target, cold.metadata.evaluated_at, cold.metadata.trace_id,
            cold.feature_schema_version, cold.requested_features
        ):
            raise ValueError("personal and Cold evaluation context must agree")
        if (behavior.scoring_version != "personalized-features-v1" or behavior.metadata.valid_until is not None
                or behavior.metadata.policy_version != self.config.transition_policy_version):
            raise ValueError("personal result requires its computation and transition versions, with point-in-time validity")
        expected_ids = [name for name in FEATURE_SPECS if name in behavior.requested_features]
        if [f.combined.feature_id for f in self.features] != expected_ids:
            raise ValueError("result must cover requested features in shared schema order")
        if behavior.features != tuple(f.combined for f in self.features):
            raise ValueError("common behavior output must equal the detailed combined features")
        if tuple(f.cold for f in self.features) != cold.features:
            raise ValueError("Cold components must preserve the supplied Cold result")
        if [p.scope for p in self.profiles] != ["PERSONAL", "POPULATION"]:
            raise ValueError("result must describe personal and population publication availability")
        for profile in self.profiles:
            if profile.context is None:
                continue
            at, context = behavior.metadata.evaluated_at, profile.context
            expected = "EXPIRED" if context.profile.valid_until <= at else "AVAILABLE"
            if (profile.status != expected or context.profile.as_of >= at or context.available_at > at):
                raise ValueError("profile availability must reflect the evaluation time")
        personal = self.profiles[0]
        configs = {f.feature_id: f for f in self.config.features}
        for feature in self.features:
            name = feature.combined.feature_id
            if name not in configs:
                raise ValueError("result feature requires its configured score version")
            if feature.personal.bases:
                if personal.status != "AVAILABLE" or feature.personal.bases[0].profile != personal.context.profile.reference:
                    raise ValueError("personal calculation requires the available published profile")
                if feature.personal_score_version != configs[name].score_version:
                    raise ValueError("personal calculation must use its configured score version")
            if personal.context is None and feature.evidence:
                raise ValueError("missing personal profile cannot claim observed evidence")
        failed = any(c.status == "FAILED" for f in self.features for c in (f.cold, f.personal, f.combined))
        if behavior.status != ("FAILED" if failed else "SUCCESS"):
            raise ValueError("component failures cannot be hidden in successful personalization")
        return self

    def validate_for_input(self, supplied: PersonalizedScoringInput) -> Self:
        """Required after decoding: structural validity alone does not bind a request."""
        if self.input_sha256 != personalization_hash(supplied) or self.config != supplied.config:
            raise ValueError("result must match the exact supplied input and configuration")
        self.cold.validate_for_input(supplied.cold)
        if self.profiles != supplied.profile_availability():
            raise ValueError("result must preserve exact publication evidence and availability")
        evaluation = supplied.cold.evaluation_with_selected_profiles()
        available = {p.reference: p for p in evaluation.profiles}
        for profile in self.profiles:
            if profile.status == "AVAILABLE":
                available[profile.context.profile.reference] = profile.context.profile
        request = BehaviorEvaluationRequest.model_validate({**evaluation.model_dump(), "profiles": list(available.values())})
        self.behavior.validate_for_request(request)
        raw_by_id = {raw.feature_id: raw for raw in supplied.cold.raw_features.features}
        for feature in self.features:
            raw = raw_by_id[feature.combined.feature_id]
            if feature.evidence != personal_feature_evidence(supplied, raw.feature_id):
                raise ValueError("feature evidence must equal the published personal distributions")
            unavailable = UNAVAILABLE_STATES.get(raw.status)
            if unavailable is not None:
                for component in (feature.personal, feature.combined):
                    if (component.status, component.reasons) != (unavailable[0], (unavailable[1],)) or component.bases:
                        raise ValueError("missing raw input must preserve its cause and cannot be scored")
        return self
