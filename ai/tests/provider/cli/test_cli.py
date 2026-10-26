"""304: user commands preserve actual outcomes and never print private failures."""

import subprocess
import sys

import pytest

from copenhagen_fds.provider_cli import __main__ as cli


def test_module_entrypoint_without_key_or_network():
    result = subprocess.run([sys.executable, '-m', 'copenhagen_fds.provider_cli', '--help'],
                            capture_output=True, text=True)
    assert result.returncode == 0
    assert 'prepare-demo' in result.stdout and 'verify-suite' in result.stdout


@pytest.mark.parametrize('status,code', [('SUCCESS', 0), ('INCOMPLETE', 3), ('UNAUTHENTICATED', 3)])
def test_single_exit_code_reports_actual_outcome(monkeypatch, capsys, run_async, status, code):
    async def execute(*args, **kwargs):
        return dict(status=status, external_api='NOT_RUN')
    monkeypatch.setattr(cli, 'execute_file', execute)
    monkeypatch.setattr(cli.asyncio, 'run', run_async)
    assert cli.main(['execute', 'request.json', '--call', 'call.json', '--server', 'server.json',
                     '--output', 'out']) == code
    assert status in capsys.readouterr().out


def test_cli_error_never_prints_raw_exception(monkeypatch, capsys):
    def fail(*args):
        raise ValueError('PRIVATE_USER_TEXT_AND_SECRET')
    monkeypatch.setattr(cli, 'prepare', fail)
    assert cli.main(['prepare-demo', '--output', 'out']) == 2
    captured = capsys.readouterr()
    assert 'PRIVATE' not in captured.out + captured.err


def test_gms_mode_not_silently_enabled():
    with pytest.raises(SystemExit) as error:
        cli.main(['execute', 'in', '--call', 'call', '--server', 'server', '--output', 'out', '--mode', 'gms'])
    assert error.value.code == 2


def test_show_keeps_expected_failure_distinct(monkeypatch, capsys):
    monkeypatch.setattr(cli, 'read_bundle', lambda *args, **kwargs: ({}, {'comparisons.json':
        b'{"status":"PASSED","rows":[{"case_id":"auth","observed":{"status":"UNAUTHENTICATED",'
        b'"risk":null,"amount_stage":null,"calls":0,"handlers":0},"passed":true}]}'}))
    assert cli.main(['show', 'out']) == 0
    assert 'UNAUTHENTICATED | None | None | 0 | 0 | True' in capsys.readouterr().out
