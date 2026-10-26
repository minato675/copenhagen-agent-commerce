"""304: independent synthetic profile histories and intentional conflict fixtures."""

from pathlib import Path

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest, ProviderProfileUpdateRequest
from copenhagen_fds.native_cli.scenarios import demo_settings
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.provider_cli.profile_scenarios import profile_cases


def test_profile_fixture_contracts_and_deliberate_conflict():
    settings = demo_settings(PersonalReplaySettings.model_validate(read_json(Path('examples/personal-replay-settings.json'))))
    cases, expected = profile_cases(settings)
    assert len(cases) == 10
    for name, case in cases.items():
        assert case.script is None
        model = ProviderProfileUpdateRequest if case.call.operation == 'UPDATE_PROFILE' else ProviderProfileCreateRequest
        if name == 'update-stale-version':
            with pytest.raises(ValidationError):
                model.model_validate(case.request)
        else:
            model.model_validate(case.request)
    population = cases['create-population'].request
    assert len(population['profile']['cohort']['member_user_ids']) == 2
    assert expected['update-held']['actions'] == ['HELD']
    assert cases['update-withdrawn'].request['replay']['observations'] == []
