"""CI JAR provenance and image packaging, without a Docker daemon."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_deploy_diagnostics import fixture_environment


ROOT = Path(__file__).resolve().parents[2]
MODULES = ('apps/core-service', 'apps/mcp-server', 'apps/pay-service',
           'mocks/ucp-merchant-mock', 'mocks/pg-mock')


class CiArtifactTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'scripts').mkdir()
        for script in ('ci-jar.sh', 'build-ci-image.sh', 'build-ci-jar.sh', 'run-ci-gradle.sh'):
            shutil.copy(ROOT / 'scripts' / script, self.root / 'scripts' / script)
        self.env = dict(fixture_environment(), CI_COMMIT_SHA='a' * 40, CI_PIPELINE_ID='123',
                        PATH=f'{self.root}:{os.environ["PATH"]}')

    def jar(self, module):
        return self.root / 'backend' / module / 'build/libs/app.jar'

    def run_script(self, script, *args):
        return subprocess.run(['sh', str(self.root / 'scripts' / script), *args],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def record(self, module):
        jar = self.jar(module)
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(f'jar for {module}'.encode())
        result = self.run_script('ci-jar.sh', 'record', module)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_each_service_artifact_is_verified(self):
        for module in MODULES:
            with self.subTest(module=module):
                self.record(module)
                result = self.run_script('ci-jar.sh', 'verify', module)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_empty_modified_and_foreign_artifacts_fail(self):
        module = MODULES[0]
        for invalid in ('missing', 'empty', 'changed', 'commit', 'pipeline', 'module', 'metadata'):
            with self.subTest(invalid=invalid):
                self.record(module)
                jar = self.jar(module)
                metadata = jar.with_name('app.jar.metadata')
                if invalid == 'missing':
                    jar.unlink()
                elif invalid in ('empty', 'changed'):
                    jar.write_bytes(b'' if invalid == 'empty' else b'other jar')
                elif invalid == 'metadata':
                    metadata.unlink()
                else:
                    lines = metadata.read_text().splitlines()
                    lines[{'commit': 0, 'pipeline': 1, 'module': 2}[invalid]] = 'other'
                    metadata.write_text('\n'.join(lines) + '\n')
                result = self.run_script('ci-jar.sh', 'verify', module)
                self.assertNotEqual(result.returncode, 0)

    def test_missing_ci_identity_and_unknown_module_fail(self):
        self.record(MODULES[0])
        result = self.run_script('ci-jar.sh', 'verify', '../other')
        self.assertNotEqual(result.returncode, 0)
        del self.env['CI_PIPELINE_ID']
        result = self.run_script('ci-jar.sh', 'verify', MODULES[0])
        self.assertNotEqual(result.returncode, 0)

    def test_test_artifact_reuse_skips_gradle_and_missing_required_artifact_fails(self):
        module = MODULES[0]
        self.record(module)
        wrapper = self.root / 'backend/gradlew'
        wrapper.write_text('#!/bin/sh\nexit 99\n')
        wrapper.chmod(0o755)
        self.env['REQUIRE_TEST_ARTIFACT'] = 'true'
        result = self.run_script('build-ci-jar.sh', module)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.jar(module).unlink()
        self.jar(module).with_name('app.jar.metadata').unlink()
        result = self.run_script('build-ci-jar.sh', module)
        self.assertEqual(result.returncode, 1, result.stderr)

    def test_unchanged_smoke_service_builds_once_and_rejects_foreign_artifact(self):
        module = MODULES[0]
        (self.root / 'backend').mkdir()
        wrapper = self.root / 'backend/gradlew'
        wrapper.write_text('''#!/bin/sh
test "$1" = :apps:core-service:bootJar || exit 98
mkdir -p apps/core-service/build/libs
printf 'fresh jar' > apps/core-service/build/libs/app.jar
''')
        wrapper.chmod(0o755)
        self.env['REQUIRE_TEST_ARTIFACT'] = 'false'
        result = self.run_script('build-ci-jar.sh', module)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.jar(module).read_text(), 'fresh jar')
        self.env['CI_PIPELINE_ID'] = 'other-pipeline'
        result = self.run_script('build-ci-jar.sh', module)
        self.assertEqual(result.returncode, 1, result.stderr)
    def test_image_uses_only_verified_service_jar_and_cleans_context(self):
        module = MODULES[0]
        self.record(module)
        docker = self.root / 'docker'
        docker.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[1:]
assert args[args.index('--target') + 1] == 'ci-runtime'
assert args[args.index('--tag') + 1] == 'test:sha'
context = pathlib.Path(args[-1])
files = list(context.rglob('*.jar'))
assert len(files) == 1
assert files[0].read_bytes() == b'jar for apps/core-service'
assert not list(context.rglob('gradlew'))
pathlib.Path(os.environ['CONTEXT_PATH']).write_text(str(context))
sys.exit(int(os.environ.get('DOCKER_EXIT', '0')))
''')
        docker.chmod(0o755)
        context_path = self.root / 'context-path'
        self.env['CONTEXT_PATH'] = str(context_path)
        for code in (0, 7):
            self.env['DOCKER_EXIT'] = str(code)
            result = self.run_script('build-ci-image.sh', module, 'test:sha')
            self.assertEqual(result.returncode, code, result.stderr)
            self.assertFalse(Path(context_path.read_text()).exists())
        context_path.unlink()
        self.jar(module).unlink()
        result = self.run_script('build-ci-image.sh', module, 'test:sha')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(context_path.exists(), 'Docker must not run without the artifact')

    def test_smoke_packages_all_ci_artifacts_without_source_fallback(self):
        from test_deploy_diagnostics import DOCKER

        shutil.copy(ROOT / 'scripts/deploy-smoke.sh', self.root / 'scripts/deploy-smoke.sh')
        shutil.copy(ROOT / 'scripts/smoke-base.sh', self.root / 'scripts/smoke-base.sh')
        shutil.copy(ROOT / 'scripts/smoke-dockerfiles.py', self.root / 'scripts/smoke-dockerfiles.py')
        git = self.root / 'git'
        git.write_text('#!/bin/sh\nprintf "%s\\n" "$CI_COMMIT_SHA"\n')
        git.chmod(0o755)
        docker = self.root / 'docker'
        docker.write_text(DOCKER)
        docker.chmod(0o755)
        trace = self.root / 'trace'
        self.env.update(SMOKE_USE_CI_ARTIFACTS='true', TEST_MODE='startup-error',
                        TEST_TRACE=str(trace), SMOKE_ARTIFACT_DIR=str(self.root / 'results'))
        for module in MODULES:
            self.record(module)
            shutil.copy(ROOT / 'backend' / module / 'Dockerfile',
                        self.root / 'backend' / module / 'Dockerfile')
        result = subprocess.run(['bash', str(self.root / 'scripts/deploy-smoke.sh')],
                                env=self.env, text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
        builds = [line for line in trace.read_text().splitlines() if '--target ci-runtime' in line]
        self.assertEqual(len(builds), 5)
        self.assertNotIn('buildx bake', trace.read_text(), 'CI candidate must only package verified JARs')
        for module in MODULES:
            self.assertTrue(any(f'/backend/{module}/Dockerfile' in line for line in builds))
        trace.unlink()
        self.jar(MODULES[-1]).unlink()
        result = subprocess.run(['bash', str(self.root / 'scripts/deploy-smoke.sh')],
                                env=self.env, text=True, capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(trace.exists(), 'Missing artifact must fail before any Docker call')


if __name__ == '__main__':
    unittest.main()
