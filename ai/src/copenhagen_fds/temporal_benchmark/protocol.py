"""Frozen experimental choices; these are not validated operating policies."""

from datetime import datetime, timedelta
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.risk_policy import RiskPolicyConfig
from copenhagen_fds.contracts.values import InputModel
from copenhagen_fds.risk_settings import default_risk_policy
from copenhagen_fds.synthetic.generator import GeneratorConfig


class SimulationConfig(InputModel):
    seed: Annotated[int, Field(ge=0, lt=2**64)] = 20260916
    users: Annotated[int, Field(ge=12, le=1000)] = 120
    days: Annotated[int, Field(ge=12, le=366)] = 120
    start: str = "2026-01-01"
    train_days: Annotated[int, Field(gt=0)] = 60
    validation_days: Annotated[int, Field(gt=0)] = 30

    @model_validator(mode="after")
    def validate_generator(self) -> Self:
        self.generator_config()
        return self

    def generator_config(self) -> GeneratorConfig:
        return GeneratorConfig(**self.model_dump())

    def boundaries(self) -> dict[str, datetime]:
        start = datetime.fromisoformat(self.start + "T00:00:00+09:00")
        return dict(start=start, validation_start=start + timedelta(days=self.train_days),
                    test_start=start + timedelta(days=self.train_days + self.validation_days),
                    end_exclusive=start + timedelta(days=self.days))


class BenchmarkPlan(InputModel):
    schema_version: Literal["temporal-benchmark-plan-v1"] = "temporal-benchmark-plan-v1"
    simulation: SimulationConfig = Field(default_factory=SimulationConfig)
    suite: Literal["stream", "challenge"] = "stream"
    # No automatic claim that data have never been inspected by a human.
    data_role: Literal["REGRESSION", "CANDIDATE_HOLDOUT"] = "REGRESSION"
    distribution_variant: Literal["BASE", "DOUBLE_AMOUNTS"] = "BASE"
    split: Literal["validation", "test"] = "test"
    purchase_limit: Annotated[int, Field(gt=0, le=10000)] = 120
    semantic_seed: Annotated[int, Field(ge=0, lt=2**64)] = 20260916
    settings: PersonalReplaySettings
    risk_policy: RiskPolicyConfig = Field(default_factory=default_risk_policy)
    selection: Literal["AVAILABLE_TIME_THEN_PURCHASE_ID"] = "AVAILABLE_TIME_THEN_PURCHASE_ID"
    primary_unit: Literal["FIRST_EVALUATION_PER_PURCHASE"] = "FIRST_EVALUATION_PER_PURCHASE"
    retry_policy: Literal["AUXILIARY_ONLY"] = "AUXILIARY_ONLY"
    fraud_positive_rule: Literal["BEHAVIOR_SCORE_AT_LEAST_MEDIUM_THRESHOLD"] = "BEHAVIOR_SCORE_AT_LEAST_MEDIUM_THRESHOLD"
    rank_metric: Literal["AVERAGE_PRECISION_TIED_SCORE_GROUPS"] = "AVERAGE_PRECISION_TIED_SCORE_GROUPS"
    missing_score: Literal["EXCLUDE_FROM_SCORE_METRICS_REPORT_COVERAGE"] = "EXCLUDE_FROM_SCORE_METRICS_REPORT_COVERAGE"
    threshold_tuning: Literal["VALIDATION_ONLY_FROZEN_BEFORE_TEST"] = "VALIDATION_ONLY_FROZEN_BEFORE_TEST"
    semantic_quality_scope: Literal["SYNTHETIC_RECIPE_NOT_HUMAN_REVIEWED"] = "SYNTHETIC_RECIPE_NOT_HUMAN_REVIEWED"

    @model_validator(mode="after")
    def validate_conditions(self) -> Self:
        if self.data_role == "CANDIDATE_HOLDOUT":
            if self.simulation.seed in {20260910, 20260911} or self.distribution_variant == "BASE":
                raise ValueError("candidate holdout requires a new seed and declared distribution variation")
        policy = self.settings.cold_policy
        if policy is not None:
            start = self.simulation.boundaries()["start"]
            if max(policy.available_at, policy.valid_from) > start:
                raise ValueError("Cold baseline must be available before the simulated history")
        if self.risk_policy.score_scale_version != self.settings.personalization.score_scale_version:
            raise ValueError("risk and personalization score scales must agree")
        return self

    def interval(self) -> tuple[datetime, datetime]:
        bounds = self.simulation.boundaries()
        return ((bounds["validation_start"], bounds["test_start"]) if self.split == "validation"
                else (bounds["test_start"], bounds["end_exclusive"]))
