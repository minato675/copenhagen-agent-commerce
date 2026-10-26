"""Check job selection and required smoke gates in the checked-in CI configuration."""
from copy import deepcopy
from pathlib import Path
import re
import unittest

from test_ci_pipeline import load_raw_config

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ('core', 'mcp', 'pay', 'merchant-mock', 'pg-mock')


def matches_path(pattern, path):
    expression = re.escape(pattern).replace(r'\*\*/', '(?:.*/)?')
    expression = expression.replace(r'\*\*', '.*').replace(r'\*', '[^/]*')
    return re.fullmatch(expression, path) is not None


def selected(job, env, paths):
    for rule in job.get('rules', [{}]):
        condition = rule.get('if', 'True')
        condition = re.sub(r'\$([A-Z_]+)', lambda match: repr(env.get(match[1], '')), condition)
        condition = condition.replace('&&', ' and ').replace('||', ' or ')
        if not eval(condition, {'__builtins__': {}}, {}):
            continue
        if 'changes' in rule and not any(matches_path(pattern, path)
                                         for pattern in rule['changes'] for path in paths):
            continue
        return rule.get('when', 'on_success') != 'never'
    return False


class SmokePipelineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = load_raw_config()

    def job(self, name):
        job = deepcopy(self.config[name])
        parents = job.pop('extends', [])
        merged = {}
        for parent in parents if isinstance(parents, list) else [parents]:
            merged.update(self.job(parent))
        merged.update(job)
        return merged

    def jobs(self, event, paths, branch='develop'):
        env = {'CI_PIPELINE_SOURCE': event, 'CI_MERGE_REQUEST_TARGET_BRANCH_NAME': branch,
               'CI_COMMIT_BRANCH': branch if event == 'push' else ''}
        if not selected(self.config['workflow'], env, paths):
            return {}
        return {name: self.job(name) for name, config in self.config.items()
                if not name.startswith('.') and isinstance(config, dict)
                and 'stage' in config and selected(self.job(name), env, paths)}

    def test_backend_and_delivery_paths_always_select_smoke(self):
        paths = {'backend/new-service/Dockerfile', 'backend/apps/core-service/Dockerfile',
                 'infra/nginx/nginx.conf', 'infra/docker/smoke.override.yml',
                 'scripts/deploy-smoke.sh', 'scripts/smoke-base.sh'}
        paths.add('scripts/smoke-dockerfiles.py')
        paths.add('infra/docker/smoke-gradle-cache.Dockerfile')
        # Every concrete representative of every delivery rule must have its smoke gate.
        for service in SERVICES:
            for rule in self.job(service + '-deploy-dev')['rules']:
                paths.update(pattern.replace('**/*', 'file').replace('*', 'file')
                             for pattern in rule.get('changes', []))
        for path in sorted(paths):
            for event in ('push', 'merge_request_event'):
                with self.subTest(path=path, event=event):
                    jobs = self.jobs(event, [path])
                    self.assertIn('deploy-smoke', jobs)
                    self.assertIs(jobs['deploy-smoke']['allow_failure'], False)
                    for name, job in jobs.items():
                        for need in job.get('needs', []):
                            dependency = need if isinstance(need, str) else need['job']
                            if isinstance(need, dict) and need.get('optional'):
                                continue
                            self.assertIn(dependency, jobs, f'{name} requires {dependency}')
                    for dependency in jobs['deploy-smoke']['dependencies']:
                        self.assertIn(dependency, jobs)
                    if event == 'merge_request_event':
                        self.assertFalse(any(name.endswith(('-docker-build', '-deploy-dev'))
                                             for name in jobs))

    def test_each_delivery_job_requires_successful_smoke_without_optional_bypass(self):
        for service in SERVICES:
            for suffix in ('docker-build', 'deploy-dev'):
                name = f'{service}-{suffix}'
                with self.subTest(job=name):
                    job = self.job(name)
                    gate = [need for need in job['needs']
                            if isinstance(need, dict) and need['job'] == 'deploy-smoke']
                    self.assertEqual(len(gate), 1)
                    self.assertFalse(gate[0].get('optional', False))
                    self.assertFalse(gate[0]['artifacts'])
                    self.assertEqual(job.get('when', 'on_success'), 'on_success')
                    self.assertFalse(any(rule.get('when') in ('always', 'manual')
                                         or rule.get('allow_failure') for rule in job['rules']))
            self.assertEqual(self.job(service + '-deploy-dev')['resource_group'], 'development-ec2')

    def test_documentation_and_unrelated_events_do_not_trigger_smoke_or_delivery(self):
        for event, paths, branch in (
            ('merge_request_event', ['docs/README.md'], 'develop'),
            ('push', ['docs/README.md'], 'develop'),
            ('push', ['backend/build.gradle'], 'feature'),
            ('merge_request_event', ['backend/build.gradle'], 'main'),
            ('web', ['backend/build.gradle'], 'develop'),
        ):
            with self.subTest(event=event, paths=paths, branch=branch):
                jobs = self.jobs(event, paths, branch)
                self.assertNotIn('deploy-smoke', jobs)
                self.assertFalse(any(name.endswith(('-docker-build', '-deploy-dev')) for name in jobs))

    def test_config_tests_run_for_baseline_and_pipeline_changes(self):
        for event in ('push', 'merge_request_event'):
            for path in ('scripts/smoke-base.sh', 'scripts/tests/test_smoke_base.py',
                         'infra/gitlab/smoke.gitlab-ci.yml', '.gitlab-ci.yml'):
                self.assertIn('ci-config-test', self.jobs(event, [path]))

    def test_smoke_keeps_stage_barrier_and_reads_the_pinned_baseline(self):
        job = self.job('deploy-smoke')
        self.assertNotIn('needs', job)  # Wait for all selected lint/test/build jobs.
        self.assertCountEqual(job['dependencies'], [service + '-build' for service in SERVICES])
        self.assertEqual(job['variables']['SMOKE_USE_CI_ARTIFACTS'], 'true')
        self.assertEqual(job['variables']['GIT_DEPTH'], '0')
        self.assertEqual(job['script'], ['bash scripts/deploy-smoke.sh'])
        script = (ROOT / 'scripts/deploy-smoke.sh').read_text()
        self.assertIn('base_sha=$(sh "$root/scripts/smoke-base.sh")', script)
        self.assertLess(script.index('base_sha='), script.index('docker info'))

    def test_smoke_cache_is_separate_from_current_pipeline_application_artifacts(self):
        job = self.job('deploy-smoke')
        self.assertEqual(job['cache']['key'], 'smoke-gradle-v1-$CI_COMMIT_REF_SLUG')
        self.assertEqual(job['cache']['fallback_keys'], ['smoke-gradle-v1-develop'])
        self.assertEqual(job['cache']['paths'], ['tmp/smoke-gradle-cache/', 'tmp/smoke-runtime-cache/'])
        self.assertCountEqual(job['dependencies'], [service + '-build' for service in SERVICES])
        self.assertNotIn('cache', job['artifacts']['paths'])


if __name__ == '__main__':
    unittest.main()
