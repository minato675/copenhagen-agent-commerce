"""Pinned smoke baselines with real Git histories; no remote or Docker access."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SmokeBaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'scripts').mkdir()
        shutil.copy(ROOT / 'scripts/smoke-base.sh', self.root / 'scripts')
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(('CI', 'GIT_', 'SMOKE_'))}
        self.git('init', '-b', 'develop')
        self.git('config', 'user.name', 'Smoke test')
        self.git('config', 'user.email', 'smoke@example.invalid')
        self.base = self.commit('baseline')
        self.git('checkout', '-b', 'feature')
        self.candidate = self.commit('candidate')
        self.git('checkout', 'develop')
        self.other = self.commit('another MR')
        self.git('update-ref', 'refs/remotes/origin/develop', self.other)
        self.git('checkout', 'feature')
        self.mr = dict(CI='true', CI_PIPELINE_SOURCE='merge_request_event',
                       CI_COMMIT_SHA=self.candidate,
                       CI_MERGE_REQUEST_TARGET_BRANCH_NAME='develop',
                       CI_MERGE_REQUEST_EVENT_TYPE='detached',
                       CI_MERGE_REQUEST_DIFF_BASE_SHA=self.base)

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env,
                              check=True, text=True, capture_output=True).stdout.strip()

    def commit(self, message):
        self.git('commit', '--allow-empty', '-m', message)
        return self.git('rev-parse', 'HEAD')

    def resolve(self, variables):
        return subprocess.run(['sh', str(self.root / 'scripts/smoke-base.sh')],
                              cwd=self.root, env={**self.env, **variables}, text=True,
                              capture_output=True, timeout=10)

    def assert_baseline(self, variables, expected):
        result = self.resolve(variables)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), expected)

    def assert_rejected(self, variables, message):
        result = self.resolve(variables)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertIn(message, result.stderr)

    def test_other_merge_and_retry_keep_mr_baseline(self):
        self.assert_baseline(self.mr, self.base)
        self.git('checkout', 'develop')
        newer = self.commit('yet another MR')
        self.git('update-ref', 'refs/remotes/origin/develop', newer)
        self.git('checkout', 'feature')
        self.assert_baseline(self.mr, self.base)
        # The old implementation instead picked the moving target and failed.
        self.assert_rejected({'SMOKE_BASE_REF': 'origin/develop'}, 'not an ancestor')

    def test_ci_cannot_override_baseline_with_local_ref_or_target_head(self):
        self.assert_baseline({**self.mr, 'SMOKE_BASE_REF': 'HEAD',
                              'CI_MERGE_REQUEST_TARGET_BRANCH_SHA': self.other}, self.base)

    def test_push_uses_before_sha_for_multi_commit_push_and_retry(self):
        self.git('checkout', 'develop')
        self.commit('first commit in push')
        head = self.commit('last commit in push')
        variables = dict(CI='true', CI_PIPELINE_SOURCE='push', CI_COMMIT_BRANCH='develop',
                         CI_COMMIT_SHA=head, CI_COMMIT_BEFORE_SHA=self.other)
        self.assert_baseline(variables, self.other)
        self.commit('a later push')
        self.git('update-ref', 'refs/remotes/origin/develop', 'HEAD')
        self.git('checkout', '--detach', head)
        self.assert_baseline(variables, self.other)

    def test_merged_result_and_train_use_pinned_target_sha(self):
        self.git('merge', '--no-ff', 'develop', '-m', 'temporary merged result')
        for event in ('merged_result', 'merge_train'):
            with self.subTest(event=event):
                variables = {**self.mr, 'CI_COMMIT_SHA': self.git('rev-parse', 'HEAD'),
                             'CI_MERGE_REQUEST_EVENT_TYPE': event,
                             'CI_MERGE_REQUEST_TARGET_BRANCH_SHA': self.other}
                self.assert_baseline(variables, self.other)
                variables.pop('CI_MERGE_REQUEST_TARGET_BRANCH_SHA')
                self.assert_rejected(variables, 'CI_MERGE_REQUEST_TARGET_BRANCH_SHA')

    def test_missing_zero_malformed_and_unavailable_baselines_fail_closed(self):
        for value, message in (('', 'full commit SHA'), ('0' * 40, 'is zero'),
                               ('develop', 'full commit SHA'), ('a' * 40, 'unavailable')):
            with self.subTest(value=value):
                self.assert_rejected({**self.mr, 'CI_MERGE_REQUEST_DIFF_BASE_SHA': value}, message)
                self.assert_rejected(dict(CI='true', CI_PIPELINE_SOURCE='push',
                                          CI_COMMIT_BRANCH='develop', CI_COMMIT_SHA=self.candidate,
                                          CI_COMMIT_BEFORE_SHA=value), message)

    def test_wrong_candidate_and_nonancestor_are_rejected(self):
        self.assert_rejected({**self.mr, 'CI_COMMIT_SHA': self.other}, 'checkout does not match')
        self.assert_rejected({**self.mr, 'CI_MERGE_REQUEST_DIFF_BASE_SHA': self.other},
                             'not an ancestor')

    def test_unsupported_ci_contexts_do_not_fall_back_to_local_mode(self):
        for changes, message in (
            ({'CI_MERGE_REQUEST_TARGET_BRANCH_NAME': 'main'}, 'targeting develop'),
            ({'CI_MERGE_REQUEST_EVENT_TYPE': 'unknown'}, 'unsupported merge request'),
            ({'CI_PIPELINE_SOURCE': 'push', 'CI_COMMIT_BRANCH': 'feature'}, 'develop pushes'),
            ({'CI_PIPELINE_SOURCE': 'web'}, 'unsupported CI pipeline'),
            ({'CI_PIPELINE_SOURCE': ''}, 'unsupported CI pipeline'),
            ({'CI_COMMIT_SHA': ''}, 'CI_COMMIT_SHA'),
        ):
            with self.subTest(changes=changes):
                self.assert_rejected({**self.mr, **changes}, message)

    def test_local_explicit_ref_and_default_are_validated(self):
        self.assert_baseline({'SMOKE_BASE_REF': self.base}, self.base)
        self.assert_baseline({'SMOKE_BASE_REF': 'HEAD'}, self.candidate)
        self.git('update-ref', 'refs/remotes/origin/develop', self.base)
        self.assert_baseline({}, self.base)
        self.assert_rejected({'SMOKE_BASE_REF': 'missing'}, 'unavailable')

    def test_deploy_smoke_validates_baseline_before_docker_and_records_shas(self):
        shutil.copy(ROOT / 'scripts/deploy-smoke.sh', self.root / 'scripts')
        docker = self.root / 'docker'
        docker.write_text('#!/bin/sh\nprintf called > "$TEST_DOCKER_MARKER"\nexit 13\n')
        docker.chmod(0o755)
        marker = self.root / 'docker-called'
        for base, expected in (('0' * 40, 1), (self.base, 13)):
            with self.subTest(base=base):
                artifacts = self.root / base
                env = {**self.env, **self.mr, 'CI_MERGE_REQUEST_DIFF_BASE_SHA': base,
                       'PATH': f'{self.root}:{self.env["PATH"]}',
                       'TEST_DOCKER_MARKER': str(marker), 'SMOKE_ARTIFACT_DIR': str(artifacts)}
                result = subprocess.run(['bash', str(self.root / 'scripts/deploy-smoke.sh')],
                                        cwd=self.root, env=env, text=True,
                                        capture_output=True, timeout=10)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                log = (artifacts / 'result.log').read_text()
                self.assertIn(f'FAIL exit={expected} phase=prepare', log)
                if expected == 1:
                    self.assertFalse(marker.exists())
                else:
                    self.assertTrue(marker.exists())
                    self.assertIn(f'base={self.base} candidate={self.candidate}', log)


if __name__ == '__main__':
    unittest.main()
