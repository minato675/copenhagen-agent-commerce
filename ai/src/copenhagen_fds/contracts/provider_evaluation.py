"""Transport-neutral BE evaluation messages, without execution or authorization."""

import math
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.behavior import AnomalyScore, CoverageValue, FeatureStatusValue
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest, EngineRequestReference
from copenhagen_fds.contracts.engine_results import EngineStageExecution, STAGES
from copenhagen_fds.contracts.engine_execution import ExecutionStage
from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationOutcome
from copenhagen_fds.contracts.personal_readiness import ReadinessFactors, ReadinessMeasures
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.profiles import ProfileReference
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.risk_results import GroupContribution, PolicyReason
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


class ConfigurationReference(InputModel):
    """A content claim to compare with trusted settings, never proof of permission."""

    configuration_id: Identifier
    version: Identifier
    content_sha256: Sha256


class CallMetadata(InputModel):
    request_id: Identifier
    trace_id: Identifier
    sent_at: Timestamp


def evaluation_configuration_hash(request: EngineEvaluationRequest) -> str:
    return personalization_hash((request.initial_policy, request.personalization, request.scoring,
                                 request.risk_policy, request.execution))


class ProviderEvaluationRequest(InputModel):
    schema_version: Literal["fds-provider-v1"] = "fds-provider-v1"
    operation: Literal["EVALUATE"]
    call: CallMetadata
    configuration: ConfigurationReference
    evaluation: EngineEvaluationRequest = Field(repr=False)

    @model_validator(mode="after")
    def validate_call(self) -> Self:
        if self.call.trace_id != self.evaluation.trace_id:
            raise ValueError("call and evaluation traces must match")
        if self.evaluation.feature_input.evaluation.evaluated_at > self.call.sent_at:
            raise ValueError("behavior cutoff cannot follow submission")
        if self.configuration.content_sha256 != evaluation_configuration_hash(self.evaluation):
            raise ValueError("configuration claim must bind all supplied policy and execution settings")
        if set(self.evaluation.feature_input.evaluation.requested_features) != set(FEATURE_SPECS):
            raise ValueError("provider evaluation requires all nine native risk-policy features")
        return self


class ReadinessSummary(InputModel):
    window: Literal["RECENT", "LONG_TERM"]
    measures: ReadinessMeasures
    factors: ReadinessFactors
    coverage_status: Literal["NOT_REQUIRED", "UNAVAILABLE", "COMPLETE", "INCOMPLETE"]
    personal_weight: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]
    reasons: Annotated[Array[Identifier], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_support(self) -> Self:
        factors = tuple(self.factors.model_dump().values())
        if self.personal_weight > 0 and (any(f is None for f in factors) or
                self.personal_weight != min(factors) or self.coverage_status not in {"COMPLETE", "NOT_REQUIRED"}):
            raise ValueError("positive personal support requires all readiness factors and coverage")
        return self


class FeatureStageSummary(InputModel):
    feature_id: Identifier
    status: FeatureStatusValue
    score: AnomalyScore | None
    stage: Literal["COLD", "TRANSITION", "PERSONALIZED", "UNAVAILABLE"]
    cold_weight: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)] | None
    personal_weight: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)] | None
    readiness: Annotated[Array[ReadinessSummary], Field(max_length=2)]
    reasons: Annotated[Array[Identifier], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_stage(self) -> Self:
        if self.feature_id not in FEATURE_SPECS:
            raise ValueError("feature must belong to the supported schema")
        if (self.status == "SCORED") != (self.score is not None) or (self.score is None) != (self.stage == "UNAVAILABLE"):
            raise ValueError("feature score, status and stage must preserve availability")
        if self.readiness and [r.window for r in self.readiness] != ["RECENT", "LONG_TERM"]:
            raise ValueError("available readiness requires both ordered windows")
        if self.stage == "UNAVAILABLE":
            if self.cold_weight is not None or self.personal_weight is not None:
                raise ValueError("unavailable score cannot claim mixture weights")
        else:
            if self.personal_weight is None or self.cold_weight != 1 - self.personal_weight:
                raise ValueError("scored stage requires complementary mixture weights")
            expected = "COLD" if self.personal_weight == 0 else "PERSONALIZED" if self.personal_weight == 1 else "TRANSITION"
            if self.stage != expected:
                raise ValueError("stage must match the actual mixture")
        return self


class EvaluationVersions(InputModel):
    feature_schema: Identifier
    scoring: Identifier
    personalization: Identifier
    risk_policy: Identifier
    requested_model: Identifier
    extraction_prompt: Identifier
    comparison_prompt: Identifier
    extraction_model: Identifier | None
    comparison_model: Identifier | None
    profiles: Array[ProfileReference]


class ProviderEvaluationResponse(InputModel):
    """Projection only: no raw text, orders, histories, prompts or full engine record."""

    schema_version: Literal["fds-provider-v1"] = "fds-provider-v1"
    operation: Literal["EVALUATE"]
    call: CallMetadata
    request_sha256: Sha256
    engine_reference: EngineRequestReference
    record_sha256: Sha256
    completed_at: Timestamp
    outcome: NativeEvaluationOutcome
    behavior_score: AnomalyScore | None
    score_coverage: CoverageValue
    groups: Annotated[Array[GroupContribution], Field(max_length=3)]
    features: Annotated[Array[FeatureStageSummary], Field(max_length=9)]
    reasons: Annotated[Array[PolicyReason], Field(min_length=1)]
    stages: Annotated[Array[ExecutionStage | EngineStageExecution], Field(min_length=4, max_length=4)]
    versions: EvaluationVersions
    # Issuer/TTL policy is not agreed. Absence must not become a default lease.
    result_valid_until: Timestamp | None = None
    reuse_status: Literal["NOT_ASSESSED"] = "NOT_ASSESSED"
    storage_status: Literal["NOT_PERSISTED"] = "NOT_PERSISTED"

    @model_validator(mode="after")
    def validate_summary(self) -> Self:
        if self.call.trace_id != self.engine_reference.trace_id or not (
            self.engine_reference.behavior_as_of <= self.call.sent_at <= self.completed_at
        ):
            raise ValueError("response must preserve trace and ordered request/completion times")
        if [s.stage for s in self.stages] != list(STAGES):
            raise ValueError("every engine stage must appear once in canonical order")
        for stage in self.stages:
            if stage.started_at is not None and not self.call.sent_at <= stage.started_at <= stage.completed_at <= self.completed_at:
                raise ValueError("stage must execute within the request interval")
        if self.result_valid_until is not None and (
            self.outcome.status != "SUCCESS" or self.result_valid_until <= self.completed_at
        ):
            raise ValueError("only successful results may carry a future exclusive expiry")
        if self.outcome.status == "SUCCESS" and any(s.status in {"FAILED", "CANCELLED"} for s in self.stages):
            raise ValueError("successful evaluation cannot hide a failed component")
        if self.outcome.status == "SUCCESS" and self.stages[-1].status != "SUCCESS":
            raise ValueError("successful evaluation requires executed risk policy")
        if self.outcome.status == "INCOMPLETE" and any(s.status in {"FAILED", "CANCELLED"} for s in self.stages):
            raise ValueError("incomplete data cannot hide execution failure or cancellation")
        if self.groups and [g.group_id for g in self.groups] != ["AMOUNT", "FREQUENCY", "TIME"]:
            raise ValueError("policy evidence must retain all three groups")
        rows = [row for group in self.groups for row in group.features]
        count = sum(row.feature.score is not None for row in rows)
        coverage = "COMPLETE" if count == 9 else "PARTIAL" if count else "UNAVAILABLE"
        if self.score_coverage != coverage or (coverage == "COMPLETE") != (self.behavior_score is not None):
            raise ValueError("missing scores are unavailable, never zero or renormalized")
        if self.behavior_score is not None and not math.isclose(
            self.behavior_score.value, min(100.0, math.fsum(g.weighted_points for g in self.groups)),
            rel_tol=1e-12, abs_tol=0,
        ):
            raise ValueError("total score must preserve the recorded group contributions")
        if self.outcome.status == "SUCCESS" and coverage != "COMPLETE":
            raise ValueError("successful evaluation requires complete behavior scores")
        names = [f.feature_id for f in self.features]
        if names != [name for name in FEATURE_SPECS if name in names]:
            raise ValueError("feature stages must be unique and in canonical order")
        by_id = {row.feature.feature_id: row.feature for row in rows}
        for feature in self.features:
            source = by_id.get(feature.feature_id)
            if source is not None and (source.status, source.score) != (feature.status, feature.score):
                raise ValueError("stage score must preserve the policy contribution's feature")
        if self.stages[0].status == "NOT_RUN" and self.features:
            raise ValueError("unexecuted behavior cannot claim feature calculations")
        if self.stages[0].status == "SUCCESS" and names != list(FEATURE_SPECS):
            raise ValueError("executed behavior requires stage/readiness summaries for every feature")
        for index, model in ((1, self.versions.extraction_model), (2, self.versions.comparison_model)):
            if self.stages[index].status == "SUCCESS" and model is None:
                raise ValueError("successful analysis requires its actual response model version")
            if self.stages[index].status == "NOT_RUN" and model is not None:
                raise ValueError("unexecuted analysis cannot invent a response model")
        return self

    def validate_for_request(self, request: ProviderEvaluationRequest) -> Self:
        if (self.call, self.request_sha256, self.engine_reference) != (
            request.call, personalization_hash(request), EngineRequestReference.from_request(request.evaluation),
        ):
            raise ValueError("response must bind the entire submitted request")
        engine, versions = request.evaluation, self.versions
        expected = (engine.feature_input.evaluation.feature_schema_version, engine.personalization.score_scale_version,
                    engine.personalization.version, engine.risk_policy.version, engine.execution.requested_model,
                    engine.execution.extraction_prompt_version, engine.execution.comparison_prompt_version)
        actual = (versions.feature_schema, versions.scoring, versions.personalization, versions.risk_policy,
                  versions.requested_model, versions.extraction_prompt, versions.comparison_prompt)
        if actual != expected or versions.profiles != tuple(
            p.state.published.profile.reference for p in (engine.personal, engine.population) if p is not None
        ):
            raise ValueError("response must preserve supplied configuration and profile versions")
        if self.groups and [(g.group_id, g.weight) for g in self.groups] != [(g.group_id, g.weight) for g in engine.risk_policy.groups]:
            raise ValueError("contribution weights must preserve the requested risk policy")
        for group in self.groups:
            for row in group.features:
                for basis in row.feature.bases:
                    if basis.profile is not None and basis.profile not in versions.profiles:
                        raise ValueError("scoring basis must reference a supplied published profile")
                    if basis.kind == "INITIAL_POLICY" and (engine.initial_policy is None or basis.policy_version != engine.initial_policy.version):
                        raise ValueError("initial scoring basis must retain the supplied policy version")
        return self
