"""Deliberately wrong observations verify rates, denominators and provenance."""

import json

from pydantic import ValidationError
import pytest

from copenhagen_fds.evaluation.grading import check_field, evaluate, grade_case
from copenhagen_fds.evaluation.models import FieldCheck, Observation, ObservationBatch


def _changed_comparison(observation, label, *, order="order-1"):
    data = observation.model_dump(mode="json")
    code = "INTENT_MISMATCH" if label == "MISMATCH" else "INTENT_UNCERTAIN"
    payload = {"comparison": label, "findings": [], "confirmation_requests": []}
    if label != "MATCH":
        payload["findings"] = [{"code": code, "dimension": "PRODUCT", "related_items": [{"order_id": order, "item_id": "item-1"}]}]
    data["run"]["comparison"]["payload"] = payload
    data["run"]["comparison"]["reasons"] = [] if label == "MATCH" else [{"code": code, "source": "MOCK"}]
    return Observation.model_validate_json(json.dumps(data))


def _batch(dataset, observations, source="MOCK"):
    return ObservationBatch(dataset_version=dataset.version, dataset_sha256=dataset.sha256, source=source, observations=observations)


def test_mock_perfect_scores_are_explicitly_not_model_quality(dataset, batch):
    report = evaluate(dataset, batch)
    assert report["source"] == "MOCK" and "NOT_MODEL_QUALITY" in report["interpretation"]
    assert report["imported_response_metrics"] is None and report["provider_metrics"] is None
    metrics = report["mock_regression_metrics"]
    assert metrics["case_checks"] == {"passed": 35, "total": 35, "rate": 1.0}
    assert metrics["extraction_field_checks"]["rate"] == 1.0
    assert metrics["uncertainty_preservation"]["rate"] == 1.0
    assert report["dataset_sha256"] == dataset.sha256 and report["grader_version"]
    assert report["versions"]["extraction"]["prompt_versions"] == ["fds-intent-extraction-v2"]
    assert report["versions"]["comparison"]["prompt_versions"] == ["fds-intent-comparison-v4"]
    assert not report["missing_case_ids"] and report["evaluated_case_count"] == 35


@pytest.mark.parametrize("payload,path,value,operation,passed", [
    ({"budget": None}, "budget", None, "equals", True),
    ({}, "budget", None, "equals", False),
    (None, "budget", None, "equals", False),
    ({"items": [{"quantity": True}]}, "items/0/quantity", 1, "equals", False),
    ({"items": [{"quantity": "2"}]}, "items/0/quantity", 2, "equals", False),
    ({"items": []}, "items/0/name", None, "equals", False),
    ({"explicit_conditions": ["한국어 자판 필수"]}, "explicit_conditions", "한국어", "contains", True),
    ({"ambiguities": []}, "ambiguities", None, "nonempty", False),
    ({"ambiguities": ["수량 확인"]}, "ambiguities", None, "nonempty", True),
])
def test_field_operators_do_not_coerce_missing_null_or_types(payload, path, value, operation, passed):
    assert check_field(payload, FieldCheck(path=path, operation=operation, value=value)) is passed


def test_wrong_extracted_quantity_reduces_field_rate(dataset, batch):
    data = batch.observations[0].model_dump(mode="json")
    data["run"]["extraction"]["payload"]["items"][0]["quantity"] = 3
    wrong = Observation.model_validate_json(json.dumps(data))
    report = evaluate(dataset, _batch(dataset, [wrong]))
    assert report["mock_regression_metrics"]["extraction_field_checks"] == {"passed": 2, "total": 3, "rate": 2 / 3}
    assert report["cases"][0]["passed"] is False
    assert len(report["missing_case_ids"]) == 34


def test_false_mismatch_missed_mismatch_abstention_and_unknown_collapse_are_separate(dataset, batch):
    by_id = {o.case_id: o for o in batch.observations}
    observations = [
        _changed_comparison(by_id["fds-01-clear"], "MISMATCH"),
        _changed_comparison(by_id["fds-08-condition-mismatch"], "MATCH"),
        _changed_comparison(by_id["fds-10-brand"], "UNKNOWN"),
        _changed_comparison(by_id["fds-05-no-currency"], "MATCH"),
    ]
    metrics = evaluate(dataset, _batch(dataset, observations))["mock_regression_metrics"]
    assert metrics["false_mismatch"] == metrics["missed_mismatch"] == metrics["mismatch_abstained"] == metrics["unknown_collapsed_to_match"] == 1
    assert metrics["confusion"]["MATCH"]["MISMATCH"] == 1
    assert metrics["uncertainty_preservation"] == {"passed": 0, "total": 1, "rate": 0.0}


def test_failed_extraction_remains_in_semantic_and_field_denominators(dataset, batch):
    data = batch.observations[0].model_dump(mode="json")
    failed = batch.observations[26].model_dump(mode="json")
    for name in ("extraction", "comparison"):
        metadata = data["run"][name]["metadata"]
        data["run"][name] = failed["run"][name]
        data["run"][name]["metadata"]["target"] = metadata["target"]
        data["run"][name]["metadata"]["trace_id"] = metadata["trace_id"]
    report = evaluate(dataset, _batch(dataset, [Observation.model_validate_json(json.dumps(data))]))
    metrics = report["mock_regression_metrics"]
    assert metrics["confusion"]["MATCH"]["FAILED"] == 1
    assert metrics["extraction_field_checks"] == {"passed": 0, "total": 3, "rate": 0.0}
    assert metrics["false_mismatch"] == metrics["missed_mismatch"] == 0


def test_references_and_dimensions_are_graded_not_only_reason_codes(dataset, batch):
    case = dataset.cases[15]
    wrong = _changed_comparison(batch.observations[15], "MISMATCH", order="order-1")
    row = grade_case(case, wrong)
    assert row["checks"]["comparison"] and row["checks"]["execution"]
    assert not row["checks"]["findings_and_confirmations"] and not row["passed"]


@pytest.mark.parametrize("mutation", ["case", "version", "digest", "target", "source", "duplicate"])
def test_batch_identity_and_provenance_errors_are_rejected(dataset, batch, mutation):
    data = _batch(dataset, [batch.observations[0]]).model_dump(mode="json")
    if mutation == "case":
        data["observations"][0]["case_id"] = "fds-unknown"
    elif mutation == "version":
        data["dataset_version"] = "other-version"
    elif mutation == "digest":
        data["dataset_sha256"] = "b" * 64
    elif mutation == "target":
        for stage in ("extraction", "comparison"):
            data["observations"][0]["run"][stage]["metadata"]["target"]["user_id"] = "other-user"
    elif mutation == "source":
        data["source"] = "GMS_IMPORTED"
    else:
        data["observations"].append(data["observations"][0])
    with pytest.raises(ValueError):
        evaluate(dataset, ObservationBatch.model_validate_json(json.dumps(data)))


def _synthetic_import(observation, *, usage=None, duration=None):
    """Artificial imported-format input for grader tests, never a real GMS result."""
    data = observation.model_dump(mode="json")
    for stage in ("extraction", "comparison"):
        value = data["run"][stage]
        value["metadata"]["sources"] = ["BE", "FDS", "OPENAI"]
        value["metadata"]["model_version"] = "synthetic-imported-model" if value["metadata"]["model_version"] else None
        for reason in value["reasons"]:
            if reason["source"] == "MOCK":
                reason["source"] = "OPENAI"
        value["telemetry"]["gateway"] = "GMS"
        value["telemetry"]["duration_ms"] = duration
        value["telemetry"]["usage"] = usage
    return Observation.model_validate_json(json.dumps(data))


def test_imported_format_metrics_keep_missing_usage_unknown(dataset, batch):
    synthetic = [_synthetic_import(batch.observations[0], duration=20.0),
                 _synthetic_import(batch.observations[1], duration=40.0, usage={"prompt_tokens": 4, "completion_tokens": 0, "total_tokens": 4})]
    report = evaluate(dataset, _batch(dataset, synthetic, source="GMS_IMPORTED"))
    assert report["mock_regression_metrics"] is None and report["imported_response_metrics"]
    assert "NOT_INDEPENDENTLY_VERIFIED" in report["interpretation"]
    assert report["eligible_case_count"] == 26 and len(report["excluded_case_ids"]) == 9
    metrics = report["provider_metrics"]["comparison"]
    assert metrics["latency_ms"] == {"count": 2, "mean": 30.0, "median": 30.0, "p95_nearest_rank": 40.0}
    assert metrics["tokens"]["completion_tokens"] == {"reported_sum": 0, "reported_count": 1, "missing_count": 1}
    assert metrics["cost"] is None


def test_no_reported_usage_is_not_reported_as_zero(dataset, batch):
    report = evaluate(dataset, _batch(dataset, [_synthetic_import(batch.observations[0])], source="GMS_IMPORTED"))
    metrics = report["provider_metrics"]["extraction"]
    assert metrics["latency_ms"]["mean"] is None
    assert metrics["tokens"]["total_tokens"] == {"reported_sum": None, "reported_count": 0, "missing_count": 1}


def test_scripted_faults_cannot_become_imported_model_quality_cases(dataset, batch):
    fake = _synthetic_import(batch.observations[27], duration=5.0)
    with pytest.raises(ValueError, match="scripted fault"):
        evaluate(dataset, _batch(dataset, [fake], source="GMS_IMPORTED"))


def test_report_contains_no_input_payload_references_or_exception_text(dataset, batch):
    report = json.dumps(evaluate(dataset, batch), ensure_ascii=False)
    assert "synthetic-private" not in report and "synthetic-offline-key" not in report
    for forbidden in ("request_text", "explicit_conditions\": [", "snapshot_hash", "user_id", "request_id", "response_id"):
        assert forbidden not in report


def test_invalid_version_metadata_is_not_echoed(dataset, batch):
    data = batch.observations[0].model_dump(mode="json")
    data["run"]["extraction"]["metadata"]["model_version"] = "synthetic-private input\nsecret"
    bad = Observation.model_validate_json(json.dumps(data))
    with pytest.raises(ValueError, match="invalid observation version metadata") as error:
        evaluate(dataset, _batch(dataset, [bad]))
    assert "synthetic-private" not in str(error.value)


def test_imported_legacy_shape_cannot_omit_input_source(dataset, batch):
    data = batch.observations[0].model_dump(mode="json")
    del data["run"]["extraction"]["input_source"]
    with pytest.raises(ValueError, match="preserve the case input source"):
        evaluate(dataset, _batch(dataset, [Observation.model_validate_json(json.dumps(data))]))


@pytest.mark.parametrize("data", [
    {"path": "__class__", "operation": "equals"},
    {"path": "budget", "operation": "contains", "value": 1},
    {"path": "budget", "operation": "nonempty", "value": 1},
])
def test_invalid_rubric_operations_are_rejected(data):
    with pytest.raises(ValidationError):
        FieldCheck.model_validate(data)
