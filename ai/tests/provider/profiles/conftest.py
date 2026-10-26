"""S15P21A207-299: existing synthetic policy observations and actual profile calculations."""

from datetime import timedelta
from types import SimpleNamespace

import pytest

from tests.learning.conftest import population
from tests.learning.policy.conftest import policy

from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.population_profiles import PopulationProfileRequest
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.profile_policy import PolicyObservation
from copenhagen_fds.contracts.provider_profiles import (
    ProviderProfileCreateRequest, ProviderProfileCreateResponse, ProviderProfileUpdateRequest, ProviderProfileUpdateResponse,
)
from copenhagen_fds.profile_policy_updates import initialize_profile_policy, update_profile_policy


@pytest.fixture
def profile_case(policy):
    def create(*, population_scope=False, empty=False):
        rows = () if empty else (policy.row("purchase-a"), policy.row("purchase-b", user="b" if population_scope else "a"))
        snapshot = policy.snapshot(rows, population_scope=population_scope)
        p = snapshot.profile
        payload = dict(reference=p.reference, state_version=snapshot.state.state_version, origin=p.origin,
            source_reference=p.source_reference, as_of=p.as_of, generated_at=p.generated_at,
            valid_until=p.valid_until, config=snapshot.config)
        profile = (PopulationProfileRequest(cohort=snapshot.cohort, **payload) if population_scope else
                   PersonalProfileRequest(user_id=p.reference.user_id, currency=p.currency, **payload))
        admission = policy.config()
        request = ProviderProfileCreateRequest(
            operation="CREATE_POPULATION_PROFILE" if population_scope else "CREATE_PERSONAL_PROFILE",
            call=dict(request_id="create-profile", trace_id="profile-trace", sent_at=p.generated_at), profile=profile,
            configuration=dict(configuration_id="synthetic-profile", version="v1",
                               content_sha256=personalization_hash((profile.config, admission))), policy=admission,
            replay=dict(mode="FULL_REPLAY", omitted_purchase="REMOVE", complete_from=snapshot.long_term.start_inclusive,
                        complete_until=p.as_of, observations=rows))
        state = initialize_profile_policy(snapshot, rows, admission)
        response = ProviderProfileCreateResponse(operation=request.operation, call=request.call,
            request_sha256=personalization_hash(request), completed_at=p.generated_at + timedelta(seconds=1), state=state)
        response.validate_for_request(request)
        return SimpleNamespace(request=request, response=response, state=state)

    def update(*, remove=False, population_scope=False):
        case = create(population_scope=population_scope)
        state = case.state
        at = state.published.profile.as_of + timedelta(hours=1)
        rows = []
        for row in (() if remove else state.observations):
            data = row.model_dump()
            data["sample"]["eligibility"]["as_of"] = at
            rows.append(PolicyObservation.model_validate(data))
        request = ProviderProfileUpdateRequest(operation="UPDATE_PROFILE",
            call=dict(request_id="update-profile", trace_id="profile-trace", sent_at=at + timedelta(seconds=1)),
            configuration=case.request.configuration, state=state,
            update=dict(expected_policy_state_version=state.state_version,
                update=dict(expected_state_version=state.candidate.state_version,
                    expected_profile_reference=state.candidate.snapshot.profile.reference, as_of=at,
                    generated_at=at + timedelta(seconds=1), valid_until=at + timedelta(days=1),
                    changes_complete_from=state.published.profile.as_of, changes_complete_until=at)),
            replay=dict(mode="FULL_REPLAY", omitted_purchase="REMOVE",
                complete_from=at - timedelta(days=state.published.config.long_term_days), complete_until=at,
                observations=rows))
        result = update_profile_policy(state, request.update, request.replay.observations)
        response = ProviderProfileUpdateResponse(operation="UPDATE_PROFILE", call=request.call,
            request_sha256=personalization_hash(request), completed_at=at + timedelta(seconds=2), result=result)
        response.validate_for_request(request)
        return SimpleNamespace(request=request, response=response)
    return SimpleNamespace(create=create, update=update)
