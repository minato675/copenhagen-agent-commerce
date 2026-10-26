"""Prepare and inspect frozen temporal evaluation inputs; no implicit network."""

import argparse
import asyncio
import json
from pathlib import Path
import sys

from .preparation import prepare, verify_inputs
from .protocol import BenchmarkPlan, SimulationConfig


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("prepare")
    build.add_argument("--settings", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--plan", type=Path)
    build.add_argument("--suite", choices=("stream", "challenge"))
    for field in ("seed", "users", "days", "train-days", "validation-days", "limit"):
        build.add_argument("--" + field, type=int)
    build.add_argument("--variant", choices=("BASE", "DOUBLE_AMOUNTS"))
    build.add_argument("--role", choices=("REGRESSION", "CANDIDATE_HOLDOUT"))
    build.add_argument("--split", choices=("validation", "test"))
    verify = commands.add_parser("verify-inputs")
    verify.add_argument("source", type=Path)
    for name in ("run", "verify-run"):
        run = commands.add_parser(name)
        run.add_argument("source", type=Path)
        run.add_argument("--output", type=Path, required=True)
    score = commands.add_parser("score")
    score.add_argument("source", type=Path)
    score.add_argument("--run", type=Path, required=True)
    score.add_argument("--output", type=Path, required=True)
    regression = commands.add_parser("regressions")
    regression.add_argument("--settings", type=Path, required=True)
    regression.add_argument("--output", type=Path, required=True)
    check = commands.add_parser("verify-regressions")
    check.add_argument("output", type=Path)
    report = commands.add_parser("report")
    report.add_argument("source", type=Path)
    report.add_argument("--output", type=Path, required=True)
    report.add_argument("--run", type=Path, action="append", default=[])
    report.add_argument("--score", type=Path, action="append", default=[])
    report.add_argument("--regressions", type=Path)
    report.add_argument("--gms-plans", type=Path)
    report.add_argument("--gms-run", type=Path, action="append", default=[])
    report.add_argument("--combination", type=Path)
    combine = commands.add_parser("combine-gms")
    combine.add_argument("source", type=Path)
    combine.add_argument("--run", type=Path, required=True)
    combine.add_argument("--gms-plans", type=Path, required=True)
    combine.add_argument("--gms-run", type=Path, action="append", default=[])
    combine.add_argument("--output", type=Path, required=True)
    check_combination = commands.add_parser("verify-combination")
    check_combination.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            if args.plan is not None:
                if any(getattr(args, field) is not None for field in (
                    "suite", "seed", "users", "days", "train_days", "validation_days",
                    "limit", "variant", "role", "split")):
                    raise ValueError("a frozen plan cannot be combined with plan overrides")
                plan = BenchmarkPlan.model_validate_json(args.plan.read_text(encoding="utf-8"))
                if plan.settings.model_dump(mode="json") != json.loads(args.settings.read_text(encoding="utf-8")):
                    # Compare validated settings rather than optional/default field spelling.
                    from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
                    supplied = PersonalReplaySettings.model_validate_json(args.settings.read_text(encoding="utf-8"))
                    if plan.settings != supplied:
                        raise ValueError("explicit settings differ from frozen plan")
            else:
                simulation = {name: getattr(args, name) for name in (
                    "seed", "users", "days", "train_days", "validation_days") if getattr(args, name) is not None}
                options = {name: getattr(args, flag) for name, flag in (
                    ("suite", "suite"), ("split", "split"), ("purchase_limit", "limit"),
                    ("data_role", "role"), ("distribution_variant", "variant")) if getattr(args, flag) is not None}
                plan = BenchmarkPlan(simulation=SimulationConfig(**simulation), **options,
                    settings=json.loads(args.settings.read_text(encoding="utf-8")))
            from copenhagen_fds.native_cli.storage import check_new_output
            check_new_output(args.output, args.settings, *([args.plan] if args.plan else []))
            result = prepare(plan, args.output)
        elif args.command == "verify-inputs":
            result = verify_inputs(args.source)
        elif args.command in ("run", "verify-run"):
            from .execution import execute, verify_run
            operation = execute if args.command == "run" else verify_run
            result = asyncio.run(operation(args.source, args.output,
                progress=lambda count, total: print(f"Temporal replay {count}/{total}", file=sys.stderr)))
        elif args.command == "score":
            from .reporting import score_run
            result = score_run(args.source, args.run, args.output)
        elif args.command in ("regressions", "verify-regressions"):
            from .regressions import run_regressions, verify_regressions
            result = (asyncio.run(run_regressions(args.settings, args.output)) if args.command == "regressions"
                      else verify_regressions(args.output))
        elif args.command in ("combine-gms", "verify-combination"):
            from .gms_combination import combine_archived_gms, verify_combination
            if args.command == "verify-combination":
                result = verify_combination(args.output)
            else:
                from copenhagen_fds.evaluation.experiments import ExperimentBatch
                batches = tuple(ExperimentBatch.model_validate_json(
                    (path / "observations.json").read_text(encoding="utf-8")) for path in args.gms_run)
                result = combine_archived_gms(args.source, args.run, args.gms_plans, batches, args.output)
        else:
            from .final_report import write_final_report
            result = write_final_report(args.source, args.output, runs=tuple(args.run), scores=tuple(args.score),
                regressions=args.regressions, gms_plans=args.gms_plans, gms_runs=tuple(args.gms_run),
                combination=args.combination)
    except (OSError, UnicodeError, ValueError, KeyError, TypeError):
        print("Temporal benchmark failed: check the frozen settings, input integrity and a new output directory.", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 1 if result.get("status") == "FAILED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
