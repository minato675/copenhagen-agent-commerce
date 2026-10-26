"""S15P21A207-301: published-profile stages and expiry through the actual handler."""

import pytest

from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationResponse
from copenhagen_fds.native_cli.scenarios import demo_request, demo_settings, observable_demo


@pytest.mark.parametrize("name,options,stage,status", [
    ("cold", {}, "COLD", "SUCCESS"),
    ("transition", {"profile": "transition", "amount": 30000}, "TRANSITION", "SUCCESS"),
    ("personalized", {"profile": "full"}, "PERSONALIZED", "SUCCESS"),
    ("expired-profile", {"profile": "expired"}, "COLD", "SUCCESS"),
    ("expired-baseline", {"baseline": "expired"}, "UNAVAILABLE", "INCOMPLETE"),
])
def test_actual_stage_and_expiry_are_preserved_without_profile_writes(
    provider_case, replay_cases, run_async, monkeypatch, name, options, stage, status,
):
    def configure(original):
        source = demo_request(name, observable_demo(), demo_settings(replay_cases.settings), **options)
        return type(source).model_validate({**source.model_dump(), "execution": original.execution.model_dump()})

    case = provider_case(configure=configure)
    before = case.request.model_dump_json()

    def forbidden(*args, **kwargs):
        pytest.fail("evaluation must not create or update a profile")

    monkeypatch.setattr("copenhagen_fds.profile_updates.update_profile", forbidden)
    monkeypatch.setattr("copenhagen_fds.profile_policy_updates.update_profile_policy", forbidden)
    monkeypatch.setattr("copenhagen_fds.profile_policy_updates.initialize_profile_policy", forbidden)
    response = ProviderEvaluationResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
    amount = next(f for f in response.features if f.feature_id == "amount_minor")
    assert response.outcome.status == status and amount.stage == stage
    if stage == "TRANSITION":
        assert 0 < amount.personal_weight < 1
    elif stage == "PERSONALIZED":
        assert amount.personal_weight == 1
    elif stage == "COLD":
        assert amount.personal_weight == 0
    else:
        assert amount.score is None and response.behavior_score is None
    if name == "expired-profile":
        assert case.request.evaluation.personal.state.published.profile.reference in response.versions.profiles
    assert case.request.model_dump_json() == before and case.adapter.closed == 1
    assert response.result_valid_until is None and response.reuse_status == "NOT_ASSESSED"
