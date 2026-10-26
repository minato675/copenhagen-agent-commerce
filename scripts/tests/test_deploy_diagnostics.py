"""Failure injection at the Docker CLI boundary; no daemon or EC2 access."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def fixture_environment():
    """Keep host/CI configuration and secrets out of synthetic Docker runs."""
    return {key: os.environ[key] for key in ('PATH', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR')
            if key in os.environ}


DOCKER = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
mode = os.environ['TEST_MODE']
with open(os.environ['TEST_TRACE'], 'a') as trace:
    trace.write(' '.join(args) + '\n')
    if args[:2] == ['buildx', 'bake']:
        manifest = json.loads(Path(args[args.index('--file') + 1]).read_text())
        for target in manifest['target'].values():
            trace.write('BUILD ' + target['context'] + ' ' + target['tags'][0] + '\n')
if args[0] == 'compose' and 'exec' in args and 'smoke-http' in args:
    args = ['run'] + args[args.index('curl') + 1:]
if args[0] == 'compose':
    command = args[1:]
    while command and command[0] in ('--env-file', '--file'):
        command = command[2:]
if mode.startswith('deploy-'):
    secret = 'synthetic.p[a]ss&word'
    if args[0] == 'image':
        sys.exit(1 if mode == 'deploy-unavailable' and args[-1] == 'app:old' else 0)
    if args[0] == 'inspect':
        print('status=exited ExitCode=1 OOMKilled=false health=unhealthy ' + secret)
        sys.exit(8 if mode == 'deploy-diagnostic-error' else 0)
    env = args[args.index('--env-file') + 1]
    candidate = '.tmp.' in env
    if command[0] == 'up':
        print('UP candidate=' + str(candidate), flush=True)
        if candidate and mode not in ('deploy-success', 'deploy-nginx-check', 'deploy-nginx-reload'):
            sys.exit(7)
        if not candidate and mode == 'deploy-rollback-failed':
            sys.exit(9)
    elif command[0] == 'ps':
        print('test-container')
        if mode == 'deploy-diagnostic-error' and '--all' in command:
            sys.exit(8)
    elif command[0] == 'exec':
        if candidate and ((mode == 'deploy-nginx-check' and command[-1] == '-t')
                          or (mode == 'deploy-nginx-reload' and command[-1] == 'reload')):
            sys.exit(6)
    elif command[0] == 'logs':
        print('APPLICATION FAILURE ' + secret + ' ' + os.environ.get('TEST_SECRET', ''))
        if mode == 'deploy-diagnostic-error':
            sys.exit(8)
    sys.exit(0)
if args[0] == 'compose':
    env = Path(args[args.index('--env-file') + 1])
    secret = next((line.split('=', 1)[1] for line in env.read_text().splitlines()
                   if line.startswith('CORE_DB_PASSWORD=')), '')
    if command[0] == 'config':
        print('{"services":{"nginx":{"image":"nginx:1.28"},"core-db":{"image":"postgres:17-alpine"},"pay-db":{"image":"postgres:17-alpine"},"merchant-db":{"image":"postgres:17-alpine"},"redis":{"image":"redis:7-alpine"},"smoke-http":{"image":"curlimages/curl:8.16.0"}}}'
              if '--format' in command else 'services: {}')
    elif command[0] == 'up':
        if mode == 'smoke-success':
            if 'core-service:broken' in env.read_text():
                sys.exit(7)
        elif not mode.startswith('http-'):
            sys.exit(7)
    elif command[0] == 'exec':
        if 'wget' in ' '.join(command):
            if mode == 'http-dependency-request':
                sys.exit(4)
            if mode == 'smoke-success':
                print('{"status":"UP","ucp":{"services":{"dev.ucp.shopping":[{"transport":"rest"}]}},"keys":[1]}')
            else:
                print('response-body-secret')
        elif 'psql' in ' '.join(command):
            if 'SELECT id' in sys.stdin.read():
                print(os.environ['COMPOSE_PROJECT_NAME'])
    elif command[0] == 'ps':
        print('test-container' if '--quiet' in command else 'pay-service restarting')
        if mode == 'diagnostic-error':
            sys.exit(8)
    elif command[0] == 'logs':
        print('application startup failed password=' + secret)
        if mode == 'diagnostic-error':
            sys.exit(9)
    elif command[0] == 'down':
        print('CLEANUP')
        if mode == 'cleanup-error':
            sys.exit(10)
elif args[0] == 'run':
    if mode in ('http-connection', 'http-timeout'):
        sys.exit(7 if mode == 'http-connection' else 28)
    if mode == 'http-status':
        print('response-body-secret\n503')
    elif mode == 'http-json':
        print('response-body-secret\n200')
    elif mode == 'http-assertion':
        print('{"status":"DOWN", "secret":"response-body-secret"}\n200')
    else:
        url = args[-1]
        path = url.split('http://nginx')[-1]
        if '/actuator/health' in url:
            print('{"status":"UP"}\n200')
        elif path.startswith('/merchant/v1/'):
            print('{"errorCode":"AUTHENTICATION_REQUIRED"}\n401')
        else:
            print('{"status":404,"path":"' + path + '"}\n404')
elif args[0] == 'inspect':
    print('test-image' if args[2] == '{{.Image}}' else
          'status=restarting ExitCode=1 OOMKilled=false health=unhealthy')
    if mode == 'diagnostic-error':
        sys.exit(11)
elif args[:2] == ['image', 'inspect']:
    print('test-image')
'''


class SmokeDiagnosticsTest(unittest.TestCase):
    def run_smoke(self, mode, cache=False, ci=False):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            docker = work / 'docker'
            docker.write_text(DOCKER)
            docker.chmod(0o755)
            trace = work / 'trace'
            artifacts = work / 'artifacts'
            env = fixture_environment()
            env.update(PATH=f'{work}:{os.environ["PATH"]}',
                       TEST_MODE=mode, TEST_TRACE=str(trace),
                       SMOKE_ARTIFACT_DIR=str(artifacts), SMOKE_BASE_REF='HEAD')
            if cache:
                cache_directory = work / 'gradle-cache'
                (cache_directory / 'wrapper').mkdir(parents=True)
                env['SMOKE_GRADLE_CACHE'] = str(cache_directory)
                runtime_cache = work / 'runtime-cache'
                runtime_cache.mkdir()
                (runtime_cache / 'images.tar').write_text('synthetic cache')
                env['SMOKE_RUNTIME_CACHE'] = str(runtime_cache)
            if ci:
                head = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'],
                                               text=True).strip()
                env.update(CI='true', CI_PIPELINE_SOURCE='merge_request_event',
                           CI_COMMIT_SHA=head, CI_MERGE_REQUEST_DIFF_BASE_SHA=head,
                           CI_MERGE_REQUEST_TARGET_BRANCH_NAME='develop')
            result = subprocess.run(['bash', str(ROOT / 'scripts/deploy-smoke.sh')],
                                    env=env, text=True, capture_output=True, timeout=30)
            output = result.stdout + result.stderr
            files = {p.name: p.read_text() for p in artifacts.iterdir()}
            return result.returncode, output, files, trace.read_text()

    def test_snapshot_precedes_cleanup_and_masks_password(self):
        code, output, files, trace = self.run_smoke('startup-error')
        self.assertEqual(code, 7, output)
        self.assertIn('FAIL exit=7 phase=fresh-deploy', output)
        self.assertIn('ExitCode=1 OOMKilled=false', output)
        self.assertIn('application startup failed password=[redacted]', output)
        self.assertLess(output.index('application startup failed'), output.index('CLEANUP'))
        self.assertIn('password=[redacted]', files['fresh-deploy-containers.log'])
        self.assertNotIn('{{json .State}}', trace)
        self.assertIn('down --volumes --remove-orphans', trace)

    def test_diagnostic_failure_preserves_status_and_cleanup(self):
        code, output, _, trace = self.run_smoke('diagnostic-error')
        self.assertEqual(code, 7, output)
        self.assertIn('CLEANUP', output)
        self.assertIn('image rm', trace)

    def test_cleanup_failure_preserves_original_status(self):
        code, output, _, _ = self.run_smoke('cleanup-error')
        self.assertEqual(code, 7, output)
        self.assertIn('FAIL cleanup phase=fresh-deploy', output)

    def test_http_failures_report_target_and_condition_without_body(self):
        for mode, expected_code, reason in (
            ('http-connection', 7, 'reason=connection'),
            ('http-timeout', 28, 'reason=timeout'),
            ('http-status', 1, 'reason=status expected=200 actual=503'),
            ('http-json', 1, 'reason=json assertion=.status == "UP"'),
            ('http-assertion', 1, 'reason=json assertion=.status == "UP"'),
            ('http-dependency-request', 4, 'reason=request exit=4'),
            ('http-dependency-json', 1, 'reason=json assertion=.status == "UP"'),
        ):
            with self.subTest(mode=mode):
                code, output, files, _ = self.run_smoke(mode)
                self.assertEqual(code, expected_code, output)
                self.assertIn(reason, output)
                self.assertIn('target=', output)
                self.assertNotIn('response-body-secret', output)
                self.assertNotIn('response-body-secret', str(files))
                self.assertIn('CLEANUP', output)

    def test_merchant_auth_is_required_for_baseline_and_candidate(self):
        script = (ROOT / 'scripts/deploy-smoke.sh').read_text(encoding='utf-8')
        calls = re.findall(r'^\s*check_http(?: (.+))?$', script, re.MULTILINE)

        self.assertEqual(calls, ['401', '401', '"$merchant_status"', '401', '401'])
        self.assertIn(
            'merchant_status=401\nfor index in "${!services[@]}"; do',
            script,
        )
        self.assertRegex(
            script,
            r'if \[ "\$\{services\[\$index\]\}" = pay-service \]; then\s+'
            r'merchant_status=401\s+fi',
        )

    def test_full_smoke_keeps_all_nine_http_rounds_and_rollback(self):
        code, output, files, trace = self.run_smoke('smoke-success')
        self.assertEqual(code, 0, output)
        self.assertEqual(trace.count('exec -T smoke-http curl '), 90)
        self.assertNotIn('\nrun ', trace)
        self.assertEqual(output.count('persisted data'), 18)
        self.assertIn('PASS fresh deployment', output)
        self.assertIn('PASS upgrade deployment', output)
        self.assertIn('ROLLBACK SUCCESS', output)
        self.assertIn('PASS failed deployment restored', output)
        for service in ('pg-mock', 'pay-service', 'ucp-merchant-mock',
                        'core-service', 'mcp-server'):
            self.assertIn('TIMING phase=upgrade-' + service, files['result.log'])
        self.assertIn('TIMING total_seconds=', files['result.log'])

    def test_gradle_cache_import_and_export_keep_the_full_source_build(self):
        code, output, _, trace = self.run_smoke('smoke-success', cache=True)
        self.assertEqual(code, 0, output)
        self.assertIn('CACHE gradle=restored', output)
        self.assertIn('--target seed', trace)
        self.assertIn('--target export', trace)
        self.assertNotIn('--no-cache', trace)
        self.assertLess(trace.index('--target seed'), trace.index('pg-mock:candidate'))
        self.assertEqual(trace.count('exec -T smoke-http curl '), 90)
        self.assertIn('PASS failed deployment restored', output)
        self.assertIn('TIMING phase=cache-export', output)
        self.assertIn('CACHE runtime=restored', output)
        self.assertIn('image load --input', trace)
        self.assertIn('pull --quiet core-db pay-db merchant-db redis smoke-http', trace)
        saved = next(line for line in trace.splitlines() if line.startswith('image save '))
        self.assertIn('postgres:17-alpine', saved)
        self.assertIn('docker.io/library/eclipse-temurin:21-jdk-alpine', saved)
        self.assertIn('nginx:1.28', saved)
        self.assertNotIn('core-service', saved)
        self.assertIn('TIMING phase=runtime-cache-export', output)

    def test_ci_archives_both_source_contexts_consistently(self):
        code, output, _, trace = self.run_smoke('smoke-success', ci=True)
        self.assertEqual(code, 0, output)
        self.assertEqual(len(re.findall(r'^BUILD .*/candidate/backend ', trace, re.M)), 5)
        self.assertEqual(len(re.findall(r'^BUILD .*/base/backend ', trace, re.M)), 5)
        self.assertIn('PASS failed deployment restored', output)


class DeployDiagnosticsTest(unittest.TestCase):
    def run_deploy(self, mode, recorded_image=True, https=False):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            docker = work / 'docker'
            docker.write_text(DOCKER)
            docker.chmod(0o755)
            (work / 'compose.yml').write_text('services: {}')
            (work / 'https.yml').write_text('services: {}')
            original = "CORE_DB_PASSWORD='synthetic.p[a]ss&word'\n"
            if recorded_image:
                original += 'CORE_IMAGE=app:old\n'
            (work / '.env').write_text(original)
            trace = work / 'trace'
            env = fixture_environment()
            env.update(PATH=f'{work}:{os.environ["PATH"]}',
                       TEST_MODE=mode, TEST_TRACE=str(trace), TEST_SECRET='environment-secret')
            result = subprocess.run(['sh', str(ROOT / 'scripts/deploy-dev-service.sh'),
                                     'core-service', 'CORE_IMAGE', 'app:new', 'compose.yml', '1']
                                    + (['https.yml'] if https else []),
                                    cwd=work, env=env, text=True, stderr=subprocess.STDOUT,
                                    stdout=subprocess.PIPE, timeout=30)
            self.assertFalse(list(work.glob('.env.tmp.*')))
            return result.returncode, result.stdout, (work / '.env').read_text(), original, trace.read_text()

    def test_failed_deploy_diagnosed_before_successful_rollback(self):
        # CI values must not mask unrelated fixture text such as OOMKilled=false.
        with patch.dict(os.environ, {'CI_TEST_TOKEN': 'false', 'HOST_SECRET': 'unhealthy'}):
            code, output, current, original, trace = self.run_deploy('deploy-startup')
        self.assertEqual(code, 7, output)
        self.assertEqual(current, original)
        self.assertIn('service=core-service image=app:new step=startup-health exit=7', output)
        self.assertIn('ExitCode=1 OOMKilled=false health=unhealthy', output)
        self.assertIn('ROLLBACK SUCCESS', output)
        self.assertLess(output.index('APPLICATION FAILURE'), output.index('UP candidate=False'))
        self.assertNotIn('synthetic.p[a]ss&word', output)
        self.assertNotIn('environment-secret', output)
        self.assertIn('[redacted]', output)
        self.assertNotIn('{{json .State}}', trace)

    def test_nginx_failure_steps(self):
        for step in ('nginx-check', 'nginx-reload'):
            with self.subTest(step=step):
                code, output, current, original, _ = self.run_deploy('deploy-' + step)
                self.assertEqual(code, 6, output)
                self.assertIn('step=' + step, output)
                self.assertIn('ROLLBACK SUCCESS', output)
                self.assertEqual(current, original)

    def test_failed_and_unavailable_rollback(self):
        for mode, result in (('deploy-rollback-failed', 'FAILED'),
                             ('deploy-unavailable', 'UNAVAILABLE')):
            with self.subTest(mode=mode):
                code, output, current, original, _ = self.run_deploy(mode)
                self.assertEqual(code, 7, output)
                self.assertIn('ROLLBACK ' + result, output)
                self.assertEqual(current, original)
        code, output, _, _, _ = self.run_deploy('deploy-startup', recorded_image=False)
        self.assertEqual(code, 7, output)
        self.assertIn('ROLLBACK UNAVAILABLE', output)

    def test_diagnostic_errors_do_not_skip_rollback(self):
        code, output, _, _, _ = self.run_deploy('deploy-diagnostic-error')
        self.assertEqual(code, 7, output)
        self.assertIn('ROLLBACK SUCCESS', output)

    def test_success_updates_environment_without_diagnostics(self):
        code, output, current, _, trace = self.run_deploy('deploy-success')
        self.assertEqual(code, 0, output)
        self.assertIn('CORE_IMAGE=app:new', current)
        self.assertNotIn('FAIL', output)
        self.assertNotIn('ROLLBACK', output)
        self.assertNotIn('logs --no-color', trace)

    def test_https_override_is_used_for_deployment_diagnostics_and_rollback(self):
        for mode in ('deploy-success', 'deploy-startup', 'deploy-nginx-reload'):
            with self.subTest(mode=mode):
                code, output, current, original, trace = self.run_deploy(mode, https=True)
                self.assertEqual(code, 0 if mode == 'deploy-success' else 7 if mode == 'deploy-startup' else 6, output)
                commands = [line for line in trace.splitlines() if line.startswith('compose ')]
                self.assertTrue(commands)
                self.assertTrue(all('--file compose.yml --file https.yml' in line for line in commands))
                if code:
                    self.assertIn('ROLLBACK SUCCESS', output)
                    self.assertEqual(current, original)


if __name__ == '__main__':
    unittest.main()
