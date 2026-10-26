"""Provider-owned snapshots prepared before the engine's read-only evaluation."""

from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personalization_inputs import PublishedProfileInput
from copenhagen_fds.contracts.profile_policy import ProfilePolicyConfig
from copenhagen_fds.personal_replay import _probe_input, run_personal_replay
from copenhagen_fds.profile_policy_updates import initialize_profile_policy


@pytest.fixture
def behavior_cases(engine_cases, replay_cases, personalized, population):
    template = engine_cases.inputs()[0]

    def with_feature(data, feature):
        original = feature.evaluation.request
        data["feature_input"] = feature
        data["analysis"] = dict(user_request=dict(purchase_request_id=original.context.purchase_request_id,
            source=original.intent.source, version=original.intent.version, text="합성 구매 요청"),
            order=original.order, context=original.context)
        return data

    def from_row(row):
        data = with_feature(template.model_dump(), row.case.input)
        data.update(trace_id=row.input.cold.trace_id, personal=row.input.personal, personalization=row.input.config)
        return EngineEvaluationRequest.model_validate(data)

    def comparison():
        rows = run_personal_replay(replay_cases.data, replay_cases.request()).rows
        return [(from_row(row), row) for row in rows[:5]]

    def population_request(*, expired=False, unavailable=False):
        if unavailable:
            state = initialize_profile_policy(population.fit(), (), ProfilePolicyConfig())
            source = PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
                expected_policy_state_version=state.state_version, available_at=state.published.profile.generated_at)
        else:
            source = personalized.publication(scope="POPULATION")
        at = source.state.published.profile.valid_until if expired else source.available_at + timedelta(seconds=1)
        feature = _probe_input("a", "KRW", 10000, at - timedelta(microseconds=1), at)
        data = with_feature(template.model_dump(), feature)
        data.update(population=source, population_baseline_request=dict(
            expected_source_reference=source.expected_reference, currency="KRW", available_at=source.available_at))
        policy = data["initial_policy"]
        policy.update(generated_at=at - timedelta(days=2), available_at=at - timedelta(days=2),
                      valid_from=at - timedelta(days=2), valid_until=at + timedelta(days=2))
        return EngineEvaluationRequest.model_validate(data)

    return SimpleNamespace(comparison=comparison, population=population_request)
