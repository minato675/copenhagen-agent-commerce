"""Build grouping preserves runtime instructions and unknown source builders."""
import importlib.util
from pathlib import Path
import shutil
import os
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('smoke_dockerfiles', ROOT / 'scripts/smoke-dockerfiles.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class SmokeDockerfilesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.output = self.root / 'generated'
        for module in builder.MODULES:
            destination = self.root / 'backend' / module / 'Dockerfile'
            destination.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / 'backend' / module / 'Dockerfile', destination)

    def generated(self, module):
        return (self.output / (module.split('/')[-1] + '.Dockerfile')).read_text()

    def test_shared_compile_keeps_each_runtime_byte_for_byte(self):
        builder.prepare(self.root, self.output)
        prefixes = []
        for module in builder.MODULES:
            original = (self.root / 'backend' / module / 'Dockerfile').read_text()
            generated = self.generated(module)
            prefix, runtime = generated.split('\n\nFROM ', 1)
            prefixes.append(prefix)
            self.assertEqual(runtime, original.split('\n\nFROM ', 1)[1])
            for task_module in builder.MODULES:
                self.assertIn(':' + task_module.replace('/', ':') + ':bootJar', prefix)
        self.assertEqual(len(set(prefixes)), 1)
        self.assertIn('JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -XX:TieredStopAtLevel=1"', prefixes[0])
        self.assertIn('ARG GRADLE_MAX_WORKERS', prefixes[0])
        self.assertIn('./gradlew "$@" ', prefixes[0])

    def test_historical_builder_without_worker_override_still_shares_compile(self):
        for module in builder.MODULES:
            path = self.root / 'backend' / module / 'Dockerfile'
            runtime = path.read_text().split('\n\nFROM ', 1)[1]
            path.write_text('FROM docker.io/library/eclipse-temurin:21-jdk-alpine AS builder\n'
                            'WORKDIR /workspace\nCOPY . .\n'
                            'RUN --mount=type=cache,target=/root/.gradle \\\n'
                            '    chmod +x gradlew && ./gradlew :'
                            + module.replace('/', ':') + ':bootJar --no-daemon\n\nFROM ' + runtime)
        builder.prepare(self.root, self.output)
        prefixes = {self.generated(module).split('\n\nFROM ', 1)[0]
                    for module in builder.MODULES}
        self.assertEqual(len(prefixes), 1)
        for module in builder.MODULES:
            self.assertIn(':' + module.replace('/', ':') + ':bootJar', next(iter(prefixes)))

    def test_custom_build_steps_are_executed_unchanged(self):
        module = 'apps/pay-service'
        path = self.root / 'backend' / module / 'Dockerfile'
        original = path.read_text().replace('COPY . .', 'COPY . .\nRUN echo custom-build')
        path.write_text(original)
        builder.prepare(self.root, self.output)
        self.assertEqual(self.generated(module), original)
        self.assertNotIn(':apps:pay-service:bootJar', self.generated('apps/core-service'))

    def test_different_jdk_is_not_combined_with_other_builders(self):
        module = 'apps/core-service'
        path = self.root / 'backend' / module / 'Dockerfile'
        original = path.read_text().replace('21-jdk-alpine', '25-jdk-alpine')
        path.write_text(original)
        builder.prepare(self.root, self.output)
        self.assertEqual(self.generated(module), original)

    def test_runtime_changes_are_preserved(self):
        module = 'apps/mcp-server'
        path = self.root / 'backend' / module / 'Dockerfile'
        original = path.read_text().replace('USER app', 'ENV RUNTIME_TEST=1\nUSER app')
        path.write_text(original)
        builder.prepare(self.root, self.output)
        self.assertEqual(self.generated(module).split('\n\nFROM ', 1)[1],
                         original.split('\n\nFROM ', 1)[1])

    def test_service_specific_ignore_is_preserved_and_not_grouped(self):
        module = 'apps/pay-service'
        path = self.root / 'backend' / module / 'Dockerfile'
        ignore = b'apps/core-service/\n'
        path.with_name('Dockerfile.dockerignore').write_bytes(ignore)
        builder.prepare(self.root, self.output)
        self.assertEqual(self.generated(module), path.read_text())
        self.assertEqual((self.output / 'pay-service.Dockerfile.dockerignore').read_bytes(), ignore)
        self.assertNotIn(':apps:pay-service:bootJar', self.generated('apps/core-service'))

    def test_unrecognized_line_endings_are_preserved(self):
        module = 'apps/core-service'
        path = self.root / 'backend' / module / 'Dockerfile'
        original = path.read_bytes().replace(b'\n', b'\r\n')
        path.write_bytes(original)
        builder.prepare(self.root, self.output)
        self.assertEqual((self.output / 'core-service.Dockerfile').read_bytes(), original)

    def test_bake_builds_every_service_from_the_selected_source_and_generated_file(self):
        builder.prepare(self.root, self.output, 'isolated-project', 'base')
        manifest = json.loads((self.output / 'bake.json').read_text())
        services = [module.split('/')[-1] for module in builder.MODULES]
        self.assertEqual(manifest['group']['default']['targets'], services)
        self.assertEqual(set(manifest['target']), set(services))
        for service, target in manifest['target'].items():
            self.assertEqual(target['context'], str(self.root / 'backend'))
            self.assertEqual(target['tags'], ['isolated-project/' + service + ':base'])
            self.assertEqual(Path(target['dockerfile']).read_text(),
                             (self.output / (service + '.Dockerfile')).read_text())

    def test_image_cache_only_collects_literal_qualified_base_images(self):
        path = self.root / 'backend/apps/core-service/Dockerfile'
        path.write_text(path.read_text() + '\nFROM builder AS copied\nFROM ${PRIVATE_BASE}/jdk AS custom\n')
        builder.prepare(self.root, self.output)
        self.assertEqual(set((self.output / 'base-images.txt').read_text().splitlines()), {
            'docker.io/library/eclipse-temurin:21-jdk-alpine',
            'docker.io/library/eclipse-temurin:21-jre-alpine',
        })


@unittest.skipUnless(shutil.which('docker'), 'Docker Compose CLI is not installed')
class SmokeComposeTest(unittest.TestCase):
    def test_probe_and_faster_polling_preserve_health_and_dependencies(self):
        env = {key: os.environ[key] for key in ('PATH', 'HOME') if key in os.environ}
        env['SMOKE_NGINX_IMAGE'] = 'synthetic/nginx:smoke'
        command = ['docker', 'compose', '--env-file', '/dev/null',
                   '-f', str(ROOT / 'docker-compose.yml')]
        original = json.loads(subprocess.check_output(command + ['config', '--format', 'json'], env=env))
        optimized = json.loads(subprocess.check_output(command + [
            '-f', str(ROOT / 'infra/docker/smoke.override.yml'), 'config', '--format', 'json'], env=env))
        for module in builder.MODULES:
            service = module.split('/')[-1]
            before, after = original['services'][service], optimized['services'][service]
            self.assertEqual(before.get('depends_on'), after.get('depends_on'))
            self.assertEqual(after['environment']['JAVA_TOOL_OPTIONS'], '-XX:TieredStopAtLevel=1')
            for key, value in before.get('environment', {}).items():
                self.assertEqual(after['environment'][key], value)
            for field in ('test', 'timeout', 'start_period'):
                self.assertEqual(before['healthcheck'][field], after['healthcheck'][field])
            self.assertEqual(after['healthcheck']['interval'], '2s')
            self.assertEqual(after['healthcheck']['retries'], 60)
        probe = optimized['services']['smoke-http']
        self.assertNotIn('ports', probe)
        self.assertNotIn('volumes', probe)
        self.assertEqual(probe['image'], 'docker.io/curlimages/curl:8.16.0')


if __name__ == '__main__':
    unittest.main()
