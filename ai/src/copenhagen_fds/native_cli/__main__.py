"""Run the native FDS engine. Default MOCK never loads a key or uses the network."""

import argparse
import asyncio
import json
from pathlib import Path
import sys

from copenhagen_fds.evaluation.offline import OfflineViolation
from copenhagen_fds.native_cli.storage import evaluate_file
from copenhagen_fds.native_cli.experiments import apply_independent_update, read_suite, run_suite, verify_run, verify_suite
from copenhagen_fds.native_cli.scenarios import prepare_demo
from copenhagen_fds.settings import SettingsError


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    evaluate = commands.add_parser("evaluate", help="Execute behavior, extraction, comparison and risk policy")
    evaluate.add_argument("input", type=Path)
    evaluate.add_argument("--output", required=True, type=Path)
    evaluate.add_argument("--mode", choices=("mock", "gms"), default="mock")
    evaluate.add_argument("--mock-script", type=Path)
    evaluate.add_argument("--env-file", type=Path)
    prepare = commands.add_parser("prepare-demo", help="Write synthetic requests/scripts and separate expectations; do not evaluate")
    prepare.add_argument("--settings", type=Path, default=Path("examples/personal-replay-settings.json"))
    prepare.add_argument("--output", type=Path, required=True)
    suite = commands.add_parser("suite", help="Run all prepared cases offline, then compare expectations")
    suite.add_argument("source", type=Path)
    suite.add_argument("--output", type=Path, required=True)
    update = commands.add_parser("update", help="Explicit independent profile update from later review evidence")
    update.add_argument("input", type=Path)
    update.add_argument("--output", type=Path, required=True)
    for name in ("verify", "verify-suite", "show"):
        commands.add_parser(name).add_argument("output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "evaluate":
            summary = asyncio.run(evaluate_file(args.input, args.output, mode=args.mode,
                script_path=args.mock_script, env_file=args.env_file))
        elif args.command == "prepare-demo": summary = prepare_demo(args.settings,args.output)
        elif args.command == "suite": summary = asyncio.run(run_suite(args.source,args.output))
        elif args.command == "verify": summary = asyncio.run(verify_run(args.output))
        elif args.command == "verify-suite": summary = asyncio.run(verify_suite(args.output))
        elif args.command == "update": summary = apply_independent_update(args.input,args.output)
        else:
            _,artifacts = read_suite(args.output)
            print("case | status | risk | score | amount stage | GPT | advice | expectation")
            for row in artifacts["comparisons.json"]["rows"]:
                current=row["summary"];observed=row["observed"]
                values=[row["case_id"],observed["status"],observed["risk"],current["behavior_score"],
                    observed["amount_stage"],observed["signal"],current["outcome"]["recommendation"],row["passed"]]
                print(" | ".join("null" if v is None else f"{v:.4f}" if isinstance(v,float) else str(v) for v in values))
            return 0
    except (ValueError, TypeError, KeyError, OSError, RecursionError, SettingsError, OfflineViolation):
        print("Native evaluation failed: check input, mode options, settings and a new isolated output directory.", file=sys.stderr)
        return 2
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True, indent=2))
    status=summary["outcome"]["status"] if "outcome" in summary else summary["status"]
    return 3 if status in {"FAILED","INCOMPLETE","CANCELLED","REJECTED","REPLAY_REQUIRED"} or summary.get("scenario_status")=="FAILED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
