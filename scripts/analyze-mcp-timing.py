"""Summarize existing MCP timing logs without exporting raw log contents."""
import argparse
import json
import math
import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

STAMP = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)")
TIMING = re.compile(
    r"MCP timing: requestId=([0-9a-fA-F-]{36}), "
    r"(?:tool=([a-z_]+), error=(true|false|null)|"
    r"stage=(service_token|core_http)(?:, method=(GET|POST|PATCH|DELETE))?), elapsedMs=(\d+)\s*$"
)


def union_ms(intervals):
    end = None
    total = 0
    for start, stop in sorted(intervals):
        total += stop - max(start, end if end is not None else start) if end is None or stop > end else 0
        end = max(stop, end if end is not None else stop)
    return round(total * 1000)


def summarize(lines, observed_seconds=None):
    requests = defaultdict(list)
    missing_time = 0
    for line in lines:
        match = TIMING.search(line)
        if not match:
            continue
        request, tool, error, stage, method, elapsed = match.groups()
        # The application timestamp is the last ISO timestamp before the timing message.
        stamps = STAMP.findall(line[:match.start()])
        end = datetime.fromisoformat(stamps[-1].replace("Z", "+00:00")).timestamp() if stamps else None
        missing_time += end is None
        requests[request].append(dict(tool=tool, error=error, stage=stage, method=method,
                                      elapsed_ms=int(elapsed), end=end))
    rows, intervals, tools = [], [], defaultdict(list)
    warnings = ["Core HTTP includes network, Core and merchant work; these cannot be separated here.",
                "Capture may include other users. Correlate request IDs or run an isolated QA window.",
                "Repeated tool calls are not proof of duplicate queries; arguments are not collected."]
    for request, events in requests.items():
        completions = [e for e in events if e["tool"]]
        for event in completions:
            tools[event["tool"]].append(event["elapsed_ms"])
            if event["end"] is not None:
                intervals.append((event["end"] - event["elapsed_ms"] / 1000, event["end"]))
        rows.append(dict(request_id=request, tools=[e["tool"] for e in completions],
                         tool_calls=len(completions), errors=sum(e["error"] == "true" for e in completions),
                         tool_elapsed_sum_ms=sum(e["elapsed_ms"] for e in completions),
                         core_http_calls=sum(e["stage"] == "core_http" for e in events),
                         core_http_work_sum_ms=sum(e["elapsed_ms"] for e in events if e["stage"] == "core_http"),
                         service_token_work_sum_ms=sum(e["elapsed_ms"] for e in events if e["stage"] == "service_token"),
                         incomplete=len(completions) != 1))
    incomplete = any(row["incomplete"] for row in rows) or missing_time > 0 or not intervals
    if incomplete:
        warnings.append("Incomplete capture or ambiguous request IDs: do not infer host time from this sample.")
    busy = union_ms(intervals)
    stats = {}
    for name, values in sorted(tools.items()):
        ordered = sorted(values)
        stats[name] = dict(count=len(values), p50_ms=ordered[math.ceil(len(values) * .50) - 1],
                           p95_ms=ordered[math.ceil(len(values) * .95) - 1])
    if any(len(v) < 20 for v in tools.values()):
        warnings.append("Fewer than 20 calls per tool: percentiles are descriptive only, not a stable baseline.")
    report = dict(requests=rows, tool_stats=stats, mcp_busy_union_ms=busy,
                  tool_elapsed_sum_ms=sum(row["tool_elapsed_sum_ms"] for row in rows),
                  incomplete_capture=incomplete, warnings=warnings)
    if observed_seconds is not None:
        observed = round(observed_seconds * 1000)
        report["observed_total_ms"] = observed
        report["outside_mcp_or_unattributed_ms"] = None if incomplete or busy > observed else observed - busy
        warnings.append("Outside-MCP time includes host, network, rendering and user pauses; it is NOT model inference time.")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--observed-seconds", type=float, help="Send-to-final-render elapsed time, including any pauses")
    args = parser.parse_args()
    if args.observed_seconds is not None and (not math.isfinite(args.observed_seconds) or args.observed_seconds < 0):
        parser.error("observed-seconds must be finite and nonnegative")
    with args.log.open(encoding="utf-8-sig", errors="replace") as source:
        print(json.dumps(summarize(source, args.observed_seconds), ensure_ascii=False, indent=2))
