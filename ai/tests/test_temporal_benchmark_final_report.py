"""Recorded artifact reporting without replay, credentials, or network (361)."""

from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.evaluation.experiments import ExperimentBatch, HttpAttempt
from copenhagen_fds.native_cli.storage import write_artifacts
from copenhagen_fds.synthetic.storage import canonical, digest
from copenhagen_fds.temporal_benchmark import final_report
from copenhagen_fds.temporal_benchmark.gms import prepare_gms_plan, read_gms_plans, summarize_temporal_round
from copenhagen_fds.temporal_benchmark.preparation import INPUT_NAMES, prepare, read_inputs
from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan
from copenhagen_fds.temporal_benchmark.regressions import SCHEMA as REGRESSION_SCHEMA, SUITE_EXPECTED_COUNTS
from copenhagen_fds.temporal_benchmark.reporting import score_run


PRIVATE = "private-original-user-text-never-in-final-report"


@pytest.fixture(scope="module")
def source(tmp_path_factory):
    settings = json.loads((Path(__file__).parents[1] / "examples/personal-replay-settings.json").read_text())
    plan = BenchmarkPlan.model_validate(dict(settings=settings,
        simulation=dict(users=24, days=18, train_days=8, validation_days=5), purchase_limit=80))
    path = tmp_path_factory.mktemp("final-source-parent") / "inputs"
    prepare(plan, path)
    return path


def _recorded_run(source, output, *, unavailable_behavior=False):
    """Authored result records test the reader, not the native model's accuracy."""
    plan, _, semantic, selected = read_inputs(source)
    rows = []
    for pid in selected:
        target = semantic[pid].analysis.context
        arms = {}
        for arm in ("cold", "personalized"):
            arms[arm] = dict(behavior_score=20.0, behavior_status="SUCCESS", mode="MOCK",
                behavior_rules=None,
                features=[dict(feature_id="amount_minor", stage="COLD", score=20.0)],
                analysis=dict(comparison=dict(status="SUCCESS", signal="MATCH")),
                outcome=dict(status="SUCCESS", risk_level="LOW", recommendation="NO_ADDITIONAL_REVIEW", review_actions=[]))
            if unavailable_behavior:
                # A missing archived policy cannot attest an invented score.
                # Combination still records the missing-evidence NOT_RUN path.
                arms[arm].update(behavior_score=None, behavior_status="INCOMPLETE",
                    features=[dict(feature_id="amount_minor", stage="UNAVAILABLE", score=None)],
                    outcome=dict(status="INCOMPLETE", risk_level="UNKNOWN", recommendation=None,
                                 review_actions=["RETRY_EVALUATION"]))
        rows.append(dict(purchase_request_id=pid, user_id=target.user_id, evaluation_id=target.evaluation_id,
            semantic_observation_sha256=semantic[pid].observation_sha256,
            evaluated_at=semantic[pid].available_at.isoformat(), arms=arms,
            profile_audit=dict(personal=dict(included_count=0)), user_text=PRIVATE))
    output.mkdir()
    (output / "predictions.jsonl").write_text("".join(canonical(row) + "\n" for row in rows), encoding="utf-8")
    summary = dict(status="EXECUTED", purchases=len(rows), native_evaluations=2 * len(rows), mode="MOCK",
        semantic_quality="NOT_MEASURED_CONSTANT_PROVIDER", external_api="NOT_RUN", labels_read=False,
        service_integrated=False, profile_persistence="IN_MEMORY_ONLY", wall_duration_seconds=2.5,
        private_debug=PRIVATE)
    write_artifacts(output, {"summary.json": summary, "plan.json": plan.model_dump(mode="json")},
        dict(schema_version="temporal-benchmark-run-v1", code=dict(source_sha256="1" * 64),
             input_sha256={name: digest(source / name) for name in INPUT_NAMES},
             predictions_sha256=digest(output / "predictions.jsonl"), selected_purchase_ids=list(selected),
             labels_read=False, external_api="NOT_RUN"))
    return output


def _recorded_regressions(output):
    output.mkdir()
    (output / "evidence.json").write_text(canonical(dict(private=PRIVATE)) + "\n", encoding="utf-8")
    suites = {name: dict(status="PASSED", n_total=count, n_passed=count, n_failed=0, n_not_run=0)
              for name, count in SUITE_EXPECTED_COUNTS.items()}
    summary = dict(status="PASSED", suites=suites, execution_error=None, external_api="NOT_RUN", mode="MOCK",
                   source_settings_sha256="3" * 64, code_sha256="1" * 64, private=PRIVATE,
                   **{key: sum(row[key] for row in suites.values()) for key in final_report.COUNTS})
    write_artifacts(output, {"summary.json": summary}, dict(schema_version=REGRESSION_SCHEMA,
        code=dict(source_sha256="1" * 64), source_settings_sha256="3" * 64, external_api="NOT_RUN",
        child_artifact_sha256={"evidence.json": digest(output / "evidence.json")},
        summary_sha256=personalization_hash(summary)))
    return output


def _recorded_gms(plan, output, source="GMS_IMPORTED"):
    now = datetime(2026, 9, 16, tzinfo=timezone.utc)
    case_id = plan.case_ids[0]
    batch = ExperimentBatch(plan_sha256=plan.sha256, source=source, status="STOPPED", stop_reason="PROVIDER_ACCESS",
        started_at=now, completed_at=now, requests=(HttpAttempt(sequence=1, case_id=case_id, repetition=1,
            stage="extraction", input_sha256=plan.extraction_input_hashes[case_id], http_status=401),))
    report = summarize_temporal_round(plan, batch)
    output.mkdir()
    for name, value in (("plan.json", plan.model_dump(mode="json")), ("observations.json", batch.model_dump(mode="json")), ("report.json", report)):
        (output / name).write_text(canonical(value) + "\n", encoding="utf-8")
    return output


def test_missing_experiments_are_not_run_and_output_inventory_is_frozen(source, tmp_path):
    output = tmp_path / "final"
    result = final_report.write_final_report(source, output)
    assert result["offline_behavior"]["status"] == result["contract_regressions"]["status"] == result["gms"]["status"] == "NOT_RUN"
    assert result["reproducibility"]["prediction_replay"] == "NOT_RUN"
    assert set(output.iterdir()) == {output / name for name in ("summary.json", "report.md", "manifest.json")}
    manifest = json.loads((output / "manifest.json").read_text())
    assert all(digest(output / name) == sha for name, sha in manifest["artifact_sha256"].items())
    assert manifest["external_api"] == "NOT_RUN" and not manifest["predictions_replayed"]


def test_offline_results_and_score_links_preserve_limits_without_private_text(source, tmp_path, monkeypatch):
    monkeypatch.setenv("GMS_KEY", PRIVATE)
    run = _recorded_run(source, tmp_path / "run")
    score = tmp_path / "score"
    score_run(source, run, score)
    output = tmp_path / "final"
    result = final_report.write_final_report(source, output, runs=(run,), scores=(score,))
    recorded = result["offline_behavior"]["runs"][0]
    assert recorded["detailed_report_status"] == "VERIFIED_RECORDED_REPORT"
    assert recorded["offline_wall_duration_seconds"] == 2.5 and recorded["gms_latency"] == "NOT_MEASURED"
    assert recorded["recorded_code_sha256"] == "1" * 64
    assert recorded["prediction_replay"] == "NOT_RUN" and recorded["current_code_comparison"] == "NOT_PERFORMED"
    assert result["system_integration"]["persistent_database"] == "NOT_RUN"
    for name in ("summary.json", "report.md", "manifest.json"):
        content = (output / name).read_text(encoding="utf-8")
        assert PRIVATE not in content and '"user_id"' not in content and '"user_text"' not in content
    assert "GMS 응답시간이 아닙니다" in (output / "report.md").read_text(encoding="utf-8")


def test_modified_predictions_are_rejected_before_final_output_exists(source, tmp_path):
    run = _recorded_run(source, tmp_path / "run")
    with (run / "predictions.jsonl").open("a") as stream:
        stream.write(" ")
    with pytest.raises(ValueError, match="journal changed"):
        final_report.write_final_report(source, tmp_path / "final", runs=(run,))
    assert not (tmp_path / "final").exists()


def test_changed_source_is_integrity_error_not_a_replay_verdict(source, tmp_path):
    copied = tmp_path / "source"
    shutil.copytree(source, copied)
    with (copied / "observations.json").open("a") as stream:
        stream.write(" ")
    with pytest.raises(ValueError, match="artifact changed"):
        final_report.write_final_report(copied, tmp_path / "final")
    assert not (tmp_path / "final").exists()


def test_corrupt_recorded_score_cannot_be_presented(source, tmp_path):
    run = _recorded_run(source, tmp_path / "run")
    score = tmp_path / "score"
    score_run(source, run, score)
    (score / "report.json").write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="artifact changed"):
        final_report.write_final_report(source, tmp_path / "final", runs=(run,), scores=(score,))


def test_regression_artifacts_are_verified_without_claiming_reexecution(source, tmp_path):
    path = _recorded_regressions(tmp_path / "regression")
    result = final_report.write_final_report(source, tmp_path / "final", regressions=path)
    regression = result["contract_regressions"]
    assert regression["n_total"] == regression["n_passed"] == 92
    assert regression["prediction_replay"] == "NOT_RUN" and not regression["current_code_matches"]
    assert PRIVATE not in json.dumps(result)
    (path / "evidence.json").write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="hash changed"):
        final_report.write_final_report(source, tmp_path / "corrupt", regressions=path)


def test_partial_gms_keeps_missing_round_and_auth_failure_provenance(source, tmp_path):
    plans_path = tmp_path / "plans"
    prepare_gms_plan(source, plans_path, limit=2)
    plans = read_gms_plans(plans_path)
    run = _recorded_gms(plans[0], tmp_path / "gms-round")
    result = final_report.write_final_report(source, tmp_path / "final", gms_plans=plans_path, gms_runs=(run,))
    gms = result["gms"]
    assert gms["status"] == "PARTIAL" and gms["actual_requests"] == 1 and gms["missing_rounds"] == 1
    assert gms["latency"] == "NOT_MEASURED" and gms["human_review"] == "NOT_PERFORMED"
    assert gms["rounds"][0]["stop_reason"] == "PROVIDER_ACCESS"
    assert gms["repeat_consistency"]["categories"]["MISSING"] == 2
    assert result["reproducibility"]["gms_recollection"] == "NOT_RUN"
    assert result["gms"]["rounds"][0]["metrics"]["case_checks"] == dict(passed=0, total=2, rate=0.0)
    markdown = (tmp_path / "final/report.md").read_text(encoding="utf-8")
    assert "| 1 | 실제 GMS | 0/2 (0.00%) |" in markdown
    assert "| 2 | 미실행 | 미실행 | 미실행 | 미실행 |" in markdown


def test_mock_gms_collection_never_counts_as_actual_calls(source, tmp_path):
    plans_path = tmp_path / "plans"
    prepare_gms_plan(source, plans_path, limit=2)
    plans = read_gms_plans(plans_path)
    run = _recorded_gms(plans[0], tmp_path / "mock-round", source="MOCK")
    result = final_report.write_final_report(source, tmp_path / "final", gms_plans=plans_path, gms_runs=(run,))
    assert result["gms"]["status"] == "MOCK_ONLY" and result["gms"]["actual_requests"] == 0
    assert result["gms"]["recorded_mock_requests"] == 1 and result["gms"]["latency"] == "NOT_MEASURED"


def test_gms_report_must_equal_recomputed_observations(source, tmp_path):
    plans_path = tmp_path / "plans"
    prepare_gms_plan(source, plans_path, limit=2)
    plans = read_gms_plans(plans_path)
    run = _recorded_gms(plans[0], tmp_path / "gms-round")
    report = json.loads((run / "report.json").read_text())
    report["complete"] = True
    (run / "report.json").write_text(canonical(report), encoding="utf-8")
    with pytest.raises(ValueError, match="differs from its recorded observations"):
        final_report.write_final_report(source, tmp_path / "final", gms_plans=plans_path, gms_runs=(run,))


def test_duplicates_missing_plan_and_output_overlap_fail_before_writing(source, tmp_path):
    run = _recorded_run(source, tmp_path / "run")
    with pytest.raises(ValueError, match="duplicate"):
        final_report.write_final_report(source, tmp_path / "final", runs=(run, run))
    with pytest.raises(ValueError, match="require the frozen plan"):
        final_report.write_final_report(source, tmp_path / "final", gms_runs=(run,))
    with pytest.raises(ValueError, match="overlap"):
        final_report.write_final_report(source, source / "final")


def test_repeat_consistency_keeps_changed_failed_unknown_and_missing_distinct(source, tmp_path):
    plans_path = tmp_path / "plans"
    prepare_gms_plan(source, plans_path, limit=5)
    plans = read_gms_plans(plans_path)
    first = ("UNKNOWN", "MATCH", "FAILED", "MATCH", "MISSING")
    second = ("UNKNOWN", "MISMATCH", "MATCH", "NOT_RUN", "MATCH")
    reports = {}
    for plan, outcomes in zip(plans, (first, second), strict=True):
        reports[plan.sha256] = dict(source="GMS_IMPORTED", rows=[dict(case_id=case_id, actual_outcome=outcome)
            for case_id, outcome in zip(plan.case_ids, outcomes, strict=True)])
    result = final_report._repeat_consistency(plans, reports)
    assert result["categories"] == dict(SAME_SEMANTIC_LABEL=1, CHANGED_SEMANTIC_LABEL=1,
        FAILED_OR_NOT_RUN=2, MISSING=1, NOT_LIVE=0)
    assert result["target_pass"] == "NOT_CLAIMED"


def test_archived_combination_is_linked_without_claiming_live_integration(source, tmp_path):
    from copenhagen_fds.temporal_benchmark.gms_combination import combine_archived_gms
    run = _recorded_run(source, tmp_path / "run", unavailable_behavior=True)
    plans_path = tmp_path / "plans"
    prepare_gms_plan(source, plans_path, limit=2)
    combined = tmp_path / "combined"
    combine_archived_gms(source, run, plans_path, (), combined)
    result = final_report.write_final_report(source, tmp_path / "final", runs=(run,),
        gms_plans=plans_path, combination=combined)
    joined = result["archived_combination"]
    assert joined["interpretation"] == "ARCHIVED_COUNTERFACTUAL_POLICY_PROJECTION"
    assert not joined["live_end_to_end"] and not joined["behavior_recomputed"]
    assert joined["observed_trials"] == 0 and joined["missing_trials"] == 4
    assert joined["fresh_api_calls"] == 0
    assert result["system_integration"]["core_pay_http"] == "NOT_RUN"
    assert PRIVATE not in json.dumps(result)


def test_combination_requires_matching_behavior_and_plan_inputs(source, tmp_path):
    from copenhagen_fds.temporal_benchmark.gms_combination import combine_archived_gms
    run = _recorded_run(source, tmp_path / "run", unavailable_behavior=True)
    plans_path = tmp_path / "plans"
    prepare_gms_plan(source, plans_path, limit=2)
    combined = tmp_path / "combined"
    combine_archived_gms(source, run, plans_path, (), combined)
    with pytest.raises(ValueError, match="combination requires"):
        final_report.write_final_report(source, tmp_path / "final", combination=combined)


def test_gms_markdown_separates_label_field_and_whole_case_denominators(source, tmp_path):
    summary = final_report.write_final_report(source, tmp_path / "final")
    rounds = []
    for fields, cases in ((95, 11), (94, 10)):
        rounds.append(dict(status="COMPLETE", source="GMS_IMPORTED", planned_purchases=12,
            observed_purchases=12, http_requests=24, latency_is_gms_measurement=False,
            metrics=dict(label_accuracy=dict(passed=12, total=12, rate=1.0),
                extraction_field_checks=dict(passed=fields, total=96, rate=fields / 96),
                case_checks=dict(passed=cases, total=12, rate=cases / 12))))
    summary["gms"].update(status="COMPLETE", actual_requests=48, rounds=rounds)
    markdown = final_report._markdown(summary)
    assert "| 1 | 실제 GMS | 12/12 (100.00%) | 95/96 (98.96%) | 11/12 (91.67%) |" in markdown
    assert "| 2 | 실제 GMS | 12/12 (100.00%) | 94/96 (97.92%) | 10/12 (83.33%) |" in markdown
    assert "동일한 12개 구매 표본을 2개 라운드에서 반복" in markdown
    assert "독립 표본 수로 해석하지 않습니다" in markdown
    assert "인간 검토 holdout 또는 실거래 성능 평가가 아닙니다" in markdown
    assert "실패·누락 사례는 계획된 분모에서 제거하지 않습니다" in markdown


def test_markdown_keeps_mock_measurements_and_zero_denominators_explicit(source, tmp_path):
    summary = final_report.write_final_report(source, tmp_path / "final")
    empty = dict(passed=0, total=0, rate=None)
    summary["gms"].update(status="MOCK_ONLY", rounds=[dict(status="PARTIAL", source="MOCK",
        planned_purchases=0, observed_purchases=0, http_requests=0, latency_is_gms_measurement=False,
        metrics={name: empty for name in ("label_accuracy", "extraction_field_checks", "case_checks")})])
    markdown = final_report._markdown(summary)
    assert "| 1 | Mock | 0/0 (비율 미산출) | 0/0 (비율 미산출) | 0/0 (비율 미산출) |" in markdown
