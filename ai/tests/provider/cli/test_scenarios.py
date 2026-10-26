"""304: all handoff cases execute real provider operations before comparisons."""

from pathlib import Path
import json

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.native_cli.scenarios import demo_settings
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.provider_cli.comparison import compare, observe
from copenhagen_fds.provider_cli.profile_scenarios import profile_cases
from copenhagen_fds.provider_cli.runtime import run_mock
from copenhagen_fds.provider_cli.scenarios import evaluation_cases


def test_all_provider_scenarios(run_async):
    settings = demo_settings(PersonalReplaySettings.model_validate(read_json(Path('examples/personal-replay-settings.json'))))
    evaluations, evaluation_expected = evaluation_cases(settings)
    profiles, profile_expected = profile_cases(settings)
    observed = {}
    async def run():
        for name, case in {**evaluations, **profiles}.items():
            before = json.dumps(case.request, sort_keys=True)
            response, execution = await run_mock(before.encode(), case.call, case.server, case.script)
            observed[name] = observe(response, execution)
            assert before == json.dumps(case.request, sort_keys=True)
            assert execution['resources_created'] == execution['resources_closed']
            assert execution['external_api'] == 'NOT_RUN'
    run_async(run())
    report = compare(observed, {**evaluation_expected, **profile_expected})
    assert report['status'] == 'PASSED', [(r['case_id'], r['observed'], r['checks']) for r in report['rows'] if not r['passed']]
    assert len(report['rows']) == 27
