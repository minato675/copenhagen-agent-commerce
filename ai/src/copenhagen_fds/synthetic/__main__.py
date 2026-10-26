"""Run with python -m copenhagen_fds.synthetic; no keys or network required."""

import argparse
import json
import sys
from pathlib import Path

from copenhagen_fds.features import transform_features

from .generator import GeneratorConfig
from .learning import export_learning_samples
from .replay import ReplayIndex, instant
from .storage import canonical, create_benchmark, read_dataset, verify_dataset


def export_inputs(dataset: Path, output: Path, *, split: str, limit: int) -> dict:
    if limit <= 0:
        raise ValueError("limit must be positive")
    verify_dataset(dataset)
    data = read_dataset(dataset)
    index = ReplayIndex(data)
    boundaries = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))["boundaries"]
    starts = {"train": "start", "validation": "validation_start", "test": "test_start"}
    ends = {"train": "validation_start", "validation": "test_start", "test": "end_exclusive"}
    lower, upper = instant(boundaries[starts[split]]), instant(boundaries[ends[split]])
    # Target selection is evaluator-side. Labels stay in a separate sidecar.
    targets = [row for row in data["truth"] if row["split"] == split]
    targets.sort(key=lambda row: (row["benchmark_role"] != "challenge",
                                  index.purchases[row["purchase_request_id"]]["occurred_at"], row["purchase_request_id"]))
    targets = targets[:limit]
    if not targets:
        raise ValueError("no targets in the selected split")
    projections = []
    skipped = 0
    for target in targets:
        for supplied in index.evaluation_points(target["purchase_request_id"]):
            if not lower <= supplied.evaluation.evaluated_at < upper:
                skipped += 1
                continue
            projections.append((target, supplied, transform_features(supplied)))
    if not projections:
        raise ValueError("no evaluation points inside the selected time interval")
    output.mkdir(parents=True, exist_ok=False)
    with ((output / "feature_inputs.jsonl").open("w", encoding="utf-8", newline="\n") as inputs,
          (output / "raw_features.jsonl").open("w", encoding="utf-8", newline="\n") as features,
          (output / "targets.jsonl").open("w", encoding="utf-8", newline="\n") as labels):
        for target, supplied, vector in projections:
            inputs.write(supplied.model_dump_json() + "\n")
            features.write(vector.model_dump_json() + "\n")
            labels.write(canonical({**target, "evaluation_id": supplied.evaluation.target.evaluation_id,
                                    "evaluated_at": supplied.evaluation.evaluated_at.isoformat(),
                                    "purchase_split": target["split"], "evaluation_split": split}) + "\n")
    report = {"status": "EXPORTED", "selected_purchases": len(targets),
              "purchases": len({target["purchase_request_id"] for target, _, _ in projections}),
              "evaluation_points": len(projections), "outside_interval_points_skipped": skipped,
              "split": split, "model_scored": False,
              "note": "Raw Feature contract check only; targets are evaluator-only and scores are not generated."}
    (output / "report.json").write_text(canonical(report) + "\n", encoding="utf-8")
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Generate and inspect frozen synthetic FDS histories")
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("generate")
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--seed", type=int, default=20260910)
    build.add_argument("--users", type=int, default=120)
    build.add_argument("--days", type=int, default=120)
    build.add_argument("--train-days", type=int, default=60)
    build.add_argument("--validation-days", type=int, default=30)
    build.add_argument("--start", default="2026-01-01")
    verify = commands.add_parser("verify")
    verify.add_argument("dataset", type=Path)
    export = commands.add_parser("export")
    export.add_argument("dataset", type=Path)
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--split", choices=("train", "validation", "test"), default="test")
    export.add_argument("--limit", type=int, default=100, help="Maximum purchases; retries add evaluation points")
    learning = commands.add_parser("export-learning")
    learning.add_argument("dataset", type=Path)
    learning.add_argument("--output", type=Path, required=True)
    learning.add_argument("--as-of", required=True, help="Explicit learning cutoff with timezone")
    learning.add_argument("--limit", type=int, default=None, help="Optional chronological purchase limit; history remains complete")
    args = parser.parse_args(argv)
    try:
        if args.command == "generate":
            config = GeneratorConfig(seed=args.seed, users=args.users, days=args.days, start=args.start,
                                     train_days=args.train_days, validation_days=args.validation_days)
            result = create_benchmark(args.output, config)
        elif args.command == "verify":
            result = verify_dataset(args.dataset)
        elif args.command == "export-learning":
            result = export_learning_samples(args.dataset, args.output, as_of=args.as_of, limit=args.limit)
        else:
            result = export_inputs(args.dataset, args.output, split=args.split, limit=args.limit)
    except (OSError, ValueError, KeyError, TypeError):
        print("Synthetic dataset operation failed: check configuration, input integrity and use a new output directory.", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
