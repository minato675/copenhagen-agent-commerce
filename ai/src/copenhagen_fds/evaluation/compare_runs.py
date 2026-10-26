"""Compare saved baseline and fresh regression observations with one rubric."""

import argparse
import json
from pathlib import Path
import sys

from copenhagen_fds.evaluation.experiment_cli import read_model
from copenhagen_fds.evaluation.experiment_scoring import score_experiment
from copenhagen_fds.evaluation.experiments import ExperimentBatch, ExperimentPlan, digest
from copenhagen_fds.evaluation.grading import evaluate
from copenhagen_fds.evaluation.models import ObservationBatch
from copenhagen_fds.evaluation.offline import OfflineViolation, deny_network
from copenhagen_fds.evaluation.rubrics import GRADER_VERSION, LEGACY_GRADER_VERSION, RUBRIC_SHA256


def compare_runs(before, after_plan, after):
    if after_plan.dataset_role != "REGRESSION" or after_plan.mode != "PIPELINE":
        raise ValueError("baseline comparison requires the same regression pipeline")
    if before.source != after.source:
        raise ValueError("cannot compare scripted and provider observations")
    if set(o.case_id for o in before.observations) != set(after_plan.case_ids):
        raise ValueError("baseline and after case selections differ")
    old_rubric = evaluate(after_plan.dataset, before, grader_version=LEGACY_GRADER_VERSION)
    baseline = evaluate(after_plan.dataset, before, grader_version=GRADER_VERSION)
    fresh = score_experiment(after_plan, after)
    if not fresh["complete"]:
        raise ValueError("cannot compare incomplete observations")
    key = "imported_response_metrics" if before.source == "GMS_IMPORTED" else "mock_regression_metrics"
    old, original, current = old_rubric[key], baseline[key], fresh["metrics"]
    before_rows = {r["case_id"]: r for r in baseline["cases"]}
    transitions = []
    for row in fresh["rows"]:
        previous = before_rows[row["case_id"]]
        if previous["checks"] != row["checks"] or previous["actual_outcome"] != row["actual_outcome"] or previous["field_checks"] != row["field_checks"]:
            transitions.append({"case_id": row["case_id"], "expected_label": row["expected_comparison"],
                                "before": previous["actual_outcome"], "after": row["actual_outcome"],
                                "before_checks": previous["checks"], "after_checks": row["checks"],
                                "before_field_checks": previous["field_checks"], "after_field_checks": row["field_checks"]})
    return {
        "report_version": "fds-regression-comparison-v1", "source": before.source,
        "dataset_version": after_plan.dataset.version, "dataset_sha256": after_plan.dataset.sha256,
        "grader_version": GRADER_VERSION, "rubric_sha256": RUBRIC_SHA256,
        "before_observations_sha256": digest(before.model_dump(mode="json")),
        "after_observations_sha256": digest(after.model_dump(mode="json")), "after_plan_sha256": after_plan.sha256,
        "compared_case_ids": list(after_plan.case_ids), "case_count": len(after_plan.case_ids),
        "same_rubric_metrics": {"before": original, "after": current},
        "rubric_change_on_unchanged_baseline": {
            "from": LEGACY_GRADER_VERSION, "to": GRADER_VERSION,
            "extraction_fields_before": old["extraction_field_checks"], "extraction_fields_after": original["extraction_field_checks"],
            "case_checks_before": old["case_checks"], "case_checks_after": original["case_checks"],
            "interpretation": "GRADER_CHANGE_NOT_MODEL_IMPROVEMENT",
        },
        "versions": {"before": baseline["versions"], "after": fresh["versions"]},
        "provider_metrics": {"before": baseline["provider_metrics"], "after": fresh["provider_metrics"]},
        "after_timing": fresh["timing"],
        "baseline_wall_timing": None,  # Legacy envelope did not measure pipeline wall time.
        "timing_limit": "Stage telemetry and pipeline wall time have different scopes; no inferred baseline wall time",
        "after_quality_targets": fresh["quality_targets"], "changed_cases": transitions,
        "interpretation": "KNOWN_CORPUS_COMPARISON_NOT_INDEPENDENT_GENERALIZATION" if before.source == "GMS_IMPORTED"
                          else "SCRIPTED_CONTRACT_COMPARISON_NOT_MODEL_QUALITY",
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description="Offline comparison on the same frozen regression corpus/rubric")
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after-plan", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        with deny_network():
            report = compare_runs(read_model(args.before, ObservationBatch), read_model(args.after_plan, ExperimentPlan),
                                  read_model(args.after, ExperimentBatch))
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as stream:
                stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        return 0
    except (Exception, OfflineViolation):
        print("Comparison failed: check matching observations and use a new output file.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
