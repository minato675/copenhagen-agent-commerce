"""Before/after comparisons must retain the same input selection and rubric."""

import json

import pytest

from copenhagen_fds.evaluation.compare_runs import compare_runs, main
from copenhagen_fds.evaluation.models import Observation, ObservationBatch
from .test_experiments import plan_for, scripted_collect


def baseline(dataset, observed):
    return ObservationBatch(dataset_version=dataset.version, dataset_sha256=dataset.sha256, source="MOCK",
                            observations=tuple(Observation(case_id=o.case_id, run=o.run) for o in observed.observations))


def test_comparison_regrades_saved_baseline_without_inventing_wall_time(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)
    after, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    before = baseline(dataset, after)
    report = compare_runs(before, plan, after)
    assert report["case_count"] == 1 and report["changed_cases"] == []
    assert report["rubric_change_on_unchanged_baseline"]["interpretation"] == "GRADER_CHANGE_NOT_MODEL_IMPROVEMENT"
    assert report["baseline_wall_timing"] is None
    assert report["same_rubric_metrics"]["before"]["comparison_label_checks"]["rate"] == 1
    assert report["same_rubric_metrics"]["after"]["label_accuracy"]["rate"] == 1
    assert not report["after_quality_targets"]["eligible"]


@pytest.mark.parametrize("change", ["selection", "dataset", "source", "incomplete"])
def test_incomparable_runs_are_rejected(run_async, dataset, tmp_path, change):
    plan = plan_for(dataset, cap=2)
    after, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    before = baseline(dataset, after)
    if change == "selection":
        before = before.model_copy(update={"observations": (before.observations[0].model_copy(update={"case_id": "fds-02-maximum"}),)})
    elif change == "dataset":
        before = before.model_copy(update={"dataset_sha256": "0" * 64})
    elif change == "source":
        before = before.model_copy(update={"source": "GMS_IMPORTED"})
    else:
        after = after.model_copy(update={"status": "STOPPED", "observations": ()})
    with pytest.raises(ValueError):
        compare_runs(before, plan, after)


def test_comparison_cli_is_offline_and_never_overwrites_inputs(run_async, dataset, tmp_path):
    plan = plan_for(dataset, cap=2)
    after, _ = run_async(scripted_collect(plan, tmp_path / "run"))
    before = baseline(dataset, after)
    before_path = tmp_path / "before.json"
    before_path.write_text(before.model_dump_json(), encoding="utf-8")
    output = tmp_path / "report.json"
    args = ["--before", str(before_path), "--after-plan", str(tmp_path / "run" / "plan.json"),
            "--after", str(tmp_path / "run" / "observations.json"), "--output", str(output)]
    assert main(args) == 0
    assert json.loads(output.read_text(encoding="utf-8"))["source"] == "MOCK"
    original = before_path.read_bytes()
    assert main([*args[:-1], str(before_path)]) == 2
    assert before_path.read_bytes() == original
