"""304: declared evaluation examples remain valid provider requests."""

from pathlib import Path

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest
from copenhagen_fds.native_cli.scenarios import demo_settings
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.provider_cli.scenarios import evaluation_cases


def test_evaluation_fixture_inventory_and_separation():
    original = PersonalReplaySettings.model_validate(read_json(Path('examples/personal-replay-settings.json')))
    cases, expected = evaluation_cases(demo_settings(original))
    assert len(cases) == 17
    assert set(cases) == set(expected)
    for case in cases.values():
        ProviderEvaluationRequest.model_validate(case.request)
        assert 'expected' not in case.request
        assert case.script is not None
        assert case.request['evaluation']['execution']['gateway'] == 'MOCK'
    assert {expected[n]['amount_stage'] for n in ('cold-normal', 'transition', 'personalized')} == {
        'COLD', 'TRANSITION', 'PERSONALIZED'}
    assert expected['missing-text']['calls'] == 0
    assert expected['auth-missing']['handlers'] == 0
