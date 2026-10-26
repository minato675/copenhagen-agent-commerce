"""Offline preparation of explicit experiment plans; never loads credentials."""

import argparse
import json
from pathlib import Path
import sys

from copenhagen_fds.evaluation.cases import load_dataset
from copenhagen_fds.evaluation.experiments import HumanReview, make_plan
from copenhagen_fds.evaluation.models import EvaluationDataset, ObservationBatch
from copenhagen_fds.evaluation.offline import OfflineViolation, deny_network


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate input JSON field")
        result[key] = value
    return result


def read_model(path, model):
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"), object_pairs_hook=_unique)
    return model.model_validate_json(json.dumps(data, allow_nan=False))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Prepare an offline experiment plan; no API calls.")
    parser.add_argument("--experiment-id", required=True)
    parser.add_argument("--dataset-file", type=Path)
    parser.add_argument("--dataset-role", choices=("REGRESSION", "STABILITY", "HOLDOUT"), default="REGRESSION")
    parser.add_argument("--mode", choices=("PIPELINE", "COMPARISON_REPEAT"), default="PIPELINE")
    parser.add_argument("--case-id", action="append")
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--frozen-from", type=Path)
    parser.add_argument("--human-review", type=Path)
    parser.add_argument("--max-calls", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        with deny_network():
            dataset = read_model(args.dataset_file, EvaluationDataset) if args.dataset_file else load_dataset()
            saved = read_model(args.frozen_from, ObservationBatch) if args.frozen_from else None
            review = read_model(args.human_review, HumanReview) if args.human_review else None
            plan = make_plan(dataset, experiment_id=args.experiment_id, mode=args.mode, case_ids=args.case_id,
                             repetitions=args.repetitions, max_http_requests=args.max_calls, frozen_batch=saved,
                             dataset_role=args.dataset_role, human_review=review)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as stream:
                stream.write(plan.model_dump_json(indent=2) + "\n")
            print(json.dumps({"plan_sha256": plan.sha256, "case_count": len(plan.case_ids),
                              "planned_attempts": len(plan.run_ids), "max_http_requests": plan.max_http_requests,
                              "human_reviewed": plan.human_review is not None, "api_calls": 0}))
        return 0
    except (Exception, OfflineViolation):
        print("Plan preparation failed: invalid input or output path.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
