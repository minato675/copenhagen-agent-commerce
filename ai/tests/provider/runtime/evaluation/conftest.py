"""S15P21A207-301: unexecuted native inputs and scripted completions through Runtime."""

from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, evaluation_configuration_hash
from copenhagen_fds.provider_runtime.evaluation import evaluate_handler
from copenhagen_fds.provider_runtime.execution import ProviderRuntime


def pytest_addoption(parser):
    parser.addoption("--provider-evaluation-examples", default=None,
                     help="Export synthetic executed evaluation pairs under ai/results/evaluation")


@pytest.fixture
def provider_case(final_cases, runtime_case):
    def make(*, configure=None, handler=evaluate_handler, **options):
        case = final_cases.prepare(**options)
        if configure is not None:
            case.request = configure(case.request)
        request = ProviderEvaluationRequest(operation="EVALUATE", evaluation=case.request,
            call=dict(request_id="synthetic-evaluation-call", trace_id=case.request.trace_id,
                      sent_at=case.request.feature_input.evaluation.evaluated_at),
            configuration=dict(configuration_id="synthetic-config", version="v1",
                               content_sha256=evaluation_configuration_hash(case.request)))
        bound = runtime_case(SimpleNamespace(request=request))
        case.adapter.closed = 0

        async def close():
            case.adapter.closed += 1

        case.adapter.aclose = close
        provider = ProviderRuntime(boundary=bound.boundary, handlers={"EVALUATE": handler},
            timeout_seconds=10.0, cleanup_timeout_seconds=1.0, max_response_bytes=2_000_000,
            resource_factory=lambda _: case.adapter, clock=lambda: case.now[0])

        async def execute():
            return await provider.execute("EVALUATE", bound.raw, credential=bound.credential)

        return SimpleNamespace(request=request, adapter=case.adapter, now=case.now,
                               bound=bound, provider=provider, execute=execute)
    return make
