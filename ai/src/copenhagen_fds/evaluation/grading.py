"""Deterministic rubric scoring. Mock scores never become model-quality metrics."""

from collections import Counter
from datetime import datetime, timezone
import json
import math
import re
from statistics import mean, median

from copenhagen_fds.evaluation.models import EvaluationCase, EvaluationDataset, FieldCheck, Observation, ObservationBatch
from copenhagen_fds.evaluation.rubrics import (
    GRADER_VERSION, GRADER_VERSIONS, LEGACY_GRADER_VERSION, RUBRIC_SHA256,
    V1_DATASET_SHA256, check_reasons, field_alternatives,
)


_MISSING = object()


def _canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def check_field(payload: dict | None, check: FieldCheck) -> bool:
    value = payload if payload is not None else _MISSING
    for part in check.path.split("/"):
        if isinstance(value, dict):
            value = value.get(part, _MISSING)
        elif isinstance(value, list) and part.isdigit() and int(part) < len(value):
            value = value[int(part)]
        else:
            value = _MISSING
    if value is _MISSING:
        return False
    if check.operation == "equals":
        return _canonical(value) == _canonical(check.value)
    if check.operation == "nonempty":
        return isinstance(value, (list, str, dict)) and bool(value)
    values = value if isinstance(value, list) else [value]
    return any(isinstance(item, str) and check.value in item for item in values)


def _signature(entry):
    return (entry.code, getattr(entry, "dimension", None), tuple(sorted((ref.order_id, ref.item_id) for ref in entry.related_items)))


def grade_case(case: EvaluationCase, observation: Observation, *, grader_version=GRADER_VERSION) -> dict:
    if grader_version not in GRADER_VERSIONS:
        raise ValueError("unsupported grader version")
    if case.case_id != observation.case_id:
        raise ValueError("observation case identity mismatch")
    run = observation.run.validate_for_request(case.request)
    if run.extraction.input_source != case.request.user_request.source:
        raise ValueError("observation must preserve the case input source")
    common = run.to_common_result(case.request)
    expected = case.expected
    actual_failure = run.extraction.failure or run.comparison.failure
    payload = run.extraction.payload.model_dump(mode="json") if run.extraction.payload else None
    fields = {check.path: check_field(payload, check) or (
        grader_version == GRADER_VERSION and any(check_field(payload, alt) for alt in field_alternatives(case, check))
    ) for check in expected.fields}
    comparison = run.comparison.payload
    references = expected.comparison_status != "SUCCESS"
    findings_ok = confirmations_ok = references
    if comparison is not None and expected.comparison_status == "SUCCESS":
        references = (
            set(expected.required_findings) <= {entry.code for entry in comparison.findings}
            and set(expected.required_confirmations) <= {entry.code for entry in comparison.confirmation_requests}
            and {_signature(e) for e in case.mock_comparison.findings} <= {_signature(e) for e in comparison.findings}
            and {_signature(e) for e in case.mock_comparison.confirmation_requests} <= {_signature(e) for e in comparison.confirmation_requests}
        )
        findings_ok = {_signature(e) for e in case.mock_comparison.findings} <= {_signature(e) for e in comparison.findings}
        confirmations_ok = {_signature(e) for e in case.mock_comparison.confirmation_requests} <= {_signature(e) for e in comparison.confirmation_requests}
        if grader_version == GRADER_VERSION:
            findings_ok, confirmations_ok, references = check_reasons(case, comparison)
    uncertainty = None
    if expected.uncertainty is not None:
        checks = []
        if expected.uncertainty in ("extraction", "both"):
            checks.append(bool(run.extraction.clarifications))
        if expected.uncertainty in ("comparison", "both"):
            checks.append(comparison is not None and comparison.comparison != "MATCH" and bool(comparison.confirmation_requests))
        uncertainty = all(checks)
    checks = {
        "execution": run.extraction.status == expected.extraction_status and run.comparison.status == expected.comparison_status,
        "failure_kind": (actual_failure.kind if actual_failure else None) == expected.failure_kind,
        "comparison": common.comparison == expected.comparison,
        "findings_and_confirmations": references,
        "fields": all(fields.values()),
        "uncertainty": uncertainty is not False,
    }
    row = {
        "case_id": case.case_id, "passed": all(checks.values()), "checks": checks, "field_checks": fields,
        "expected_comparison": expected.comparison,
        "actual_outcome": common.comparison if common.status == "SUCCESS" else common.status,
        "extraction_status": run.extraction.status, "comparison_status": run.comparison.status,
        "failure_kind": actual_failure.kind if actual_failure else None,
        "uncertainty_preserved": uncertainty,
    }
    if grader_version == GRADER_VERSION:
        row["reason_checks"] = {"findings": findings_ok, "confirmations": confirmations_ok, "paired": references}
    return row


def _ratio(passed, total):
    return {"passed": passed, "total": total, "rate": passed / total if total else None}


def _versions(stages, getter):
    values = {getter(stage) for stage in stages} - {None}
    if any(not re.fullmatch(r"[a-zA-Z0-9_.:-]{1,200}", value) for value in values):
        raise ValueError("invalid observation version metadata")
    return sorted(values)


def _provider_metrics(stages):
    attempted = [s for s in stages if any(value is not None for value in (
        s.telemetry.duration_ms, s.telemetry.request_id, s.telemetry.response_id, s.telemetry.usage,
    ))]
    durations = sorted(s.telemetry.duration_ms for s in attempted if s.telemetry.duration_ms is not None)
    usage = {}
    for key in ("prompt_tokens", "completion_tokens", "total_tokens"):
        values = [getattr(s.telemetry.usage, key) for s in attempted if s.telemetry.usage is not None and getattr(s.telemetry.usage, key) is not None]
        usage[key] = {"reported_sum": sum(values) if values else None, "reported_count": len(values), "missing_count": len(attempted) - len(values)}
    return {
        "attempts_with_telemetry": len(attempted),
        "latency_ms": {"count": len(durations), "mean": mean(durations) if durations else None,
                       "median": median(durations) if durations else None,
                       "p95_nearest_rank": durations[math.ceil(len(durations) * .95) - 1] if durations else None},
        "tokens": usage,
        "reported_failure_http_status_counts": dict(Counter(str(s.failure.http_status) for s in stages if s.failure and s.failure.http_status is not None)),
        "failure_kind_counts": dict(Counter(s.failure.kind for s in stages if s.failure)),
        "cost": None,
    }


def evaluate(dataset: EvaluationDataset, batch: ObservationBatch, *, grader_version=GRADER_VERSION) -> dict:
    if grader_version not in GRADER_VERSIONS:
        raise ValueError("unsupported grader version")
    if grader_version == GRADER_VERSION and dataset.sha256 != V1_DATASET_SHA256:
        raise ValueError("v2 rubric requires the frozen v1 dataset")
    if batch.dataset_version != dataset.version or batch.dataset_sha256 != dataset.sha256:
        raise ValueError("observation dataset version or digest mismatch")
    cases = {case.case_id: case for case in dataset.cases}
    if any(o.case_id not in cases for o in batch.observations):
        raise ValueError("observation references an unknown case")
    if batch.source == "GMS_IMPORTED" and any(cases[o.case_id].fault is not None for o in batch.observations):
        raise ValueError("scripted fault cases are not model-quality evaluation inputs")
    rows = [grade_case(cases[o.case_id], o, grader_version=grader_version) for o in batch.observations]
    matrix = {label: dict.fromkeys(("MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"), 0) for label in ("MATCH", "MISMATCH", "UNKNOWN")}
    fields = [passed for row in rows for passed in row["field_checks"].values()]
    uncertainty = [row["uncertainty_preserved"] for row in rows if row["uncertainty_preserved"] is not None]
    for row in rows:
        if row["expected_comparison"] is not None:
            matrix[row["expected_comparison"]][row["actual_outcome"]] += 1
    metrics = {
        "case_checks": _ratio(sum(row["passed"] for row in rows), len(rows)),
        "extraction_field_checks": _ratio(sum(fields), len(fields)),
        "confusion": matrix,
        "uncertainty_preservation": _ratio(sum(uncertainty), len(uncertainty)),
        "false_mismatch": matrix["MATCH"]["MISMATCH"],
        "missed_mismatch": matrix["MISMATCH"]["MATCH"],
        "mismatch_abstained": matrix["MISMATCH"]["UNKNOWN"],
        "unknown_collapsed_to_match": matrix["UNKNOWN"]["MATCH"],
        "execution_outcomes": dict(Counter(row["actual_outcome"] for row in rows)),
        "failure_kinds": dict(Counter(row["failure_kind"] for row in rows if row["failure_kind"])),
    }
    if grader_version == GRADER_VERSION:
        semantic = [row for row in rows if row["expected_comparison"] is not None]
        metrics["comparison_label_checks"] = _ratio(sum(row["checks"]["comparison"] for row in semantic), len(semantic))
        metrics["reason_checks"] = {
            key: _ratio(sum(row["reason_checks"][key] for row in semantic), len(semantic))
            for key in ("findings", "confirmations", "paired")
        }
    stages = {stage: [getattr(o.run, stage) for o in batch.observations] for stage in ("extraction", "comparison")}
    eligible = {case.case_id for case in dataset.cases if batch.source == "MOCK" or case.fault is None}
    report = {
        "report_version": "fds-intent-evaluation-report-v1", "grader_version": grader_version,
        "dataset_version": dataset.version, "dataset_sha256": dataset.sha256,
        "generated_at": datetime.now(timezone.utc).isoformat(), "source": batch.source,
        "interpretation": "SCRIPTED_CONTRACT_REGRESSION_NOT_MODEL_QUALITY" if batch.source == "MOCK" else "IMPORTED_RESPONSES_PROVENANCE_NOT_INDEPENDENTLY_VERIFIED",
        "dataset_case_count": len(dataset.cases), "eligible_case_count": len(eligible), "evaluated_case_count": len(rows),
        "excluded_case_ids": sorted(set(cases) - eligible),
        "missing_case_ids": sorted(eligible - {o.case_id for o in batch.observations}),
        "versions": {name: {
            "requested_models": _versions(values, lambda s: s.telemetry.requested_model),
            "response_models": _versions(values, lambda s: s.metadata.model_version),
            "prompt_versions": _versions(values, lambda s: s.metadata.prompt_version),
        } for name, values in stages.items()},
        "mock_regression_metrics": metrics if batch.source == "MOCK" else None,
        "imported_response_metrics": metrics if batch.source == "GMS_IMPORTED" else None,
        "provider_metrics": {name: _provider_metrics(values) for name, values in stages.items()} if batch.source == "GMS_IMPORTED" else None,
        "cases": rows,
    }
    if grader_version == GRADER_VERSION:
        report.update(report_version="fds-intent-evaluation-report-v2", rubric_sha256=RUBRIC_SHA256)
    return report
