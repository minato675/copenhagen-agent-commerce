"""S15P21A207-302: actual candidate and admission execution without model or storage."""

import pytest

from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateResponse
from copenhagen_fds.provider_runtime.profile_creation import create_profile_handler


@pytest.mark.parametrize("population_scope", [False, True])
def test_creation_runs_native_build_and_policy_once(creation_case, run_async, monkeypatch, population_scope):
    import copenhagen_fds.provider_runtime.profile_creation as module

    case = creation_case(population_scope=population_scope)
    before, seen = case.request.model_dump_json(), []
    build, initialize = module.build_profile_candidate, module.initialize_profile_policy

    def candidate(request):
        seen.append(("candidate", request))
        return build(request)

    def policy(snapshot, observations, config):
        seen.append(("policy", observations, config))
        return initialize(snapshot, observations, config)

    monkeypatch.setattr(module, "build_profile_candidate", candidate)
    monkeypatch.setattr(module, "initialize_profile_policy", policy)
    response = ProviderProfileCreateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
    assert response.state == case.original.state
    assert seen == [("candidate", case.request), ("policy", case.request.replay.observations, case.request.policy)]
    assert case.request.model_dump_json() == before
    assert response.storage_status == "NOT_PERSISTED" and response.completed_at == case.wall[0]


@pytest.mark.parametrize("population_scope", [False, True])
def test_creation_needs_no_model_resources_and_preserves_owner_context(creation_case, run_async, monkeypatch, population_scope):
    import copenhagen_fds.provider_runtime.execution as execution

    seen = []

    async def handler(request, context):
        seen.append(context.identity)
        return await create_profile_handler(request, context)

    async def forbidden(*args, **kwargs):
        pytest.fail("initial creation must not call model completion or fraud evaluation")

    monkeypatch.setattr(execution.ExecutionContext, "complete", forbidden)
    monkeypatch.setattr("copenhagen_fds.native_evaluation.evaluate_fraud", forbidden)
    case = creation_case(population_scope=population_scope, handler=handler)
    response = ProviderProfileCreateResponse.model_validate_json(run_async(case.execute()))
    assert seen == [case.bound.identity] and response.state == case.original.state


@pytest.mark.parametrize("population_scope", [False, True])
def test_creation_is_reproducible_under_observation_and_member_reordering(creation_case, run_async, population_scope):
    original = creation_case(population_scope=population_scope)
    first = ProviderProfileCreateResponse.model_validate_json(run_async(original.execute()))

    def reorder(data):
        data["replay"]["observations"] = tuple(reversed(data["replay"]["observations"]))
        if population_scope:
            data["profile"]["cohort"]["member_user_ids"] = tuple(reversed(data["profile"]["cohort"]["member_user_ids"]))

    reordered = creation_case(population_scope=population_scope, configure=reorder)
    second = ProviderProfileCreateResponse.model_validate_json(run_async(reordered.execute())).validate_for_request(reordered.request)
    assert first.state == second.state
    assert first.request_sha256 != second.request_sha256  # Exact request binding retains submitted order.
