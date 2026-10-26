"""S15P21A207-300: operation delivery, safe responses, interruption and resource ownership."""

import asyncio
import json
from types import SimpleNamespace

import pytest

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.transport_models import CompletionPrompt
from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.execution import ProviderRuntime


PROMPT = CompletionPrompt(stage="extraction", developer_message="offline test",
                          user_message="synthetic input", version="test-only")


class Resource:
    def __init__(self):
        self.calls = []
        self.closed = 0

    async def complete(self, prompt, *, budget):
        self.calls.append(budget)
        await asyncio.sleep(0)

    async def aclose(self):
        self.closed += 1


def runtime(fixture, handler, **options):
    request = fixture.case.request
    seconds = request.evaluation.execution.total_timeout_seconds if request.operation == "EVALUATE" else 5.0
    settings = dict(boundary=fixture.boundary, handlers={request.operation: handler},
                    timeout_seconds=seconds, cleanup_timeout_seconds=1.0, max_response_bytes=2_000_000)
    settings.update(options)
    return ProviderRuntime(**settings)


async def execute(provider, fixture, **options):
    return await provider.execute(fixture.case.request.operation, fixture.raw,
                                  credential=options.get("credential", fixture.credential))


@pytest.mark.parametrize("kind", ["evaluation", "personal", "population", "update"])
def test_injected_handlers_return_only_bound_contracts(kind, evaluation_case, profile_case, runtime_case, run_async):
    case = (evaluation_case() if kind == "evaluation" else profile_case.update() if kind == "update"
            else profile_case.create(population_scope=kind == "population"))
    fixture = runtime_case(case)
    observed = []

    async def handler(request, context):
        observed.append((request, context.identity))
        return case.response

    encoded = run_async(execute(runtime(fixture, handler), fixture))
    assert type(case.response).model_validate_json(encoded).validate_for_request(case.request) == case.response
    assert observed == [(case.request, fixture.identity)]


def test_denial_and_missing_handler_allocate_no_resources(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    observed = []

    def factory(authorized):
        observed.append("resource")
        return Resource()

    async def handler(request, context):
        observed.append("handler")
        return fixture.case.response

    provider = runtime(fixture, handler, resource_factory=factory)
    denied = run_async(execute(provider, fixture, credential=None))
    missing = run_async(execute(runtime(fixture, handler, handlers={}, resource_factory=factory), fixture))
    assert ProviderError.model_validate_json(denied).code == "UNAUTHENTICATED"
    assert ProviderError.model_validate_json(missing).code == "PROVIDER_UNAVAILABLE"
    assert not observed


@pytest.mark.parametrize("outcome", ["success", "exception", "connection", "contract", "wrong_response", "invalid_response"])
def test_resource_closed_and_private_errors_never_leave_boundary(outcome, evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    resource = Resource()

    async def handler(request, context):
        if outcome == "exception":
            raise ValueError("private-body-and-service-token")
        if outcome == "connection":
            raise ConnectionError("private-provider-address-and-token")
        if outcome == "contract":
            raise ProviderContractError("VERSION_CONFLICT")
        if outcome == "wrong_response":
            return {"secret": "private-handler-output"}
        if outcome == "invalid_response":
            return fixture.case.response.model_copy(update={"request_sha256": "a" * 64})
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, resource_factory=lambda _: resource), fixture))
    assert resource.closed == 1
    assert b"private-" not in encoded
    if outcome != "success":
        expected = {"connection": "PROVIDER_UNAVAILABLE", "contract": "VERSION_CONFLICT"}.get(outcome, "INTERNAL_ERROR")
        assert ProviderError.model_validate_json(encoded).code == expected
        assert set(json.loads(encoded)) == {"schema_version", "error_id", "code", "retry"}


def test_response_size_limit_discards_oversized_result(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())

    async def handler(request, context):
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, max_response_bytes=1), fixture))
    assert ProviderError.model_validate_json(encoded).code == "INTERNAL_ERROR"


def test_shared_budget_stops_followup_call_and_closes_resource(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    clock = SimpleNamespace(now=0.0)
    reached = []

    class ExpiringResource(Resource):
        async def complete(self, prompt, *, budget):
            await super().complete(prompt, budget=budget)
            clock.now += budget.total_seconds + 1

    resource = ExpiringResource()

    async def handler(request, context):
        await context.complete(PROMPT, budget=context.budget)
        reached.append("second-call")
        await context.complete(PROMPT, budget=context.budget)
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, clock=lambda: clock.now,
                                         resource_factory=lambda _: resource), fixture))
    assert ProviderError.model_validate_json(encoded).code == "DEADLINE_EXCEEDED"
    assert not reached and len(resource.calls) == 1 and resource.closed == 1


def test_handler_cannot_restart_budget(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    resource = Resource()

    async def handler(request, context):
        await context.complete(PROMPT, budget=AnalysisBudget.start(context.budget.total_seconds))
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, resource_factory=lambda _: resource), fixture))
    assert ProviderError.model_validate_json(encoded).code == "CONFIGURATION_MISMATCH"
    assert not resource.calls and resource.closed == 1


def test_context_satisfies_existing_engine_adapter_preflight(evaluation_case, runtime_case, run_async):
    from copenhagen_fds.engine_analysis import validate_engine_analysis_settings
    from copenhagen_fds.openai_settings import OpenAISettings

    fixture = runtime_case(evaluation_case())
    config = fixture.case.request.evaluation.execution
    resource = Resource()
    resource.settings = OpenAISettings(mode="MOCK", openai_model=config.requested_model,
        openai_timeout_seconds=config.provider_timeout_seconds, evaluation_timeout_seconds=config.total_timeout_seconds)

    async def handler(request, context):
        validate_engine_analysis_settings(request.evaluation, context)
        await context.complete(PROMPT, budget=context.budget)
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, resource_factory=lambda _: resource), fixture))
    assert ProviderEvaluationResponse.model_validate_json(encoded)
    assert resource.closed == 1 and len(resource.calls) == 1


def test_cleanup_time_is_included_before_publishing_success(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    clock = SimpleNamespace(now=0.0)

    class SlowCleanup(Resource):
        async def aclose(self):
            await super().aclose()
            clock.now += fixture.case.request.evaluation.execution.total_timeout_seconds + 1

    async def handler(request, context):
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, clock=lambda: clock.now,
                                         resource_factory=lambda _: SlowCleanup()), fixture))
    assert ProviderError.model_validate_json(encoded).code == "DEADLINE_EXCEEDED"


def test_cancelled_call_drains_cleanup_even_when_cancelled_twice(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())

    async def scenario():
        started, closing, finish_close = asyncio.Event(), asyncio.Event(), asyncio.Event()

        class ClosingResource(Resource):
            async def aclose(self):
                closing.set()
                await finish_close.wait()
                await super().aclose()

        resource = ClosingResource()
        reached = []

        async def handler(request, context):
            started.set()
            await asyncio.Event().wait()
            reached.append("after-cancel")
            return fixture.case.response

        task = asyncio.create_task(execute(runtime(fixture, handler, resource_factory=lambda _: resource), fixture))
        await started.wait()
        task.cancel()
        await closing.wait()
        task.cancel()
        finish_close.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert resource.closed == 1 and not reached

    run_async(scenario())


def test_deadline_cancels_waiting_handler_and_closes_resource(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    resource = Resource()

    async def handler(request, context):
        await asyncio.Event().wait()

    encoded = run_async(execute(runtime(fixture, handler, timeout_seconds=0.05,
                                         resource_factory=lambda _: resource), fixture))
    assert ProviderError.model_validate_json(encoded).code == "DEADLINE_EXCEEDED"
    assert resource.closed == 1


def test_shutdown_cancels_inflight_work_and_rejects_new_work(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())

    async def scenario():
        entered = asyncio.Event()
        resource = Resource()

        async def handler(request, context):
            entered.set()
            await asyncio.Event().wait()

        provider = runtime(fixture, handler, resource_factory=lambda _: resource)
        task = asyncio.create_task(execute(provider, fixture))
        await entered.wait()
        await provider.aclose()
        assert task.cancelled() and resource.closed == 1
        response = await execute(provider, fixture)
        assert ProviderError.model_validate_json(response).code == "PROVIDER_UNAVAILABLE"
        await provider.aclose()

    run_async(scenario())


def test_concurrent_requests_have_separate_budgets_resources_and_lifetimes(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    contexts, resources = [], []

    def factory(authorized):
        resource = Resource()
        resources.append(resource)
        return resource

    async def scenario():
        both_entered = asyncio.Event()

        async def handler(request, context):
            contexts.append(context)
            if len(contexts) == 2:
                both_entered.set()
            await both_entered.wait()
            await context.complete(PROMPT, budget=context.budget)
            return fixture.case.response

        provider = runtime(fixture, handler, resource_factory=factory)
        results = await asyncio.gather(execute(provider, fixture), execute(provider, fixture))
        assert all(ProviderEvaluationResponse.model_validate_json(result) for result in results)
        assert contexts[0].budget is not contexts[1].budget
        assert resources[0] is not resources[1]
        assert all(resource.closed == 1 for resource in resources)
        for context in contexts:
            with pytest.raises(ProviderContractError) as error:
                await context.complete(PROMPT, budget=context.budget)
            assert error.value.error.code == "PROVIDER_UNAVAILABLE"
        assert all(len(resource.calls) == 1 for resource in resources)
        await provider.aclose()

    run_async(scenario())


@pytest.mark.parametrize("failure", ["exception", "timeout", "cancelled"])
def test_cleanup_failure_cannot_publish_success(failure, evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())

    class BrokenCleanup(Resource):
        async def aclose(self):
            if failure == "timeout":
                await asyncio.Event().wait()
            if failure == "cancelled":
                raise asyncio.CancelledError
            raise ValueError("private-cleanup-error")

    async def handler(request, context):
        return fixture.case.response

    encoded = run_async(execute(runtime(fixture, handler, cleanup_timeout_seconds=0.01,
                                       resource_factory=lambda _: BrokenCleanup()), fixture))
    assert ProviderError.model_validate_json(encoded).code == "PROVIDER_UNAVAILABLE"
    assert b"private-cleanup-error" not in encoded


def test_input_validation_consumes_the_same_budget(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    clock = SimpleNamespace(now=0.0)
    original = fixture.boundary
    called = []

    class SlowBoundary:
        def prepare(self, *args, **kwargs):
            result = original.prepare(*args, **kwargs)
            clock.now += fixture.case.request.evaluation.execution.total_timeout_seconds + 1
            return result

    async def handler(request, context):
        called.append("handler")
        return fixture.case.response

    fixture.boundary = SlowBoundary()
    encoded = run_async(execute(runtime(fixture, handler, clock=lambda: clock.now), fixture))
    assert ProviderError.model_validate_json(encoded).code == "DEADLINE_EXCEEDED"
    assert not called


def test_dependency_timeout_is_not_claimed_as_total_budget_exhaustion(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())

    async def handler(request, context):
        raise TimeoutError("private-dependency-timeout")

    encoded = run_async(execute(runtime(fixture, handler), fixture))
    assert ProviderError.model_validate_json(encoded).code == "PROVIDER_UNAVAILABLE"


def test_runtime_cannot_expand_trusted_engine_budget(evaluation_case, runtime_case, run_async):
    fixture = runtime_case(evaluation_case())
    called = []

    async def handler(request, context):
        called.append("handler")
        return fixture.case.response

    seconds = fixture.case.request.evaluation.execution.total_timeout_seconds + 1
    encoded = run_async(execute(runtime(fixture, handler, timeout_seconds=seconds), fixture))
    assert ProviderError.model_validate_json(encoded).code == "CONFIGURATION_MISMATCH"
    assert not called


def test_concurrent_services_keep_separate_permissions_and_configuration(profile_case, runtime_case, limits, run_async):
    from copenhagen_fds.provider_runtime.access import ServiceIdentity, ServiceTokenAuthenticator, TokenBinding
    from copenhagen_fds.provider_runtime.boundary import ProviderBoundary, TrustedConfiguration
    from pydantic import SecretStr

    fixtures = [runtime_case(profile_case.create(population_scope=population)) for population in (False, True)]
    bindings = []
    for index, fixture in enumerate(fixtures):
        fixture.credential = SecretStr(f"offline-service-{index}-test-only-token")
        bindings.append(TokenBinding(fixture.credential, ServiceIdentity(f"service-{index}", fixture.identity.grants)))
    boundary = ProviderBoundary(authenticator=ServiceTokenAuthenticator(tuple(bindings)), limits=limits,
        configurations=tuple(TrustedConfiguration(f.case.request.operation, f.case.request.configuration) for f in fixtures))

    async def scenario():
        seen = {}
        entered = asyncio.Event()

        async def handler(request, context):
            seen[request.operation] = (context.identity.service_id, request.configuration)
            if len(seen) == 2:
                entered.set()
            await entered.wait()
            return next(f.case.response for f in fixtures if f.case.request.operation == request.operation)

        provider = ProviderRuntime(boundary=boundary, handlers={f.case.request.operation: handler for f in fixtures},
            timeout_seconds=5.0, cleanup_timeout_seconds=1.0, max_response_bytes=2_000_000)
        responses = await asyncio.gather(*(execute(provider, fixture) for fixture in fixtures))
        for index, (fixture, encoded) in enumerate(zip(fixtures, responses)):
            request = fixture.case.request
            assert seen[request.operation] == (f"service-{index}", request.configuration)
            assert type(fixture.case.response).model_validate_json(encoded).validate_for_request(request)
        denied = await provider.execute(fixtures[1].case.request.operation, fixtures[1].raw,
                                        credential=fixtures[0].credential)
        assert ProviderError.model_validate_json(denied).code == "FORBIDDEN"
        await provider.aclose()

    run_async(scenario())
