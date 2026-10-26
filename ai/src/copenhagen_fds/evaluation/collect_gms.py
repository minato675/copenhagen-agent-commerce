"""Explicit, separately authorized LIVE command. The scoring CLI stays offline."""

import argparse
import asyncio
import json
import os
from pathlib import Path
import sys

from copenhagen_fds.analysis import OpenAIAnalysisAdapter
from copenhagen_fds.evaluation.collection import collect, write_json
from copenhagen_fds.evaluation.experiment_cli import read_model
from copenhagen_fds.evaluation.experiment_scoring import score_experiment
from copenhagen_fds.evaluation.experiments import ExperimentPlan
from copenhagen_fds.openai_settings import load_openai_settings


async def run(plan, settings, output):
    async with OpenAIAnalysisAdapter(settings) as adapter:
        batch = await collect(plan, adapter, output, progress=lambda item: print(json.dumps(item), flush=True))
    report = score_experiment(plan, batch)
    write_json(output / "report.json", report)
    print(json.dumps({"status": batch.status, "http_requests": len(batch.requests), "report_written": True}), flush=True)
    return 0 if report["complete"] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description="LIVE GMS collection. Costs require explicit approval of the frozen plan.")
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--approve-plan-sha256", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args(argv)
    try:
        plan = read_model(args.plan, ExperimentPlan)
        if args.approve_plan_sha256 != plan.sha256:
            raise ValueError("approval does not cover this plan")
        if plan.dataset_role == "HOLDOUT" and plan.human_review is None:
            raise ValueError("holdout labels have not been reviewed")
        if args.output_dir.exists():
            raise ValueError("refuse to overwrite a collection")
        settings = load_openai_settings({
            **os.environ, "FDS_MODE": "LIVE", "FDS_OPENAI_MODEL": plan.requested_model,
            "FDS_OPENAI_TIMEOUT_SECONDS": str(plan.provider_timeout_seconds),
            "FDS_EVALUATION_TIMEOUT_SECONDS": str(plan.evaluation_timeout_seconds),
        }, env_file=args.env_file)
        return asyncio.run(run(plan, settings, args.output_dir))
    except KeyboardInterrupt:
        print("Collection interrupted; inspect the persisted journal before any new run.", file=sys.stderr)
        return 2
    except Exception:
        print("Collection failed: check the plan, approval, settings, and saved observations.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
