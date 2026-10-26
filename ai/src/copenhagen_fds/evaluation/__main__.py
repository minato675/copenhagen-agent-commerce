"""Local mock regression or grading of previously collected GMS observations."""

import argparse
import asyncio
import json
from pathlib import Path
import sys

from copenhagen_fds.evaluation.cases import load_dataset
from copenhagen_fds.evaluation.grading import evaluate
from copenhagen_fds.evaluation.rubrics import GRADER_VERSION, GRADER_VERSIONS
from copenhagen_fds.evaluation.models import ObservationBatch
from copenhagen_fds.evaluation.offline import OfflineViolation, deny_network, run_mock_dataset
from copenhagen_fds.evaluation.experiments import ExperimentPlan, ExperimentBatch
from copenhagen_fds.evaluation.experiment_cli import read_model
from copenhagen_fds.evaluation.experiment_scoring import score_experiment


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate observation field")
        result[key] = value
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Offline FDS regression and imported-response grading. Never calls GMS.")
    parser.add_argument("mode", choices=("mock", "score-gms"))
    parser.add_argument("--observations", type=Path)
    parser.add_argument("--plan", type=Path, help="Frozen repeated/pipeline experiment plan for offline scoring")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--grader-version", choices=GRADER_VERSIONS, default=GRADER_VERSION)
    args = parser.parse_args(argv)
    if (args.mode == "score-gms") != (args.observations is not None):
        parser.error("score-gms requires --observations; mock does not accept it")
    if args.plan is not None and args.mode != "score-gms":
        parser.error("--plan requires score-gms")
    if args.plan is not None and args.grader_version != GRADER_VERSION:
        parser.error("experiment scoring uses its pinned reference grader")
    try:
        if args.output is not None and args.observations is not None and args.output.resolve() == args.observations.resolve():
            raise ValueError("report cannot overwrite observations")
        if args.output is not None and args.plan is not None and args.output.resolve() == args.plan.resolve():
            raise ValueError("report cannot overwrite plan")
        if args.plan is not None:
            with deny_network():
                plan = read_model(args.plan, ExperimentPlan)
                batch = read_model(args.observations, ExperimentBatch)
                if batch.source != "GMS_IMPORTED":
                    raise ValueError("score-gms requires imported observations")
                report = score_experiment(plan, batch)
                encoded = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
                if args.output is None:
                    print(encoded)
                else:
                    args.output.parent.mkdir(parents=True, exist_ok=True)
                    args.output.write_text(encoded + "\n", encoding="utf-8")
                    print(json.dumps({"source": report["source"], "observed_attempts": report["observed_attempts"], "report_written": True}))
                return 0 if report["complete"] and all(row["passed"] for row in report["rows"]) else 1
        dataset = load_dataset()
        with asyncio.Runner() as runner:
            runner.get_loop()
            with deny_network():
                if args.mode == "mock":
                    batch = runner.run(run_mock_dataset(dataset))
                else:
                    # Never print validation errors: their nested inputs can retain text.
                    raw = json.loads(args.observations.read_text(encoding="utf-8-sig"), object_pairs_hook=_unique)
                    batch = ObservationBatch.model_validate_json(json.dumps(raw, allow_nan=False))
                    if batch.source != "GMS_IMPORTED":
                        raise ValueError("score-gms requires imported GMS observations")
                report = evaluate(dataset, batch, grader_version=args.grader_version)
        encoded = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
        if args.output is None:
            print(encoded)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded + "\n", encoding="utf-8")
            print(json.dumps({"source": report["source"], "evaluated_case_count": report["evaluated_case_count"], "report_written": True}))
        return 0 if all(case["passed"] for case in report["cases"]) else 1
    except (Exception, OfflineViolation):
        print("Evaluation failed: invalid input, offline boundary, or report output error.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
