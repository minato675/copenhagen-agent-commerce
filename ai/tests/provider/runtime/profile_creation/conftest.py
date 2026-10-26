"""S15P21A207-302: synthetic observations through the actual authorized runtime."""

from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest
from copenhagen_fds.provider_runtime.execution import ProviderRuntime
from copenhagen_fds.provider_runtime.profile_creation import create_profile_handler


def pytest_addoption(parser):
    parser.addoption("--provider-profile-examples", default=None,
                     help="Export synthetic initial creation pairs under ai/results/evaluation")


@pytest.fixture
def creation_case(profile_case, runtime_case, monkeypatch):
    def make(*, population_scope=False, rows=None, statistics=None, policy=None, configure=None,
             scope=None, handler=create_profile_handler, resource_factory=None):
        original = profile_case.create(population_scope=population_scope)
        data = original.request.model_dump()
        if rows is not None:
            data["replay"]["observations"] = rows
        if statistics:
            data["profile"]["config"].update(statistics)
        if policy:
            data["policy"].update(policy)
        if configure:
            configure(data)
        profile = type(original.request.profile).model_validate(data["profile"])
        admission = type(original.request.policy).model_validate(data["policy"])
        data["configuration"]["content_sha256"] = personalization_hash((profile.config, admission))
        request = ProviderProfileCreateRequest.model_validate(data)
        bound = runtime_case(SimpleNamespace(request=request), scope=scope)
        wall, monotonic = [request.call.sent_at + timedelta(seconds=1)], [0.0]
        monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_creation.utc_now", lambda: wall[0])
        provider = ProviderRuntime(boundary=bound.boundary, handlers={request.operation: handler},
            timeout_seconds=10, cleanup_timeout_seconds=1, max_response_bytes=2_000_000,
            resource_factory=resource_factory, clock=lambda: monotonic[0])

        async def execute():
            return await provider.execute(request.operation, bound.raw, credential=bound.credential)

        return SimpleNamespace(request=request, original=original, bound=bound, wall=wall,
                               monotonic=monotonic, provider=provider, execute=execute)
    return make
