"""Join evaluator labels only after immutable predictions have been written."""

from pathlib import Path

from copenhagen_fds.native_cli.storage import check_new_output, write_artifacts
from copenhagen_fds.synthetic.storage import digest
from .execution import read_predictions
from .preparation import read_inputs, read_targets
from .report import summarize_predictions


def score_run(source: Path, run: Path, output: Path) -> dict:
    check_new_output(output, source, run)
    _, rows = read_predictions(source, run)
    plan, _, _, _ = read_inputs(source)
    targets = read_targets(source)
    report = summarize_predictions(rows, targets, plan)
    output.mkdir(parents=True, exist_ok=False)
    write_artifacts(output, {"report.json": report}, dict(
        schema_version="temporal-benchmark-score-v1",
        source_manifest_sha256=digest(source / "manifest.json"),
        run_manifest_sha256=digest(run / "manifest.json"),
        predictions_sha256=digest(run / "predictions.jsonl"),
        evaluator_sha256=digest(source / "evaluator-labels.json"),
        evaluation_only=True, threshold_tuned=False))
    return report
