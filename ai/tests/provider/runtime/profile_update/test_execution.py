"""S15P21A207-303: native full replay once, independent of model calls and persistence."""

import pytest

from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateResponse
from copenhagen_fds.provider_runtime.profile_update import update_profile_handler


@pytest.mark.parametrize("population_scope", [False, True])
def test_runtime_preserves_native_result_with_original_input_once(update_case, profile_case, run_async, monkeypatch, population_scope):
    import copenhagen_fds.provider_runtime.profile_update as module

    case = update_case(population_scope=population_scope)
    expected = profile_case.update(population_scope=population_scope).response.result
    before, calls = case.request.model_dump_json(), []
    native = module.update_profile_policy

    def observe(state, update, observations):
        calls.append((state, update, observations))
        return native(state, update, observations)

    monkeypatch.setattr(module, "update_profile_policy", observe)
    response = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
    assert response.result == expected
    assert calls == [(case.request.state, case.request.update, case.request.replay.observations)]
    assert case.request.model_dump_json() == before
    assert response.storage_status == "NOT_PERSISTED" and response.completed_at == case.wall[0]


@pytest.mark.parametrize("population_scope", [False, True])
def test_identical_replay_preserves_versions_and_has_no_transitions(update_case, profile_case, run_async, population_scope):
    state = profile_case.create(population_scope=population_scope).state
    case = update_case(state=state, cutoff=state.candidate.snapshot.profile.as_of,
                       rows=tuple(reversed(state.observations)))
    response = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute()))
    assert response.result.status == "UNCHANGED" and response.result.state == state
    assert not response.result.transitions and not response.result.candidate_events


def test_update_uses_authorized_context_without_evaluation_or_creation(update_case, run_async, monkeypatch):
    seen = []

    async def handler(request, context):
        seen.append(context.identity)
        return await update_profile_handler(request, context)

    async def forbidden(*args, **kwargs):
        pytest.fail("update must not call model completion, evaluate or create a profile")

    monkeypatch.setattr("copenhagen_fds.provider_runtime.execution.ExecutionContext.complete", forbidden)
    monkeypatch.setattr("copenhagen_fds.native_evaluation.evaluate_fraud", forbidden)
    monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_creation.create_profile_handler", forbidden)
    case = update_case(handler=handler)
    response = ProviderProfileUpdateResponse.model_validate_json(run_async(case.execute()))
    assert seen == [case.bound.identity] and response.result.status == "UPDATED"
