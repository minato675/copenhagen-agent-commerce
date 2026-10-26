"""Task 287 contracts use real prior scorers with explicit synthetic analysis evidence."""

from datetime import datetime, timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest
from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES, RiskPolicyConfig
from copenhagen_fds.personal_replay import _probe_input, run_personal_replay


@pytest.fixture
def engine_cases(replay_cases):
    at = datetime.fromisoformat("2026-01-07T14:00:00+09:00")
    policy = RiskPolicyConfig(version="engine-fixture-v1", intent_required=True, intent_max_age_seconds=300,
        groups=[dict(group_id=name, weight=weight, features=[dict(feature_id=f, required=True) for f in members])
                for (name, members), weight in zip(GROUP_FEATURES.items(), (0.4, 0.35, 0.25))],
        thresholds=dict(medium_from=30.0, high_from=70.0),
        floor_rules=[dict(rule_id="strong-amount", conditions=[dict(group_id="AMOUNT", minimum_score=90.0)], minimum_risk="HIGH")])

    def inputs(*, profile=False, missing=False, amount=100000):
        feature = _probe_input("a", "KRW", amount, at - timedelta(microseconds=1), at).model_dump()
        if missing:
            feature["history"]["purchases"] = feature["history"]["attempts"] = dict(status="NOT_COLLECTED")
        replay = PersonalReplayRequest(settings=replay_cases.settings,
            cases=[dict(case_id="engine", mode="CONTROLLED_PROBE", input=feature,
                        profile_as_of="2026-01-05T13:00:00+09:00" if profile else None)])
        row = run_personal_replay(replay_cases.data, replay).rows[0]
        original = row.case.input.evaluation.request
        request = EngineEvaluationRequest(trace_id=row.input.cold.trace_id, feature_input=row.case.input,
            analysis=dict(user_request=dict(purchase_request_id=original.context.purchase_request_id,
                source=original.intent.source, version=original.intent.version, text="합성 구매 요청"),
                order=original.order, context=original.context), personal=row.input.personal,
            initial_policy=replay_cases.settings.cold_policy, personalization=row.input.config,
            scoring=replay_cases.settings.scoring, risk_policy=policy,
            execution=dict(gateway="MOCK", requested_model="fixture-model", extraction_prompt_version=EXTRACTION_PROMPT_VERSION,
                comparison_prompt_version=COMPARISON_PROMPT_VERSION, provider_timeout_seconds=5.0, total_timeout_seconds=10.0))
        return request, row

    return SimpleNamespace(inputs=inputs, at=at)
