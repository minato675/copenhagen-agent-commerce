import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('compare_cart', Path(__file__).parents[1] / 'compare-cart-timing.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CompareCartTimingTest(unittest.TestCase):
    def run_sample(self, mode='single', seconds=10):
        return dict(mode=mode, item_count=8, observed_seconds=seconds, environment='synthetic-test-only',
                    report=dict(incomplete_capture=False,
                                requests=[dict(errors=0, tool_calls=8 if mode == 'single' else 1, core_http_calls=8)]))

    def test_separates_modes_and_reports_small_sample_limit(self):
        rows = module.compare([self.run_sample(), self.run_sample('batch', 2)])
        self.assertEqual(2, len(rows))
        self.assertEqual([1], rows[0]['tool_calls'])
        self.assertEqual([8], rows[0]['core_http_calls'])
        self.assertTrue(rows[0]['descriptive_only'])

    def test_rejects_invalid_duration_and_incomplete_capture(self):
        for seconds in (0, -1, float('nan'), float('inf'), True):
            with self.assertRaises(ValueError):
                module.compare([self.run_sample(seconds=seconds)])
        run = self.run_sample()
        run['report']['incomplete_capture'] = True
        with self.assertRaises(ValueError):
            module.compare([run])

    def test_rejects_failed_runs(self):
        run = self.run_sample()
        run['report']['requests'][0]['errors'] = 1
        with self.assertRaises(ValueError):
            module.compare([run])

    def test_nearest_rank_percentiles(self):
        row = module.compare([self.run_sample(seconds=i) for i in range(1, 21)])[0]
        self.assertEqual(10000, row['total_p50_ms'])
        self.assertEqual(19000, row['total_p95_ms'])
        self.assertFalse(row['descriptive_only'])
