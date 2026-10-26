import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("timing", Path(__file__).parents[1] / "analyze-mcp-timing.py")
timing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(timing)


class TimingTest(unittest.TestCase):
    def event(self, seconds, request, detail):
        return f"mcp-server-1 | 2026-09-22T00:00:{seconds:02d}Z INFO MCP timing: requestId={request}, {detail}\n"

    def test_nested_and_overlapping_work_is_not_double_counted(self):
        first, second = "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"
        lines = [self.event(4, first, "stage=service_token, elapsedMs=20"),
                 self.event(5, first, "stage=core_http, method=GET, elapsedMs=4000"),
                 self.event(6, first, "tool=search_products, error=false, elapsedMs=6000"),
                 self.event(8, second, "tool=get_cart, error=false, elapsedMs=4000")]
        result = timing.summarize(lines, 10)
        self.assertEqual(8000, result["mcp_busy_union_ms"])
        self.assertEqual(10000, result["tool_elapsed_sum_ms"])
        self.assertEqual(2000, result["outside_mcp_or_unattributed_ms"])
        self.assertEqual(4000, result["requests"][0]["core_http_work_sum_ms"])

    def test_partial_capture_does_not_invent_host_time(self):
        result = timing.summarize([self.event(4, "11111111-1111-4111-8111-111111111111",
                                              "stage=core_http, method=GET, elapsedMs=3000")], 10)
        self.assertTrue(result["incomplete_capture"])
        self.assertIsNone(result["outside_mcp_or_unattributed_ms"])

    def test_unrelated_and_sensitive_lines_are_not_exported(self):
        result = timing.summarize(["Authorization: private-value", "invalid timing record"], 10)
        self.assertEqual([], result["requests"])
        self.assertNotIn("private-value", str(result))

    def test_timestamp_less_logs_still_count_but_disable_residual(self):
        line = "MCP timing: requestId=11111111-1111-4111-8111-111111111111, tool=get_cart, error=true, elapsedMs=1"
        result = timing.summarize([line], 5)
        self.assertEqual(1, result["requests"][0]["errors"])
        self.assertIsNone(result["outside_mcp_or_unattributed_ms"])


if __name__ == "__main__":
    unittest.main()
