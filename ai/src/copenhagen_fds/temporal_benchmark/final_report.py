"""Verify recorded artifacts and publish a small, secret-free evaluation index.

This is an offline reader, not another experiment. Hash validation establishes
the supplied records' consistency; it does not replay predictions, authenticate
an imported GMS response, or prove that a synthetic benchmark generalizes.
"""

from collections import Counter
import json
import math
from pathlib import Path
import re

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.evaluation.experiments import ExperimentBatch, ExperimentPlan
from copenhagen_fds.native_cli.storage import check_new_output
from copenhagen_fds.synthetic.storage import canonical, digest
from .execution import read_predictions
from .gms import read_gms_plans, summarize_temporal_round
from .preparation import read_inputs, read_targets, verify_inputs
from .regressions import SUITE_EXPECTED_COUNTS, verify_regressions
from .report import summarize_predictions


SCHEMA = "temporal-benchmark-final-report-v1"
COUNTS = ("n_total", "n_passed", "n_failed", "n_not_run")
METRICS = ("n_total", "n_scored_labeled", "coverage", "precision", "recall", "false_positive_rate", "average_precision")


def _json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _hash(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("recorded digest must be a lowercase SHA-256 value")
    return value


def _child(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError("artifact name must be a relative path")
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or path == root.resolve():
        raise ValueError("artifact path must stay inside its recorded directory")
    return path


def _verify_hashes(root, entries):
    if not isinstance(entries, dict):
        raise ValueError("artifact inventory must be an object")
    for name, expected in entries.items():
        if digest(_child(root, name)) != _hash(expected):
            raise ValueError("recorded artifact changed")


def _reference(path, names):
    return dict(directory=str(path.resolve()), sha256={name: digest(_child(path, name)) for name in names})


def _score_index(source, runs, scores):
    run_hashes = {digest(run / "manifest.json") for run in runs}
    found = {}
    for path in scores:
        manifest = _json(path / "manifest.json")
        if (manifest["schema_version"] != "temporal-benchmark-score-v1"
                or set(manifest["artifact_sha256"]) != {"report.json"}
                or manifest["evaluation_only"] is not True or manifest["threshold_tuned"] is not False):
            raise ValueError("unsupported recorded score report")
        _verify_hashes(path, manifest["artifact_sha256"])
        run_hash = manifest["run_manifest_sha256"]
        if run_hash not in run_hashes or run_hash in found:
            raise ValueError("score must refer to exactly one supplied run")
        if (manifest["source_manifest_sha256"] != digest(source / "manifest.json")
                or manifest["evaluator_sha256"] != digest(source / "evaluator-labels.json")):
            raise ValueError("score source or evaluator artifact differs from this report")
        found[run_hash] = path, manifest
    return found


def _offline_runs(source, runs, scores, plan, targets):
    if not runs:
        if scores:
            raise ValueError("score reports require their source runs")
        return dict(status="NOT_RUN", runs=[], language_model_quality="NOT_MEASURED")
    indexed = _score_index(source, runs, scores)
    reports = []
    for path in runs:
        manifest, rows = read_predictions(source, path)
        summary = _json(path / "summary.json")
        if (summary["mode"] != "MOCK" or summary["external_api"] != "NOT_RUN"
                or summary["labels_read"] is not False or summary["service_integrated"] is not False
                or summary["semantic_quality"] != "NOT_MEASURED_CONSTANT_PROVIDER"
                or summary["purchases"] != len(rows) or summary["native_evaluations"] != 2 * len(rows)):
            raise ValueError("offline run provenance does not match temporal Mock evaluation")
        wall = summary["wall_duration_seconds"]
        if type(wall) not in (int, float) or not math.isfinite(wall) or wall < 0:
            raise ValueError("offline execution duration must be finite and nonnegative")
        report = summarize_predictions(rows, targets, plan)
        score_record = indexed.get(digest(path / "manifest.json"))
        detailed_reference = None
        if score_record is not None:
            score_path, score_manifest = score_record
            if (score_manifest["predictions_sha256"] != manifest["predictions_sha256"]
                    or _json(score_path / "report.json") != report):
                raise ValueError("recorded score differs from the verified prediction/evaluator join")
            detailed_reference = _reference(score_path, ("manifest.json", "report.json"))
        reports.append(dict(
            status="RECORDED", purchases=len(rows), run_reference=_reference(path, ("manifest.json", "predictions.jsonl")),
            detailed_report_reference=detailed_reference,
            detailed_report_status="VERIFIED_RECORDED_REPORT" if score_record else "RECOMPUTED_FROM_VERIFIED_RECORDS",
            recorded_code_sha256=_hash(manifest["code"]["source_sha256"]),
            behavior={arm: {key: report["behavior"][arm][key] for key in METRICS} for arm in ("cold", "personalized")},
            paired_scored=dict(n_pairs=report["behavior"]["paired_scored"]["n_pairs"],
                               deltas=report["behavior"]["paired_scored"]["deltas"]),
            stage_counts={key: value["n_purchases"] for key, value in report["strata"]["amount_stage"].items()},
            offline_wall_duration_seconds=wall, offline_timing_scope="WHOLE_LOCAL_REPLAY_AND_PROFILE_COMPUTATION",
            language_model_quality="NOT_MEASURED_CONSTANT_MOCK", gms_latency="NOT_MEASURED",
            artifact_integrity="VERIFIED", prediction_replay="NOT_RUN", current_code_comparison="NOT_PERFORMED",
        ))
    return dict(status="RECORDED", runs=reports, pooling="NOT_PERFORMED", language_model_quality="NOT_MEASURED")


def _regression_counts(row):
    values = {key: row[key] for key in COUNTS}
    if any(type(value) is not int or value < 0 for value in values.values()):
        raise ValueError("regression counters must be nonnegative integers")
    if values["n_total"] != values["n_passed"] + values["n_failed"] + values["n_not_run"]:
        raise ValueError("regression counters must preserve missing checks")
    if row["status"] not in {"PASSED", "FAILED", "NOT_RUN"}:
        raise ValueError("unsupported regression execution status")
    if row["status"] == "PASSED" and (values["n_failed"] or values["n_not_run"]):
        raise ValueError("incomplete regression checks cannot claim a pass")
    return dict(status=row["status"], **values)


def _regressions(path):
    if path is None:
        return dict(status="NOT_RUN", language_model_quality="NOT_MEASURED", actual_http_service="NOT_RUN")
    verified = verify_regressions(path)
    manifest = _json(path / "manifest.json")
    summary = _json(path / "summary.json")
    if (personalization_hash(summary) != manifest["summary_sha256"] or summary["mode"] != "MOCK"
            or summary["external_api"] != "NOT_RUN" or set(summary["suites"]) != set(SUITE_EXPECTED_COUNTS)):
        raise ValueError("regression summary differs from its declared provenance")
    total = _regression_counts(summary)
    suites = {key: _regression_counts(summary["suites"][key]) for key in SUITE_EXPECTED_COUNTS}
    if any(suites[key]["n_total"] != expected for key, expected in SUITE_EXPECTED_COUNTS.items()):
        raise ValueError("regression suite size differs from the declared checks")
    if any(sum(row[key] for row in suites.values()) != total[key] for key in COUNTS):
        raise ValueError("regression total differs from its child suites")
    return dict(**total, suites=suites, reference=_reference(path, ("manifest.json", "summary.json")),
        recorded_code_sha256=_hash(manifest["code"]["source_sha256"]), artifact_integrity="VERIFIED",
        current_code_matches=verified["current_code_matches"],
        prediction_replay="NOT_RUN", language_model_quality="NOT_MEASURED_SCRIPTED_COMPLETIONS",
        injection_scope="PROMPT_AND_RESPONSE_CONTRACTS_ONLY", actual_http_service="NOT_RUN")


def _repeat_consistency(plans, reports):
    if len(plans) < 2:
        return dict(status="NOT_RUN", reason="AT_LEAST_TWO_PLANNED_ROUNDS_REQUIRED", per_case=[])
    categories = ("SAME_SEMANTIC_LABEL", "CHANGED_SEMANTIC_LABEL", "FAILED_OR_NOT_RUN", "MISSING", "NOT_LIVE")
    counts, rows = Counter(dict.fromkeys(categories, 0)), []
    for case_id in plans[0].case_ids:
        outcomes = []
        for plan in plans:
            report = reports.get(plan.sha256)
            if report is None:
                outcomes.append("MISSING")
            elif report["source"] != "GMS_IMPORTED":
                outcomes.append("NOT_LIVE")
            else:
                outcomes.append(next(row["actual_outcome"] for row in report["rows"] if row["case_id"] == case_id))
        if "MISSING" in outcomes:
            category = "MISSING"
        elif "NOT_LIVE" in outcomes:
            category = "NOT_LIVE"
        elif any(value in {"FAILED", "NOT_RUN"} for value in outcomes):
            category = "FAILED_OR_NOT_RUN"
        else:
            category = "SAME_SEMANTIC_LABEL" if len(set(outcomes)) == 1 else "CHANGED_SEMANTIC_LABEL"
        counts[category] += 1
        rows.append(dict(case_id=case_id, round_outcomes=outcomes, category=category))
    models_comparable = len(reports) == len(plans) and all(
        report["source"] == "GMS_IMPORTED" and bool(report.get("response_models")) for report in reports.values())
    return dict(status="RECORDED" if reports else "NOT_RUN", planned_purchases=len(rows),
                categories=dict(counts), per_case=rows,
                response_models_match=(len({canonical(report["response_models"])
                    for report in reports.values()}) == 1) if models_comparable else None,
                scope="FULL_PIPELINE_VARIATION; UNKNOWN_IS_A_SEMANTIC_LABEL; FAILURES_AND_MISSING_ARE_NOT_AGREEMENT",
                target_pass="NOT_CLAIMED")


def _gms(source, plan_directory, run_directories):
    if plan_directory is None:
        if run_directories:
            raise ValueError("GMS observations require the frozen plan bundle")
        return dict(status="NOT_RUN", rounds=[], planned_rounds=None, actual_requests=0,
                    human_review="NOT_PERFORMED", latency="NOT_MEASURED")
    plans = read_gms_plans(plan_directory)
    manifest = _json(plan_directory / "manifest.json")
    if manifest["source_manifest_sha256"] != digest(source / "manifest.json"):
        raise ValueError("GMS plans were prepared from another source bundle")
    planned = {plan.sha256: plan for plan in plans}
    found, reports = {}, {}
    for path in run_directories:
        recorded = ExperimentPlan.model_validate_json((path / "plan.json").read_text(encoding="utf-8"))
        if recorded.sha256 not in planned or recorded.sha256 in found:
            raise ValueError("GMS run must match a distinct frozen pipeline round")
        batch = ExperimentBatch.model_validate_json((path / "observations.json").read_text(encoding="utf-8"))
        report = summarize_temporal_round(planned[recorded.sha256], batch)
        if _json(path / "report.json") != report:
            raise ValueError("GMS diagnostic report differs from its recorded observations")
        reports[recorded.sha256] = report
        found[recorded.sha256] = dict(
            status="COMPLETE" if report["complete"] else "PARTIAL", source=batch.source,
            plan_sha256=recorded.sha256, planned_purchases=report["planned_purchases"],
            observed_purchases=report["observed_purchases"], http_requests=report["http_requests"],
            stop_reason=report["stop_reason"], metrics={key: report["metrics"][key] for key in (
                "label_accuracy", "extraction_field_checks", "case_checks", "semantic_result_coverage", "outcomes")},
            wall_duration_ms=report["wall_duration_ms"],
            timing_scope="LIVE_GMS_PIPELINE" if batch.source == "GMS_IMPORTED" else "SCRIPTED_MOCK_PIPELINE",
            latency_is_gms_measurement=report["latency_is_gms_measurement"],
            response_models=report["response_models"],
            reference=_reference(path, ("plan.json", "observations.json", "report.json")),
            artifact_integrity="VERIFIED", recollection="NOT_RUN",
        )
    rounds = [found.get(plan.sha256, dict(status="NOT_RUN", plan_sha256=plan.sha256,
              planned_purchases=len(plan.case_ids), observed_purchases=0, http_requests=0)) for plan in plans]
    actual = [row for row in rounds if row.get("source") == "GMS_IMPORTED"]
    actual_requests = sum(row["http_requests"] for row in actual)
    if not found:
        status = "NOT_RUN"
    elif not actual:
        status = "MOCK_ONLY"
    else:
        status = "COMPLETE" if all(row.get("source") == "GMS_IMPORTED" and row["status"] == "COMPLETE" for row in rounds) else "PARTIAL"
    return dict(status=status, rounds=rounds, planned_rounds=len(plans),
        actual_requests=actual_requests, recorded_mock_requests=sum(row["http_requests"] for row in rounds if row.get("source") == "MOCK"),
        request_count_basis="RECORDED_GMS_REQUEST_ATTEMPTS_NOT_BILLING",
        max_http_requests=sum(plan.max_http_requests for plan in plans),
        missing_rounds=sum(row["status"] == "NOT_RUN" for row in rounds),
        plan_reference=_reference(plan_directory, ("manifest.json", "sample.json")),
        interpretation="NON_HOLDOUT_SUBSAMPLED_SYNTHETIC_DIAGNOSTIC", human_review="NOT_PERFORMED",
        repeat_consistency=_repeat_consistency(plans, reports),
        quality_targets="NOT_CLAIMED", population_performance="NOT_ESTIMATED", billed_cost=None,
        response_authenticity="RECORDED_PROVENANCE_NOT_INDEPENDENTLY_ATTESTED",
        latency="RECORDED_LIVE_PIPELINE_ATTEMPTS" if any(row.get("latency_is_gms_measurement") and row["wall_duration_ms"]["count"] for row in actual) else "NOT_MEASURED")


def _combination(source, runs, plan_directory, run_directories, path):
    if path is None:
        return dict(status="NOT_RUN", fresh_api_calls=0, live_end_to_end=False, behavior_recomputed=False)
    if not runs or plan_directory is None:
        raise ValueError("combination requires its behavior run and GMS plan bundle")
    from .gms_combination import verify_combination
    verified = verify_combination(path)
    summary = _json(path / "summary.json")
    provenance = verified["provenance"]
    plan, _, _, _ = read_inputs(source)
    matching_runs = [run for run in runs if digest(run / "manifest.json") == provenance["behavior_run_manifest_sha256"]]
    if (provenance["source_manifest_sha256"] != digest(source / "manifest.json")
            or provenance["source_plan_sha256"] != personalization_hash(plan)
            or provenance["gms_plan_manifest_sha256"] != digest(plan_directory / "manifest.json")
            or len(matching_runs) != 1
            or provenance["predictions_sha256"] != digest(matching_runs[0] / "predictions.jsonl")):
        raise ValueError("combination provenance differs from supplied source and behavior evidence")
    supplied = {}
    for directory in run_directories:
        batch = ExperimentBatch.model_validate_json((directory / "observations.json").read_text(encoding="utf-8"))
        supplied[batch.plan_sha256] = personalization_hash(batch)
    if provenance["batch_sha256"] != supplied:
        raise ValueError("combination batches differ from the supplied GMS observation rounds")
    if (summary["interpretation"] != "ARCHIVED_COUNTERFACTUAL_POLICY_PROJECTION"
            or summary["live_end_to_end"] is not False or summary["behavior_recomputed"] is not False
            or summary["fresh_api_calls"] != 0 or summary["status"] != "PROJECTED"):
        raise ValueError("combination cannot claim fresh calls or live system integration")
    numbers = {key: summary[key] for key in (
        "planned_trials", "observed_trials", "missing_trials", "pipeline_rounds", "sampled_purchases")}
    if (any(type(value) is not int or value < 0 for value in numbers.values())
            or numbers["observed_trials"] + numbers["missing_trials"] != numbers["planned_trials"]
            or numbers["pipeline_rounds"] * numbers["sampled_purchases"] != numbers["planned_trials"]):
        raise ValueError("combination trial counts must preserve all missing observations")
    if summary["observation_sources"] not in ([], ["MOCK"], ["GMS_IMPORTED"]):
        raise ValueError("combination must keep Mock and actual GMS provenance separate")
    variation = summary["variation"]
    variation_keys = ("n_purchases", "n_with_two_observed_rounds", "n_with_missing_rounds",
                      "semantic_signal_changed", "extracted_payload_changed", "response_model_changed")
    projected = {key: variation[key] for key in variation_keys}
    projected["combined_outcome_changed"] = {arm: variation["combined_outcome_changed"][arm] for arm in ("cold", "personalized")}
    return dict(status="RECORDED", interpretation=summary["interpretation"], **numbers,
        observation_sources=summary["observation_sources"], fresh_api_calls=0,
        live_end_to_end=False, behavior_recomputed=False, variation=projected,
        reference=_reference(path, ("manifest.json", "summary.json", "rows.json")),
        artifact_integrity="VERIFIED", projection_reexecuted=False,
        policy_projection_is_payment_approval=False)


def _markdown(summary):
    source, offline, regression, gms = (summary[key] for key in ("source", "offline_behavior", "contract_regressions", "gms"))
    lines = ["# FDS 시간순 평가 기록", "", "이 보고서는 저장된 산출물을 검증·재집계한 기록입니다. 모델 재실행이나 시스템 통합 검증은 수행하지 않았습니다.", "",
        f"- 데이터: 합성 / {source['suite']} / {source['split']} / {source['data_role']}",
        f"- 고정 구매 표본: {source['selected_purchases']}건", "- 의미 정답의 인간 검토: 미수행", "",
        "## 행동 평가", "", f"기록 상태: {offline['status']}. 여러 실행의 수치를 합치지 않습니다.", ""]
    for number, run in enumerate(offline["runs"], 1):
        lines.extend([f"### 실행 {number}", "", f"구매 {run['purchases']}건, 동일 구매의 점수 비교 가능 쌍 {run['paired_scored']['n_pairs']}건.",
            f"로컬 전체 실행 시간: {run['offline_wall_duration_seconds']:.3f}초. 프로필 계산을 포함하며 GMS 응답시간이 아닙니다.", "",
            "| 평가 | 점수 산출 비율 | 정상 오탐률 | 사기 라벨 Recall | Average precision |",
            "|---|---:|---:|---:|---:|"])
        for arm, label in (("cold", "Cold"), ("personalized", "개인화")):
            row = run["behavior"][arm]
            values = ["미산출" if row[key] is None else f"{row[key]:.4f}" for key in ("coverage", "false_positive_rate", "recall", "average_precision")]
            lines.append("| " + " | ".join([label, *values]) + " |")
        lines.extend(["", "점수 지표는 산출 가능한 라벨 보유 거래를 대상으로 합니다. GPT는 일정한 Mock 응답을 사용했으므로 여기서 의미 판단 품질을 주장하지 않습니다.",
            f"실행 참조: `{run['run_reference']['directory']}`", ""])
    lines.extend(["## 계약 회귀 검증", "", f"상태: {regression['status']}. 실제 모델 품질·공격 방어 성능과 별개입니다."])
    if regression["status"] != "NOT_RUN":
        lines.extend([f"전체 {regression['n_total']} / 통과 {regression['n_passed']} / 실패 {regression['n_failed']} / 미실행 {regression['n_not_run']}."])
    lines.extend(["", "## GMS 진단", "", f"상태: {gms['status']}. GMS 요청 시도 기록 {gms['actual_requests']}회.",
        "고정 합성 표본의 제한된 진단이며 인간 검토 holdout 또는 실거래 성능 평가가 아닙니다."])
    if len(gms["rounds"]) > 1:
        lines.append(f"동일한 {gms['rounds'][0]['planned_purchases']}개 구매 표본을 {len(gms['rounds'])}개 라운드에서 반복합니다. 라운드 수를 곱해 독립 표본 수로 해석하지 않습니다.")
    if gms["rounds"]:
        lines.extend(["", "| 라운드 | 관측 출처 | 의미 라벨 일치 | 추출 필드 일치 | 전체 사례 검사 |",
                      "|---|---|---:|---:|---:|"])
        for number, row in enumerate(gms["rounds"], 1):
            metrics = row.get("metrics")
            values = []
            for key in ("label_accuracy", "extraction_field_checks", "case_checks"):
                if metrics is None:
                    values.append("미실행")
                else:
                    check = metrics[key]
                    rate = "비율 미산출" if check["rate"] is None else f"{100 * check['rate']:.2f}%"
                    values.append(f"{check['passed']}/{check['total']} ({rate})")
            source_label = {"GMS_IMPORTED": "실제 GMS", "MOCK": "Mock"}.get(row.get("source"), "미실행")
            lines.append("| " + " | ".join([str(number), source_label, *values]) + " |")
        lines.extend(["", "각 수치는 통과 수/검사 대상 수입니다. 전체 사례 검사는 라벨·추출 필드·사유·실행 상태 등 사례의 모든 검사를 통과해야 집계합니다. 실패·누락 사례는 계획된 분모에서 제거하지 않습니다.", ""])
    for number, row in enumerate(gms["rounds"], 1):
        lines.append(f"- 라운드 {number}: {row['status']}, 관측 {row['observed_purchases']}/{row['planned_purchases']}건, 요청 {row['http_requests']}회.")
        if row.get("latency_is_gms_measurement"):
            timing = row["wall_duration_ms"]
            p95 = "미측정" if timing["p95_nearest_rank"] is None else f"{timing['p95_nearest_rank']:.1f}ms"
            lines.append(f"  실제 추출·비교 파이프라인 시간 표본 {timing['count']}개, p95 {p95}. 누락 {timing['unmeasured_count']}개.")
    repeated = gms.get("repeat_consistency", {})
    if "categories" in repeated:
        counts = repeated["categories"]
        lines.append(f"반복 결과: 동일 의미 판단 {counts['SAME_SEMANTIC_LABEL']}건 / 변경 {counts['CHANGED_SEMANTIC_LABEL']}건 / 실패·미실행 {counts['FAILED_OR_NOT_RUN']}건 / 누락 {counts['MISSING']}건 / 실제 호출 아님 {counts['NOT_LIVE']}건.")
    combination = summary["archived_combination"]
    lines.extend(["", "## 보관 결과의 정책 결합", "", f"상태: {combination['status']}."])
    if combination["status"] != "NOT_RUN":
        lines.append(f"행동 기록과 GMS 보관 응답의 정책 투영 {combination['observed_trials']}/{combination['planned_trials']}건, 누락 {combination['missing_trials']}건.")
        lines.append("발생 시점이 다른 보관 자료를 기존 정책에 대입한 결과입니다. 행동 모델 재계산·새 GMS 호출·실시간 전체 흐름 검증이나 결제 승인을 의미하지 않습니다.")
    lines.extend(["", "## 재현 범위와 남은 작업", "",
        "파일 해시 및 저장 결과의 연결은 검증했습니다. 예측 재실행·GMS 재호출은 하지 않았습니다. 저장된 코드 해시는 실행 당시 기록이며 현재 코드와 다르다는 이유만으로 과거 결과를 실패로 판정하지 않습니다.",
        "동일 입력 재생은 기록 당시 코드·설정·의존성을 맞춘 뒤 verify-run으로 별도 수행합니다. 실제 GMS 응답의 완전한 결정성은 보장하지 않습니다.",
        "Core·Pay HTTP 연동, 영속 DB, 실제 사용자 승인·결제 흐름, 실거래 탐지 성능은 미검증입니다.",
        "정확한 원본 참조와 해시는 summary.json에 있습니다. 원문·개인 프로필·API 키는 이 요약에 포함하지 않습니다.", ""])
    return "\n".join(lines)


def write_final_report(source: Path, output: Path, *, runs=(), scores=(), regressions: Path | None = None,
                       gms_plans: Path | None = None, gms_runs=(), combination: Path | None = None) -> dict:
    """Write summary.json/report.md and their manifest into a new directory."""
    source, output = Path(source), Path(output)
    runs, scores, gms_runs = (tuple(Path(path) for path in values) for values in (runs, scores, gms_runs))
    regressions = Path(regressions) if regressions is not None else None
    gms_plans = Path(gms_plans) if gms_plans is not None else None
    combination = Path(combination) if combination is not None else None
    for values in (runs, scores, gms_runs):
        if len({path.resolve() for path in values}) != len(values):
            raise ValueError("duplicate recorded directories cannot inflate evidence")
    sources = [source, *runs, *scores, *gms_runs, *([regressions] if regressions else []),
               *([gms_plans] if gms_plans else []), *([combination] if combination else [])]
    check_new_output(output, *sources)
    verify_inputs(source)
    plan, _, _, selected = read_inputs(source)
    targets = read_targets(source)
    summary = dict(
        schema_version=SCHEMA, status="REPORT_WRITTEN", artifact_integrity="VERIFIED",
        source=dict(reference=_reference(source, ("manifest.json", "plan.json", "evaluator-labels.json")),
            plan_sha256=personalization_hash(plan), origin="SYNTHETIC", suite=plan.suite, split=plan.split,
            data_role=plan.data_role, selected_purchases=len(selected), human_review="NOT_PERFORMED"),
        offline_behavior=_offline_runs(source, runs, scores, plan, targets),
        contract_regressions=_regressions(regressions), gms=_gms(source, gms_plans, gms_runs),
        archived_combination=_combination(source, runs, gms_plans, gms_runs, combination),
        reproducibility=dict(artifact_verification="VERIFIED", prediction_replay="NOT_RUN",
                             current_code_comparison="NOT_PERFORMED", gms_recollection="NOT_RUN"),
        system_integration=dict(core_pay_http="NOT_RUN", persistent_database="NOT_RUN",
                                user_approval_and_payment="NOT_RUN", production_fraud_quality="NOT_ESTABLISHED"),
    )
    markdown = _markdown(summary)
    output.mkdir(parents=True, exist_ok=False)
    with (output / "summary.json").open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(canonical(summary) + "\n")
    with (output / "report.md").open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(markdown)
    manifest = dict(schema_version=SCHEMA, source_manifest_sha256=digest(source / "manifest.json"),
                    external_api="NOT_RUN", predictions_replayed=False,
                    artifact_sha256={name: digest(output / name) for name in ("summary.json", "report.md")})
    with (output / "manifest.json").open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(canonical(manifest) + "\n")
    return summary
