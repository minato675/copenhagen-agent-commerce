"""304: individual calls never read expectations or mutate original inputs."""
import json
from copenhagen_fds.provider_cli.single import execute_file


def test_single_call_is_independent_of_expectations(prepared, tmp_path, run_async):
    (prepared / 'expected.json').unlink()
    before = (prepared / 'cold-normal.request.json').read_bytes()
    output = tmp_path / 'one'
    summary = run_async(execute_file(prepared / 'cold-normal.request.json',
        prepared / 'cold-normal.call.json', prepared / 'cold-normal.server.json', output,
        script_path=prepared / 'cold-normal.script.json'))
    assert summary['status'] == 'SUCCESS'
    assert (output / 'request.json').read_bytes() == before
    assert (prepared / 'cold-normal.request.json').read_bytes() == before
    assert json.loads((output / 'manifest.json').read_bytes())['expectations_read'] is False
