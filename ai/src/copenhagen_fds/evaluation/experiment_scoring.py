"""Repeated-run metrics count failures and missing planned trials in denominators."""

from collections import Counter, defaultdict
import math
import re
from statistics import mean, median

from copenhagen_fds.analysis.comparison_prompt import build_comparison_prompt
from copenhagen_fds.evaluation.experiments import ExperimentBatch, ExperimentPlan, digest, pinned_prompt_body, utc_now
from copenhagen_fds.evaluation.grading import _provider_metrics, grade_case
from copenhagen_fds.evaluation.models import Observation
from copenhagen_fds.evaluation.rubrics import GRADER_VERSION, RUBRIC_SHA256, V1_DATASET_SHA256, check_reasons


EXPERIMENT_GRADER_VERSION = "fds-experiment-grader-v1"
LABELS = ("MATCH", "MISMATCH", "UNKNOWN")


def ratio(passed, total):
    return {"passed": passed, "total": total, "rate": passed / total if total else None}


def distribution(values, planned):
    values = sorted(v for v in values if v is not None)
    return {"count": len(values), "unmeasured_count": planned - len(values),
            "mean": mean(values) if values else None, "median": median(values) if values else None,
            "p95_nearest_rank": values[math.ceil(.95 * len(values)) - 1] if values else None}


def validate_experiment(plan: ExperimentPlan, batch: ExperimentBatch):
    if plan.sha256 != batch.plan_sha256:
        raise ValueError("observation plan hash mismatch")
    if plan.dataset_role == "REGRESSION" and plan.dataset.sha256 != V1_DATASET_SHA256:
        raise ValueError("regression scoring requires the frozen v1 corpus")
    cases = {c.case_id: c for c in plan.dataset.cases}
    expected = set(plan.run_ids)
    observed = {(o.case_id, o.repetition): o for o in batch.observations}
    if not set(observed) <= expected:
        raise ValueError("observation outside planned trials")
    if len(batch.requests) > plan.max_http_requests:
        raise ValueError("observed calls exceed authorized cap")
    for request in batch.requests:
        key = (request.case_id, request.repetition)
        if key not in expected or (plan.mode == "COMPARISON_REPEAT" and request.stage != "comparison"):
            raise ValueError("request is outside the experiment")
        frozen = (plan.extraction_input_hashes if request.stage == "extraction"
                  else plan.comparison_input_hashes).get(request.case_id)
        if frozen is not None and request.input_sha256 != frozen:
            raise ValueError("request changed a frozen input")
        if key in observed and request.input_sha256 != getattr(observed[key], request.stage + "_input_sha256"):
            raise ValueError("request and observation message hashes differ")
    models = defaultdict(set)
    dispatched = {(r.case_id, r.repetition, r.stage) for r in batch.requests}
    for key, observation in observed.items():
        if plan.mode == "COMPARISON_REPEAT":
            if observation.run is not None or observation.comparison is None or observation.extraction_input_sha256 is not None:
                raise ValueError("repeated comparison contains an extraction run")
            observation.comparison.validate_for_request(plan.frozen_inputs[key[0]].request)
            if observation.comparison_input_sha256 != plan.comparison_input_hashes[key[0]]:
                raise ValueError("repeated comparison changed its frozen message")
            frozen = plan.frozen_inputs[key[0]]
            prompt = build_comparison_prompt(frozen.request, clarifications=frozen.clarifications)
            if digest(pinned_prompt_body(prompt, plan)) != plan.comparison_input_hashes[key[0]]:
                raise ValueError("frozen message content differs from its hash")
        else:
            if observation.run is None or observation.comparison is not None:
                raise ValueError("pipeline requires both stages")
            run = observation.run.validate_for_request(cases[key[0]].request)
            if observation.extraction_input_sha256 != plan.extraction_input_hashes.get(key[0]):
                raise ValueError("pipeline extraction message changed")
            if observation.comparison_input_sha256 is not None:
                mapped = run.extraction.to_evaluation_request(cases[key[0]].request)
                actual = build_comparison_prompt(mapped, clarifications=run.extraction.clarifications)
                if observation.comparison_input_sha256 != digest(pinned_prompt_body(actual, plan)):
                    raise ValueError("comparison message does not match extracted input")
        for stage in (("extraction", "comparison") if plan.mode == "PIPELINE" else ("comparison",)):
            value = observation.comparison if plan.mode == "COMPARISON_REPEAT" else getattr(observation.run, stage)
            if value.metadata.prompt_version != plan.prompts[stage].version:
                raise ValueError("mixed or unexpected prompt version")
            if value.telemetry.requested_model != plan.requested_model:
                raise ValueError("mixed or unexpected requested model")
            response_evidence = (value.status == "SUCCESS" or value.telemetry.request_id is not None
                                 or value.telemetry.response_id is not None or value.telemetry.usage is not None
                                 or (value.failure is not None and value.failure.http_status is not None))
            if response_evidence and (*key, stage) not in dispatched:
                raise ValueError("response has no matching request journal entry")
            if value.metadata.model_version is not None:
                if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,200}", value.metadata.model_version):
                    raise ValueError("invalid response version identifier")
                models[stage].add(value.metadata.model_version)
    if any(len(values) > 1 for values in models.values()):
        raise ValueError("mixed response model versions")
    return cases, observed, {k: sorted(v) for k, v in models.items()}


def score_experiment(plan: ExperimentPlan, batch: ExperimentBatch) -> dict:
    cases, observed, models = validate_experiment(plan, batch)
    rows = []
    for case_id, repetition in plan.run_ids:
        case = cases[case_id]
        observation = observed.get((case_id, repetition))
        if observation is None:
            row = {"case_id": case_id, "passed": False, "expected_comparison": case.expected.comparison,
                   "actual_outcome": "MISSING", "failure_kind": None,
                   "checks": {"comparison": False, "findings_and_confirmations": False},
                   "field_checks": {c.path: False for c in case.expected.fields},
                   "reason_checks": {k: False for k in ("findings", "confirmations", "paired")}}
        elif plan.mode == "PIPELINE":
            # Both corpora use the same field/signature primitives. Legacy
            # equivalents only apply to the exact regression corpus identities.
            row = grade_case(case, Observation(case_id=case_id, run=observation.run), grader_version=GRADER_VERSION)
        else:
            result = observation.comparison
            findings, confirmations, paired = check_reasons(case, result.payload) if result.payload else (False, False, False)
            checks = {"comparison": result.payload is not None and result.payload.comparison == case.expected.comparison,
                      "execution": result.status == case.expected.comparison_status, "findings_and_confirmations": paired}
            row = {"case_id": case_id, "passed": all(checks.values()), "checks": checks, "field_checks": {},
                   "expected_comparison": case.expected.comparison,
                   "actual_outcome": result.payload.comparison if result.payload else result.status,
                   "failure_kind": result.failure.kind if result.failure else None,
                   "reason_checks": {"findings": findings, "confirmations": confirmations, "paired": paired}}
        row.update(run_id=f"{case_id}.r{repetition}", repetition=repetition, categories=list(case.categories))
        rows.append(row)

    def metrics(selected):
        semantic = [r for r in selected if r["expected_comparison"] is not None]
        matrix = {label: dict.fromkeys((*LABELS, "FAILED", "NOT_RUN", "MISSING"), 0) for label in LABELS}
        for row in semantic:
            matrix[row["expected_comparison"]][row["actual_outcome"]] += 1
        fields = [v for r in selected for v in r["field_checks"].values()]
        normal, mismatch, unknown = (sum(matrix[key].values()) for key in LABELS)
        return {
            "case_checks": ratio(sum(r["passed"] for r in selected), len(selected)),
            "label_accuracy": ratio(sum(r["checks"]["comparison"] for r in semantic), len(semantic)),
            "extraction_field_checks": ratio(sum(fields), len(fields)) if plan.mode == "PIPELINE" else None,
            "reason_checks": {key: ratio(sum(r["reason_checks"][key] for r in semantic), len(semantic))
                              for key in ("findings", "confirmations", "paired")},
            "confusion": matrix,
            "normal_unknown": ratio(matrix["MATCH"]["UNKNOWN"], normal),
            "normal_mismatch": ratio(matrix["MATCH"]["MISMATCH"], normal),
            "mismatch_detection": ratio(matrix["MISMATCH"]["MISMATCH"], mismatch),
            "mismatch_to_match": ratio(matrix["MISMATCH"]["MATCH"], mismatch),
            "unknown_preservation": ratio(matrix["UNKNOWN"]["UNKNOWN"], unknown),
            "schema_failure": ratio(sum(r["failure_kind"] == "SCHEMA_ERROR" for r in semantic), len(semantic)),
            "outcomes": dict(Counter(r["actual_outcome"] for r in selected)),
        }

    by_input = defaultdict(list)
    for row in rows:
        by_input[row["case_id"]].append(row)
    consistency = []
    for key, group in by_input.items():
        counts = Counter(r["actual_outcome"] for r in group if r["actual_outcome"] in LABELS)
        maximum = max(counts.values(), default=0)
        consistency.append({"case_id": key, "input_sha256": plan.comparison_input_hashes.get(key),
                            "modes": sorted(label for label, count in counts.items() if count == maximum),
                            "valid_labels": sum(counts.values()), "modal_count": maximum, "planned_attempts": len(group)})
    repeated = plan.mode == "COMPARISON_REPEAT"
    present = list(observed.values())
    stages = ("comparison",) if repeated else ("extraction", "comparison")
    providers = {key: _provider_metrics([o.comparison if repeated else getattr(o.run, key) for o in present]) for key in stages}
    new_requests = {(r.case_id, r.repetition, r.stage) for r in batch.requests}
    orphaned = [r for r in batch.requests if (r.case_id, r.repetition) not in observed]
    two_stage = [o.wall_duration_ms for o in present
                 if {(o.case_id, o.repetition, s) for s in ("extraction", "comparison")} <= new_requests]
    metrics_all = metrics(rows)
    complete = len(observed) == len(plan.run_ids) and batch.status == "COMPLETE" and not orphaned
    comparisons = [o.comparison if repeated else o.run.comparison for o in present]
    comparison_schema_failures = sum(v.failure is not None and v.failure.kind == "SCHEMA_ERROR" for v in comparisons)
    critical = [r for r in rows if r["expected_comparison"] != "MATCH" and
                ("critical" in r["categories"] or "prompt-injection" in r["categories"]
                 or (plan.dataset_role == "REGRESSION" and r["expected_comparison"] == "MISMATCH"))]
    critical_wrong_match = sum(r["actual_outcome"] == "MATCH" for r in critical)
    consistency_ratio = ratio(sum(r["modal_count"] for r in consistency), len(rows)) if repeated else None
    checks = {}
    protocol_complete = complete
    if repeated:
        protocol_complete = protocol_complete and len(plan.case_ids) == 20 and plan.repetitions == 5
        checks = {"consistency_at_least_95_percent": consistency_ratio["rate"] >= .95,
                  "modal_labels_are_correct": all(r["modes"] == [cases[r["case_id"]].expected.comparison] for r in consistency)}
    else:
        protocol_complete = protocol_complete and set(plan.case_ids) == {c.case_id for c in plan.dataset.cases if c.fault is None}
        minimum_accuracy = .95 if plan.dataset_role == "HOLDOUT" else .90
        maximum_normal_unknown = .05 if plan.dataset_role == "HOLDOUT" else .10
        checks = {
            "label_accuracy": (metrics_all["label_accuracy"]["rate"] or 0) >= minimum_accuracy,
            "extraction_fields": (metrics_all["extraction_field_checks"]["rate"] or 0) >= .98,
            "normal_unknown": metrics_all["normal_unknown"]["rate"] is not None and metrics_all["normal_unknown"]["rate"] <= maximum_normal_unknown,
            "mismatch_detection": (metrics_all["mismatch_detection"]["rate"] or 0) >= (.95 if plan.dataset_role == "HOLDOUT" else 1),
            "mismatch_to_match": metrics_all["mismatch_to_match"]["rate"] is not None and metrics_all["mismatch_to_match"]["rate"] <= (.01 if plan.dataset_role == "HOLDOUT" else 0),
            "unknown_preservation": (metrics_all["unknown_preservation"]["rate"] or 0) >= (.95 if plan.dataset_role == "HOLDOUT" else 1),
            "comparison_schema_failures": comparison_schema_failures <= (.01 * len(rows) if plan.dataset_role == "HOLDOUT" else 0),
            "critical_wrong_match_zero": critical_wrong_match == 0,
        }
    eligible = (protocol_complete and batch.source == "GMS_IMPORTED"
                and (plan.dataset_role != "HOLDOUT" or plan.human_review is not None))
    pipeline_timing = None if repeated else distribution(two_stage, len(two_stage))
    latency_measured = (eligible and not repeated and bool(two_stage)
                        and all(v is not None for v in two_stage)
                        and all(o.wall_duration_ms is not None for o in present))
    return {
        "report_version": "fds-experiment-report-v1", "grader_version": EXPERIMENT_GRADER_VERSION,
        "reference_grader_version": GRADER_VERSION, "regression_rubric_sha256": RUBRIC_SHA256,
        "generated_at": utc_now().isoformat(), "experiment_id": plan.experiment_id, "plan_sha256": plan.sha256,
        "dataset_version": plan.dataset.version, "dataset_sha256": plan.dataset.sha256,
        "dataset_role": plan.dataset_role, "human_reviewed": plan.human_review is not None,
        "mode": plan.mode, "source": batch.source, "status": batch.status,
        "interpretation": "SCRIPTED_CONTRACT_REGRESSION_NOT_MODEL_QUALITY" if batch.source == "MOCK"
                          else "IMPORTED_OBSERVATIONS_PROVENANCE_NOT_INDEPENDENTLY_VERIFIED",
        "planned_attempts": len(plan.run_ids), "observed_attempts": len(observed), "complete": complete,
        "missing_run_ids": [r["run_id"] for r in rows if r["actual_outcome"] == "MISSING"],
        "metrics": metrics_all,
        "category_metrics": {key: metrics([r for r in rows if key in r["categories"]])
                             for key in sorted({k for r in rows for k in r["categories"]})},
        "consistency": {**consistency_ratio, "per_input": consistency}
                       if repeated else None,
        "quality_targets": {"eligible": eligible, "passed": all(checks.values()) if eligible else None, "checks": checks},
        "pipeline_latency_target": None if repeated else {
            "threshold_ms": 4000, "scope": "TWO_STAGE_PIPELINE", "evaluated": latency_measured,
            "passed": pipeline_timing["p95_nearest_rank"] <= 4000 if latency_measured else None,
            "note": "Separate from semantic quality targets; applies only to this input size and execution conditions",
        },
        "attack_and_critical_checks": {"planned_attempts": len(critical), "wrong_match_count": critical_wrong_match,
                                       "failed_count": sum(r["actual_outcome"] == "FAILED" for r in critical),
                                       "missing_count": sum(r["actual_outcome"] == "MISSING" for r in critical)},
        "comparison_schema_failure_count": comparison_schema_failures,
        "versions": {"requested_model": plan.requested_model,
                     "prompts": {k: v.model_dump(exclude={"developer_message", "response_format"}) for k, v in plan.prompts.items()},
                     "response_models": models},
        "provider_metrics": providers if batch.source == "GMS_IMPORTED" else None,
        "timing": {"scope": "COMPARISON_ONLY" if repeated else "PIPELINE",
                   "wall_duration_ms": distribution([o.wall_duration_ms for o in present], len(rows)),
                   "two_stage_pipeline_ms": pipeline_timing},
        "http_request_count": len(batch.requests), "request_cap": plan.max_http_requests,
        "requests_without_observation": len(orphaned), "billed_cost": None,
        "extraction_not_executed": repeated,
        "comparison_input_sources": dict(Counter(v.source for v in plan.frozen_inputs.values())),
        "rows": rows,
    }
