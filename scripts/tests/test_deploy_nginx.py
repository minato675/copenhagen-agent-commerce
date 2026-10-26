"""Deployment ordering and failure safety at the Docker CLI boundary."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_deploy_diagnostics import fixture_environment

ROOT = Path(__file__).resolve().parents[2]
DOCKER = '''#!/usr/bin/env python3
import json
import os
import sys
with open(os.environ['TRACE'], 'a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
if os.environ.get('INVALID_CERT') == '1' and 'run' in sys.argv:
    sys.exit(9)
'''


class DeployNginxTest(unittest.TestCase):
    def execute(self, invalid=False, missing=False):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            docker = work / 'docker'
            docker.write_text(DOCKER)
            docker.chmod(0o755)
            for name in ('compose.yml', '.env'):
                (work / name).touch()
            if not missing:
                (work / 'https.yml').touch()
            env = fixture_environment()
            env.update(PATH=str(work) + ':' + os.environ['PATH'], TRACE=str(work / 'trace'),
                       INVALID_CERT='1' if invalid else '0')
            result = subprocess.run(['sh', str(ROOT / 'scripts/deploy-dev-nginx.sh'),
                                     'compose.yml', 'https.yml', '.env'], cwd=work,
                                    env=env, text=True, capture_output=True, timeout=20)
            commands = [json.loads(line) for line in (work / 'trace').read_text().splitlines()] if (work / 'trace').exists() else []
            return result, commands

    def test_validate_before_recreate_and_reload(self):
        result, commands = self.execute()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(command[:7] == ['compose', '--env-file', '.env', '--file',
                                          'compose.yml', '--file', 'https.yml'] for command in commands))
        commands = [command[7:] for command in commands]
        preflight = next(i for i, command in enumerate(commands) if command[0] == 'run')
        update = next(i for i, command in enumerate(commands) if command[0] == 'up')
        self.assertLess(preflight, update)
        self.assertIn('--no-deps', commands[preflight])
        self.assertIn('--no-deps', commands[update])
        self.assertEqual(commands[-1], ['exec', '-T', 'nginx', 'nginx', '-s', 'reload'])

    def test_invalid_certificate_does_not_replace_running_nginx(self):
        result, commands = self.execute(invalid=True)
        self.assertEqual(result.returncode, 9)
        self.assertFalse(any('up' in command or 'reload' in command for command in commands))

    def test_missing_override_fails_before_any_docker_mutation(self):
        result, commands = self.execute(missing=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, [])


if __name__ == '__main__':
    unittest.main()
