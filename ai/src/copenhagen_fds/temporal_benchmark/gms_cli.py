"""Explicit bounded LIVE collection; preparation and saved-response scoring are offline."""

import argparse
import asyncio
import json
import os
from pathlib import Path
import sys

from .gms import collect_temporal_gms, prepare_gms_plan, read_gms_plans, summarize_temporal_round


async def _collect(plan, args):
    from copenhagen_fds.analysis import OpenAIAnalysisAdapter
    from copenhagen_fds.openai_settings import load_openai_settings
    if args.approve_plan_sha256 != plan.sha256:
        raise ValueError("explicit dispatch must match the frozen plan hash")
    if args.output.exists():
        raise ValueError("refuse to overwrite collected observations")
    settings = load_openai_settings({**os.environ, "FDS_MODE": "LIVE",
        "FDS_OPENAI_MODEL": plan.requested_model,
        "FDS_OPENAI_TIMEOUT_SECONDS": str(plan.provider_timeout_seconds),
        "FDS_EVALUATION_TIMEOUT_SECONDS": str(plan.evaluation_timeout_seconds)}, env_file=args.env_file)
    async with OpenAIAnalysisAdapter(settings) as adapter:
        _, report = await collect_temporal_gms(plan, adapter, args.output,
            progress=lambda row: print(json.dumps(row), file=sys.stderr, flush=True))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("source", type=Path)
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument("--limit", type=int, default=12)
    prepare.add_argument("--rounds", type=int, default=2)
    for name in ("collect", "rescore"):
        command = commands.add_parser(name)
        command.add_argument("plans", type=Path)
        command.add_argument("--round", type=int, required=True)
        if name == "collect":
            command.add_argument("--approve-plan-sha256", required=True)
            command.add_argument("--output", type=Path, required=True)
            command.add_argument("--env-file", type=Path)
        else:
            command.add_argument("--run", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            result = prepare_gms_plan(args.source, args.output, args.limit, args.rounds)
        else:
            plans = read_gms_plans(args.plans)
            if not 1 <= args.round <= len(plans):
                raise ValueError("round is outside the prepared plan")
            plan = plans[args.round - 1]
            if args.command == "collect":
                result = asyncio.run(_collect(plan, args))
            else:
                from copenhagen_fds.evaluation.experiments import ExperimentBatch
                batch = ExperimentBatch.model_validate_json((args.run / "observations.json").read_text(encoding="utf-8"))
                result = summarize_temporal_round(plan, batch)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
        return 0 if result.get("complete", True) else 1
    except KeyboardInterrupt:
        print("Collection interrupted; inspect the saved journal before any new call.", file=sys.stderr)
        return 2
    except Exception:
        print("GMS diagnostic failed: inspect plan integrity, settings and saved observations.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
