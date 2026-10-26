"""Caller-owned engine snapshots and secret-free execution settings; no evaluation."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION
from copenhagen_fds.contracts.analysis_inputs import IntentAnalysisRequest
from copenhagen_fds.contracts.baselines import InitialPolicy, parameter_error
from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.contracts.personal_amount import AMOUNT_SCORE_VERSION
from copenhagen_fds.contracts.personal_components import PersonalScoringPolicies
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION
from copenhagen_fds.contracts.personal_readiness import READINESS_VERSION
from copenhagen_fds.contracts.personal_time import TIME_PERIODS, TIME_SCORE_VERSION
from copenhagen_fds.contracts.personalization_config import PersonalizationConfig
from copenhagen_fds.contracts.personalization_inputs import PublishedProfileInput, personalization_hash
from copenhagen_fds.contracts.population_baselines import PopulationBaselineRequest
from copenhagen_fds.contracts.results import EvaluationTarget, Timestamp
from copenhagen_fds.contracts.risk_policy import RiskPolicyConfig
from copenhagen_fds.contracts.values import Identifier, InputModel, Sha256
from copenhagen_fds.risk_settings import default_risk_policy
from copenhagen_fds.validation_memo import memoized_validation


PositiveSeconds = Annotated[float, Field(gt=0, allow_inf_nan=False)]


class EngineExecutionConfig(InputModel):
    version: Literal["engine-execution-v1"] = "engine-execution-v1"
    gateway: Literal["MOCK", "GMS"]
    requested_model: Identifier
    extraction_prompt_version: Identifier
    comparison_prompt_version: Identifier
    provider_timeout_seconds: PositiveSeconds
    total_timeout_seconds: PositiveSeconds

    @model_validator(mode="after")
    def validate_config(self) -> Self:
        if self.provider_timeout_seconds > self.total_timeout_seconds:
            raise ValueError("provider timeout cannot exceed the shared total budget")
        if (self.extraction_prompt_version, self.comparison_prompt_version) != (
            EXTRACTION_PROMPT_VERSION, COMPARISON_PROMPT_VERSION,
        ):
            raise ValueError("engine requires supported extraction and comparison prompt versions")
        return self


class EngineEvaluationRequest(InputModel):
    schema_version: Literal["engine-evaluation-request-v1"] = "engine-evaluation-request-v1"
    trace_id: Identifier
    feature_input: FeatureTransformInput = Field(repr=False)
    analysis: IntentAnalysisRequest = Field(repr=False)
    personal: PublishedProfileInput | None = Field(default=None, repr=False)
    population: PublishedProfileInput | None = Field(default=None, repr=False)
    population_baseline_request: PopulationBaselineRequest | None = None
    initial_policy: InitialPolicy | None
    personalization: PersonalizationConfig
    scoring: PersonalScoringPolicies
    risk_policy: RiskPolicyConfig = Field(default_factory=default_risk_policy)
    execution: EngineExecutionConfig

    @model_validator(mode="after")
    def validate_binding(self) -> Self:
        evaluation = self.feature_input.evaluation
        original, analysis = evaluation.request, self.analysis
        if (original.order, original.context) != (analysis.order, analysis.context):
            raise ValueError("behavior and analysis must preserve exact order and context content")
        if (original.intent.source, original.intent.version) != (analysis.user_request.source, analysis.user_request.version):
            raise ValueError("text provenance must match the original intent provenance")
        if analysis.target != evaluation.target:
            raise ValueError("behavior and analysis must share one evaluation target")
        config, policy = self.personalization, self.risk_policy
        if evaluation.feature_schema_version != policy.feature_schema_version:
            raise ValueError("behavior and risk policy must share a supported feature schema")
        if not set(evaluation.requested_features) <= {f.feature_id for f in config.features}:
            raise ValueError("personalization must cover every requested feature")
        if config.score_scale_version != policy.score_scale_version or config.score_scale_version != self.scoring.scale_version:
            raise ValueError("behavior and risk policies must share the scoring scale")
        versions = {"amount_minor": AMOUNT_SCORE_VERSION, **dict.fromkeys(FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION),
                    **dict.fromkeys(TIME_PERIODS, TIME_SCORE_VERSION)}
        if config.transition_policy_version != READINESS_VERSION or any(f.score_version != versions[f.feature_id] for f in config.features):
            raise ValueError("engine requires supported readiness and feature scoring versions")
        for scope, supplied in (("PERSONAL", self.personal), ("POPULATION", self.population)):
            if supplied is None:
                continue
            profile = supplied.state.published.profile
            if profile.reference.scope != scope:
                raise ValueError("published profile must occupy its declared scope")
            if scope == "PERSONAL" and profile.reference.user_id != evaluation.target.user_id:
                raise ValueError("personal profile must belong to the evaluated user")
            if (profile.currency, profile.feature_schema_version) != (original.order.total.currency, evaluation.feature_schema_version):
                raise ValueError("published profile must match evaluation currency and feature schema")
            if profile.as_of >= evaluation.evaluated_at or supplied.available_at > evaluation.evaluated_at:
                raise ValueError("engine requires prior cutoffs and already available publications")
            if any(r.sample.eligibility.target.purchase_request_id == evaluation.target.purchase_request_id
                   for r in supplied.state.observations):
                raise ValueError("current purchase must be absent from supplied policy history")
        for profile in evaluation.profiles:
            supplied = self.personal if profile.reference.scope == "PERSONAL" else self.population
            if supplied is None or profile != supplied.state.published.profile:
                raise ValueError("behavior metadata must match a supplied published profile")
        if (self.population is None) != (self.population_baseline_request is None):
            raise ValueError("published population requires an explicit baseline derivation request")
        if self.population is not None:
            request, supplied = self.population_baseline_request, self.population
            profile = supplied.state.published.profile
            if (request.expected_source_reference, request.currency, request.feature_schema_version) != (
                profile.reference, profile.currency, profile.feature_schema_version,
            ) or not supplied.available_at <= request.available_at <= evaluation.evaluated_at:
                raise ValueError("population derivation must pin the published source, currency, schema and availability")
        initial = self.initial_policy
        if initial is not None:
            if initial.currency != original.order.total.currency or initial.feature_schema_version != policy.feature_schema_version:
                raise ValueError("initial policy must match evaluation currency and schema")
            if parameter_error(initial.parameters):
                raise ValueError("initial policy requires supported feature parameters and units")
            if max(initial.available_at, initial.valid_from) > evaluation.evaluated_at:
                raise ValueError("initial policy cannot originate after the behavior cutoff")
        return self


class EngineRequestReference(InputModel):
    schema_version: Literal["engine-request-reference-v1"] = "engine-request-reference-v1"
    target: EvaluationTarget
    trace_id: Identifier
    behavior_as_of: Timestamp
    request_sha256: Sha256
    analysis_sha256: Sha256
    feature_input_sha256: Sha256
    profiles_sha256: Sha256
    policies_sha256: Sha256
    execution_sha256: Sha256

    @classmethod
    def from_request(cls, request: EngineEvaluationRequest) -> Self:
        def calculate():
            validated = EngineEvaluationRequest.model_validate(request.model_dump())
            return cls(target=validated.analysis.target, trace_id=validated.trace_id,
                behavior_as_of=validated.feature_input.evaluation.evaluated_at, request_sha256=personalization_hash(validated),
                analysis_sha256=personalization_hash(validated.analysis), feature_input_sha256=personalization_hash(validated.feature_input),
                profiles_sha256=personalization_hash((validated.personal, validated.population, validated.population_baseline_request)),
                policies_sha256=personalization_hash((validated.initial_policy, validated.personalization, validated.scoring, validated.risk_policy)),
                execution_sha256=personalization_hash(validated.execution))

        return memoized_validation("engine-request-reference-v1:" + cls.__module__ + "." + cls.__qualname__,
                                   request, calculate)
