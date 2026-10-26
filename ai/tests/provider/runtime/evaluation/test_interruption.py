"""S15P21A207-301: whole-call expiry, caller cancellation and private partial records."""

import asyncio

import pytest

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.native_evaluation import NativeEvaluationCancelled
from copenhagen_fds.provider_runtime.evaluation import evaluate_provider_request
from copenhagen_fds.provider_runtime.evaluation_projection import project_evaluation_response


@pytest.mark.parametrize("expire_at,calls", [("before", 0), ("extraction", 1), ("comparison", 2)])
def test_total_budget_expiry_blocks_later_calls_and_discards_late_success(provider_case, run_async, expire_at, calls):
    case = provider_case()
    if expire_at == "before":
        original = case.bound.boundary.prepare

        def slow_boundary(*args, **kwargs):
            authorized = original(*args, **kwargs)
            case.now[0] = 11.0
            return authorized

        case.bound.boundary.prepare = slow_boundary
    else:
        def expire(stage):
            if stage == expire_at:
                case.now[0] = 11.0
        case.adapter.on_call = expire
    error = ProviderError.model_validate_json(run_async(case.execute()))
    assert error.code == "DEADLINE_EXCEEDED"
    assert len(case.adapter.calls) == calls
    assert case.adapter.closed == (0 if expire_at == "before" else 1)


@pytest.mark.parametrize("stage,index", [("extraction", 1), ("comparison", 2)])
def test_caller_cancellation_retains_private_evidence_and_closes_resource(provider_case, run_async, stage, index):
    retained = []

    async def handler(request, context):
        try:
            return (await evaluate_provider_request(request, context)).response
        except NativeEvaluationCancelled as error:
            retained.append(project_evaluation_response(request, error.result))
            raise

    case = provider_case(handler=handler)

    async def scenario():
        started = asyncio.Event()
        original = case.adapter.complete

        async def blocked(prompt, *, budget):
            if prompt.stage == stage:
                started.set()
                await asyncio.Event().wait()
            return await original(prompt, budget=budget)

        case.adapter.complete = blocked
        task = asyncio.create_task(case.execute())
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert task.cancelled()

    run_async(scenario())
    partial = retained[0]
    assert partial.outcome.status == "CANCELLED" and partial.outcome.recommendation is None
    assert partial.stages[index].status == "CANCELLED"
    assert all(s.status == "NOT_RUN" for s in partial.stages[index + 1:])
    assert len(partial.features) == 9 and partial.behavior_score is None
    assert case.adapter.closed == 1
