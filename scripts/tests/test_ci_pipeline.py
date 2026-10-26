"""Evaluate pipeline selection and required artifacts for representative changes."""
import fnmatch
from pathlib import Path
import re
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ('core', 'mcp', 'pay', 'merchant-mock', 'pg-mock')


class Reference(list):
    pass


class CiLoader(yaml.SafeLoader):
    pass


CiLoader.add_constructor('!reference', lambda loader, node: Reference(loader.construct_sequence(node)))


def load_raw_config():
    config = {}
    for path in (ROOT / 'infra/gitlab').glob('*.gitlab-ci.yml'):
        config.update(yaml.load(path.read_text(), Loader=CiLoader))

    def resolve(value):
        if isinstance(value, Reference):
            target = config
            for key in value:
                target = target[key]
            return resolve(target)
        if isinstance(value, list):
            result = []
            for child in value:
                resolved = resolve(child)
                result.extend(resolved if isinstance(child, Reference) and isinstance(resolved, list)
                              else [resolved])
            return result
        if isinstance(value, dict):
            return {key: resolve(child) for key, child in value.items()}
        return value

    return resolve(config)


def load_config():
    config = load_raw_config()

    def job(name):
        result = {}
        parents = config[name].get('extends', [])
        for parent in [parents] if isinstance(parents, str) else parents:
            result.update(job(parent))
        result.update(config[name])
        return result

    return {name: job(name) for name, value in config.items()
            if isinstance(value, dict) and name not in ('workflow', 'variables', 'default')
            and not name.startswith('.')}


def selected_rule(job, source, path):
    variables = {'CI_PIPELINE_SOURCE': source, 'CI_COMMIT_BRANCH': 'develop',
                 'CI_MERGE_REQUEST_TARGET_BRANCH_NAME': 'develop'}
    for rule in job['rules']:
        expression = rule.get('if', '')
        groups = expression.replace('(', '').replace(')', '').split('||')
        condition = any(all(variables.get(key) == value
                            for key, value in re.findall(r'\$(\w+)\s*==\s*"([^"]+)"', group))
                        for group in groups)
        patterns = rule.get('changes', ['*'])
        changed = any(fnmatch.fnmatchcase(path, pattern) or
                      fnmatch.fnmatchcase(path, pattern.replace('**/', '')) for pattern in patterns)
        if condition and changed:
            return rule if rule.get('when') != 'never' else None
    return None


def selected(job, source, path):
    return selected_rule(job, source, path) is not None


class CiPipelineTest(unittest.TestCase):
    def test_smoke_has_every_service_artifact_and_stage_barrier(self):
        jobs = load_config()
        for path in ('backend/apps/core-service/src/main/java/App.java',
                     'backend/gradle.properties', 'backend/libs/internal-api-contract/build.gradle',
                     'infra/nginx/nginx.conf', 'infra/gitlab/README.md',
                     'infra/docker/https.override.yml', 'scripts/deploy-dev-nginx.sh',
                     'scripts/deploy-smoke.sh', 'scripts/smoke-base.sh', 'scripts/build-ci-image.sh',
                     'scripts/ci-jar.sh', 'scripts/build-ci-jar.sh', 'scripts/run-ci-gradle.sh',
                     'docker-compose.yml', '.gitlab-ci.yml'):
            with self.subTest(path=path):
                active = {name for name, job in jobs.items() if selected(job, 'merge_request_event', path)}
                self.assertIn('deploy-smoke', active)
                self.assertNotIn('needs', jobs['deploy-smoke'], 'Smoke must wait for all earlier stages')
                for dependency in jobs['deploy-smoke']['dependencies']:
                    self.assertIn(dependency, active)
                    artifacts = jobs[dependency]['artifacts']['paths']
                    self.assertEqual(len(artifacts), 2)
                    self.assertTrue(artifacts[1].endswith('/app.jar.metadata'))
                    test_job = dependency.replace('-build', '-test')
                    rule = selected_rule(jobs[dependency], 'merge_request_event', path)
                    self.assertEqual(rule['variables']['REQUIRE_TEST_ARTIFACT'],
                                     'true' if test_job in active else 'false')
                for name in active:
                    for dependency in jobs[name].get('needs', []):
                        if isinstance(dependency, str):
                            self.assertIn(dependency, active)
                        elif not dependency.get('optional', False):
                            self.assertIn(dependency['job'], active)
                self.assertFalse(any(name.endswith('-deploy-dev') for name in active))

    def test_develop_delivery_downloads_matching_jar_and_stays_serialized(self):
        jobs = load_config()
        for service in SERVICES:
            build = jobs[f'{service}-docker-build']
            self.assertEqual(build['needs'], [{'job': 'deploy-smoke', 'artifacts': False},
                                             {'job': f'{service}-build', 'artifacts': True}])
            self.assertEqual(jobs[f'{service}-deploy-dev']['resource_group'], 'development-ec2')
        active = {name for name, job in jobs.items()
                  if selected(job, 'push', 'backend/apps/core-service/src/main/java/App.java')}
        self.assertIn('core-test', active)
        self.assertIn('core-build', active)
        self.assertIn('core-deploy-dev', active)
        self.assertNotIn('pay-deploy-dev', active)
        self.assertIn('deploy-smoke', active)
        for dependency in jobs['deploy-smoke']['dependencies']:
            self.assertIn(dependency, active)

    def test_nginx_changes_deploy_https_without_rebuilding_service_images(self):
        jobs = load_config()
        for path in ('infra/nginx/nginx.https.conf', 'scripts/deploy-dev-nginx.sh'):
            with self.subTest(path=path):
                active = {name for name, job in jobs.items() if selected(job, 'push', path)}
                self.assertIn('nginx-deploy-dev', active)
                self.assertIn('nginx-config-test', active)
                self.assertIn('deploy-smoke', active)
                self.assertFalse(any(name.endswith('-docker-build') for name in active))
                self.assertEqual(jobs['nginx-deploy-dev']['resource_group'], 'development-ec2')
                for need in jobs['nginx-deploy-dev']['needs']:
                    self.assertIn(need['job'], active)
                    self.assertFalse(need.get('optional', False))

    def test_https_oauth_settings_redeploy_core_after_smoke(self):
        jobs = load_config()
        path = 'infra/docker/https.override.yml'
        for source in ('merge_request_event', 'push'):
            with self.subTest(source=source):
                active = {name for name, job in jobs.items() if selected(job, source, path)}
                for required in ('core-test', 'core-build', 'nginx-config-test', 'deploy-smoke'):
                    self.assertIn(required, active)
                self.assertEqual(selected_rule(jobs['core-build'], source, path)
                                 ['variables']['REQUIRE_TEST_ARTIFACT'], 'true')
                delivery = {name for name in active if name.endswith(('-docker-build', '-deploy-dev'))}
                self.assertEqual(delivery, {'core-docker-build', 'core-deploy-dev', 'nginx-deploy-dev'}
                                 if source == 'push' else set())
                for name in active:
                    for need in jobs[name].get('needs', []):
                        if isinstance(need, str):
                            self.assertIn(need, active)
                        elif not need.get('optional', False):
                            self.assertIn(need['job'], active)

    def test_service_deploy_syncs_and_applies_https_before_upgrading_application(self):
        job = load_config()['core-deploy-dev']
        script = '\n'.join(job['script'])
        self.assertIn('nginx.https.conf', script)
        self.assertIn('https.override.yml', script)
        self.assertLess(script.index('sh scripts/deploy-dev-nginx.sh'),
                        script.index('./scripts/deploy-dev-service.sh'))
        self.assertIn("'$DEV_DEPLOY_WAIT_TIMEOUT' 'infra/docker/https.override.yml'", script)


if __name__ == '__main__':
    unittest.main()
