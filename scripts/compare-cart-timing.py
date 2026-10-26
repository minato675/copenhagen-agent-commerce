"""Compare per-run timing reports; never mistake fixture time for host latency."""
import argparse
import json
import math
from pathlib import Path
from collections import defaultdict


def compare(runs):
    groups = defaultdict(list)
    for run in runs:
        if run['mode'] not in ('single', 'batch') or run['item_count'] not in (1, 4, 8):
            raise ValueError('mode must be single/batch and item_count must be 1/4/8')
        seconds = run['observed_seconds']
        if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or not math.isfinite(seconds) or seconds <= 0:
            raise ValueError('observed_seconds must be a measured positive duration')
        if not run.get('environment'):
            raise ValueError('environment must identify host/model/server/cache conditions')
        report = run['report']
        if report['incomplete_capture'] or not report['requests']:
            raise ValueError('incomplete timing capture cannot be compared')
        if any(row['errors'] for row in report['requests']):
            raise ValueError('separate failed runs from successful latency comparison')
        groups[(run['environment'], run['item_count'], run['mode'])].append(run)
    output = []
    for (environment, count, mode), samples in sorted(groups.items()):
        values = sorted(s['observed_seconds'] * 1000 for s in samples)
        calls = [sum(row['tool_calls'] for row in s['report']['requests']) for s in samples]
        core = [sum(row['core_http_calls'] for row in s['report']['requests']) for s in samples]
        output.append(dict(environment=environment, item_count=count, mode=mode, samples=len(samples),
                           total_p50_ms=values[math.ceil(len(values) * .50)-1],
                           total_p95_ms=values[math.ceil(len(values) * .95)-1],
                           tool_calls=calls, core_http_calls=core,
                           descriptive_only=len(samples) < 20))
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path, help='JSON array of run metadata and analyzer report file paths')
    args = parser.parse_args()
    runs = json.loads(args.manifest.read_text(encoding='utf-8-sig'))
    for run in runs:
        run['report'] = json.loads((args.manifest.parent / run['report_file']).read_text(encoding='utf-8-sig'))
    print(json.dumps(compare(runs), ensure_ascii=False, indent=2))
