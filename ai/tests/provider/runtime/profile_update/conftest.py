"""S15P21A207-303: known synthetic previous state and full replay through Runtime."""

from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.profile_policy import PolicyObservation
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyUpdateRequest
from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateRequest
from copenhagen_fds.provider_runtime.execution import ProviderRuntime
from copenhagen_fds.provider_runtime.profile_update import update_profile_handler


def pytest_addoption(parser):
    parser.addoption("--provider-update-examples", default=None,
                     help="Export synthetic update pairs under ai/results/evaluation")


@pytest.fixture
def update_case(profile_case, runtime_case, monkeypatch):
    def make(*, population_scope=False, state=None, rows=None, cutoff=None, configure=None, scope=None,
             handler=update_profile_handler):
        state = state or profile_case.create(population_scope=population_scope).state
        snapshot = state.candidate.snapshot
        cutoff = cutoff or snapshot.profile.as_of + timedelta(hours=1)
        carried = []
        for row in state.observations if rows is None else rows:
            data = row.model_dump()
            data["sample"]["eligibility"]["as_of"] = cutoff
            carried.append(PolicyObservation.model_validate(data))
        data = dict(expected_policy_state_version=state.state_version, update=dict(
            expected_state_version=state.candidate.state_version, expected_profile_reference=snapshot.profile.reference,
            as_of=cutoff, generated_at=cutoff + timedelta(seconds=1), valid_until=cutoff + timedelta(days=1),
            changes_complete_from=snapshot.profile.as_of, changes_complete_until=cutoff))
        if configure:
            configure(data)
        update = ProfilePolicyUpdateRequest.model_validate(data)
        config = update.update.personal_config or update.update.population_config or snapshot.config
        admission = update.config or state.evaluation.config
        request = ProviderProfileUpdateRequest(operation="UPDATE_PROFILE", state=state, update=update,
            call=dict(request_id="synthetic-update-call", trace_id="synthetic-update-trace", sent_at=update.update.generated_at),
            configuration=dict(configuration_id="synthetic-profile", version="v1",
                               content_sha256=personalization_hash((config, admission))),
            replay=dict(mode="FULL_REPLAY", omitted_purchase="REMOVE", complete_until=cutoff,
                        complete_from=cutoff - timedelta(days=config.long_term_days), observations=carried))
        bound = runtime_case(SimpleNamespace(request=request), scope=scope)
        wall, monotonic = [request.call.sent_at + timedelta(seconds=1)], [0.0]
        monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_update.utc_now", lambda: wall[0])
        provider = ProviderRuntime(boundary=bound.boundary, handlers={"UPDATE_PROFILE": handler},
            timeout_seconds=10, cleanup_timeout_seconds=1, max_response_bytes=2_000_000,
            clock=lambda: monotonic[0])

        async def execute():
            return await provider.execute("UPDATE_PROFILE", bound.raw, credential=bound.credential)

        return SimpleNamespace(request=request, bound=bound, wall=wall, monotonic=monotonic,
                               provider=provider, execute=execute)
    return make
