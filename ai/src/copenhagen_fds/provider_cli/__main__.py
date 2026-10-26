"""Reproduce BE provider contracts offline; no server, environment or live GMS."""

import argparse
import asyncio
import json
from pathlib import Path
import sys

from copenhagen_fds.evaluation.offline import OfflineViolation
from copenhagen_fds.provider_cli.artifacts import read_bundle
from copenhagen_fds.provider_cli.preparation import prepare
from copenhagen_fds.provider_cli.single import execute_file
from copenhagen_fds.provider_cli.workflow import run_suite, verify_suite


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    demo = commands.add_parser("prepare-demo", help="Create synthetic requests, schemas and separate expectations")
    demo.add_argument("--settings", type=Path, default=Path("examples/personal-replay-settings.json"))
    demo.add_argument("--output", type=Path, required=True)
    execute = commands.add_parser("execute", help="Execute one provider request without reading expectations")
    execute.add_argument("request", type=Path)
    execute.add_argument("--call", type=Path, required=True)
    execute.add_argument("--server", type=Path, required=True)
    execute.add_argument("--mock-script", type=Path)
    execute.add_argument("--mode", choices=("mock",), default="mock")
    execute.add_argument("--output", type=Path, required=True)
    suite = commands.add_parser("suite", help="Execute all provider requests, then compare declared outcomes")
    suite.add_argument("source", type=Path)
    suite.add_argument("--output", type=Path, required=True)
    for name in ("verify-suite", "show"):
        commands.add_parser(name).add_argument("source", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare-demo":
            result = prepare(args.settings, args.output)
        elif args.command == "execute":
            result = asyncio.run(execute_file(args.request, args.call, args.server, args.output,
                                             script_path=args.mock_script))
        elif args.command == "suite":
            result = asyncio.run(run_suite(args.source, args.output))
        elif args.command == "verify-suite":
            result = asyncio.run(verify_suite(args.source))
        else:
            _, values = read_bundle(args.source, kind="suite-run")
            report = json.loads(values["comparisons.json"])
            print("case | actual status | risk | amount stage | mock calls | handlers | expectation matched")
            for row in report["rows"]:
                actual = row["observed"]
                print(" | ".join(str(v) for v in (row["case_id"], actual["status"], actual["risk"],
                    actual["amount_stage"], actual["calls"], actual["handlers"], row["passed"])))
            return 0 if report["status"] == "PASSED" else 3
    except (ValueError, TypeError, KeyError, OSError, RecursionError, OfflineViolation):
        print("Provider demo failed: check input contracts, offline options and a new separate output directory.", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    successful = {"PREPARED", "PASSED", "REPRODUCED", "SUCCESS", "CREATED", "UPDATED", "UNCHANGED"}
    return 0 if result["status"] in successful and result.get("scenario_status", "PASSED") == "PASSED" else 3


if __name__ == "__main__":
    raise SystemExit(main())
