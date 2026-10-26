"""Cold scoring connects explicit raw values, selected sources and shared results."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.baselines import BaselineSelection, BaselineSelectionRequest
from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest, BehaviorEvaluationResult
from copenhagen_fds.contracts.features import RawFeatureVector
from copenhagen_fds.contracts.values import Array, Identifier, InputModel
from copenhagen_fds.scoring import SCORING_VERSION


UNAVAILABLE_STATES = {
    "NOT_COLLECTED": ("NOT_COLLECTED", "MISSING_INPUT"),
    "INSUFFICIENT_HISTORY": ("INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    "NOT_APPLICABLE": ("NOT_APPLICABLE", "NOT_APPLICABLE"),
}


class ColdScoringInput(InputModel):
    request: BaselineSelectionRequest
    raw_features: RawFeatureVector
    selection: BaselineSelection
    trace_id: Identifier

    @model_validator(mode="after")
    def validate_context(self) -> Self:
        self.raw_features.validate_for_request(self.request.evaluation)
        self.selection.validate_for_request(self.request)
        return self

    def evaluation_with_selected_profiles(self) -> BehaviorEvaluationRequest:
        """Bind supplied, validated population snapshots without mutating input."""
        evaluation = self.request.evaluation
        profiles = {p.reference: p for p in evaluation.profiles}
        profiles.update({p.reference: p for p in self.selection.profiles})
        return BehaviorEvaluationRequest.model_validate({
            **evaluation.model_dump(), "profiles": list(profiles.values()),
        })


class ColdFeatureSufficiency(InputModel):
    """Reference support for calculation, never statistical confidence."""

    feature_id: Identifier
    reference_support: Literal["UNAVAILABLE", "POLICY_ONLY", "POPULATION_SAMPLES"]
    population_sample_count: Annotated[int, Field(gt=0)] | None = None

    @model_validator(mode="after")
    def validate_support(self) -> Self:
        if (self.reference_support == "POPULATION_SAMPLES") != (self.population_sample_count is not None):
            raise ValueError("only population support can claim observed sample counts")
        return self


def reference_sufficiency(selection: BaselineSelection) -> tuple[ColdFeatureSufficiency, ...]:
    profiles = {p.reference: p for p in selection.profiles}
    results = []
    for feature in selection.features:
        support, count = "UNAVAILABLE", None
        if feature.status == "SELECTED":
            support = "POLICY_ONLY"
            if feature.basis.profile is not None:
                support = "POPULATION_SAMPLES"
                profile = profiles[feature.basis.profile]
                count = next(o.valid_sample_count for o in profile.observations if o.feature_id == feature.feature_id)
        results.append(ColdFeatureSufficiency(feature_id=feature.feature_id,
                                             reference_support=support, population_sample_count=count))
    return tuple(results)


class ColdScoringResult(InputModel):
    """Shared result plus auditable inputs and limits; no final risk decision."""

    behavior: BehaviorEvaluationResult
    raw_features: RawFeatureVector
    selection: BaselineSelection
    personal_profile_status: Literal["NOT_SUPPLIED", "SUPPLIED_NOT_USED"]
    sufficiency: Annotated[Array[ColdFeatureSufficiency], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_evidence(self) -> Self:
        behavior, raw, selection = self.behavior, self.raw_features, self.selection
        if not (behavior.metadata.target == raw.target == selection.target):
            raise ValueError("Cold evidence must share the evaluation target")
        if not (behavior.metadata.evaluated_at == raw.evaluated_at == selection.evaluated_at):
            raise ValueError("Cold evidence must share the evaluation time")
        if not (behavior.feature_schema_version == raw.feature_schema_version == selection.feature_schema_version):
            raise ValueError("Cold evidence must share the feature schema")
        if behavior.scoring_version != SCORING_VERSION:
            raise ValueError("unsupported Cold scoring version")
        if behavior.metadata.valid_until is not None:
            raise ValueError("Cold point-in-time scoring cannot advertise reusable result validity")
        if any(f.currency is not None and f.currency != selection.currency for f in raw.features):
            raise ValueError("Cold raw values and references must share currency")
        ids = set(behavior.requested_features)
        if any({f.feature_id for f in group} != ids for group in (raw.features, selection.features, behavior.features)):
            raise ValueError("Cold evidence must cover exactly the requested features")
        if self.sufficiency != reference_sufficiency(selection):
            raise ValueError("Cold sufficiency must reflect the selected reference observations")
        selected = {f.feature_id: f for f in selection.features}
        features = {f.feature_id: f for f in behavior.features}
        for value in raw.features:
            feature, reference = features[value.feature_id], selected[value.feature_id]
            unavailable = UNAVAILABLE_STATES.get(value.status)
            if unavailable is None and reference.status == "UNAVAILABLE":
                unavailable = ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE")
            if unavailable is not None:
                if (feature.status, feature.reasons) != (unavailable[0], (unavailable[1],)) or feature.bases:
                    raise ValueError("unavailable Cold feature must retain its cause and no applied basis")
            else:
                if feature.status not in {"SCORED", "FAILED"} or feature.bases != (reference.basis,):
                    raise ValueError("calculated Cold feature must retain its selected basis")
                if feature.status == "SCORED":
                    reason = "WITHIN_REFERENCE" if feature.score.value == 0 else "PATTERN_DEVIATION"
                    if feature.reasons != (reason,):
                        raise ValueError("Cold score reason must agree with the deviation")
        expected_status = "FAILED" if any(f.status == "FAILED" for f in behavior.features) else "SUCCESS"
        if behavior.status != expected_status:
            raise ValueError("Cold execution status must reflect calculation failures")
        profiles = {p.reference: p for p in selection.profiles}
        if any(profiles.get(p.reference) != p for p in behavior.profiles):
            raise ValueError("Cold result must retain exact selected profile snapshots")
        return self

    def validate_for_input(self, supplied: ColdScoringInput) -> Self:
        if self.raw_features != supplied.raw_features or self.selection != supplied.selection:
            raise ValueError("Cold result must retain the supplied raw features and selected references")
        self.behavior.validate_for_request(supplied.evaluation_with_selected_profiles())
        if self.behavior.metadata.trace_id != supplied.trace_id:
            raise ValueError("Cold result must retain the supplied trace identity")
        has_personal = any(p.reference.scope == "PERSONAL" for p in supplied.request.evaluation.profiles)
        expected = "SUPPLIED_NOT_USED" if has_personal else "NOT_SUPPLIED"
        if self.personal_profile_status != expected:
            raise ValueError("personal profile availability must reflect the supplied request")
        return self
