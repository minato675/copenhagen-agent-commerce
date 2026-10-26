"""CI worker override must be explicit, validated, and forwarded to Gradle."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class CiGradleTest(unittest.TestCase):
    def test_worker_override_and_default(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'backend').mkdir()
            shutil.copy(ROOT / 'scripts/run-ci-gradle.sh', root / 'scripts')
            wrapper = root / 'backend/gradlew'
            wrapper.write_text('#!/bin/sh\nprintf "argument=%s\\n" "$@"\n')
            wrapper.chmod(0o755)
            for value in ('', '1', '2', '0', '-1', 'two', '2 --parallel'):
                with self.subTest(workers=value):
                    result = subprocess.run(
                        ['sh', str(root / 'scripts/run-ci-gradle.sh'), ':apps:core-service:test'],
                        env=dict(os.environ, CI_GRADLE_MAX_WORKERS=value),
                        text=True, capture_output=True, timeout=10,
                    )
                    if value in ('', '1', '2'):
                        self.assertEqual(result.returncode, 0, result.stderr)
                        self.assertIn('argument=:apps:core-service:test', result.stdout)
                        self.assertIn('argument=--build-cache', result.stdout)
                        if value:
                            self.assertIn(f'argument=--max-workers={value}', result.stdout)
                        else:
                            self.assertNotIn('argument=--max-workers', result.stdout)
                    else:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertNotIn('argument=', result.stdout)


if __name__ == '__main__':
    unittest.main()
