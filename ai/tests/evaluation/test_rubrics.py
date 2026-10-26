"""Equivalent explanations are finite; incorrect values and references still fail."""

import json

import pytest

from copenhagen_fds.evaluation.grading import evaluate, grade_case
from copenhagen_fds.evaluation.models import Observation
from copenhagen_fds.evaluation.rubrics import GRADER_VERSION, LEGACY_GRADER_VERSION, V1_DATASET_SHA256


def changed(batch, case_id, *, code="INSUFFICIENT_DATA", dimension="QUANTITY",
            confirmation="PROVIDE_MISSING_DATA", refs=(), extra=False):
    data = next(o for o in batch.observations if o.case_id == case_id).model_dump(mode="json")
    result = data["run"]["comparison"]
    result["payload"]["findings"] = [{"code": code, "dimension": dimension, "related_items": list(refs)}]
    result["payload"]["confirmation_requests"] = [{"code": confirmation, "related_items": list(refs)}]
    if extra:
        result["payload"]["findings"].append({"code": "INTENT_UNCERTAIN", "dimension": "BRAND", "related_items": []})
    result["reasons"] = [{"code": f["code"], "source": "MOCK"} for f in result["payload"]["findings"]]
    return Observation.model_validate_json(json.dumps(data))


def test_frozen_dataset_and_legacy_report_are_preserved(dataset, batch):
    assert dataset.sha256 == V1_DATASET_SHA256
    old = evaluate(dataset, batch, grader_version=LEGACY_GRADER_VERSION)
    new = evaluate(dataset, batch)
    assert old["report_version"].endswith("v1") and "rubric_sha256" not in old
    assert new["grader_version"] == GRADER_VERSION and len(new["rubric_sha256"]) == 64
    assert old["mock_regression_metrics"]["case_checks"] == new["mock_regression_metrics"]["case_checks"]
    assert new["mock_regression_metrics"]["comparison_label_checks"]["total"] == 23


@pytest.mark.parametrize("case_id,dimension", [
    ("fds-04-no-quantity", "QUANTITY"), ("fds-05-no-currency", "BUDGET"),
    ("fds-17-purpose-only", "PRODUCT"),
])
def test_equivalent_missing_information_explanations(dataset, batch, case_id, dimension):
    case = next(c for c in dataset.cases if c.case_id == case_id)
    observation = changed(batch, case_id, dimension=dimension)
    assert grade_case(case, observation)["passed"]
    assert not grade_case(case, observation, grader_version=LEGACY_GRADER_VERSION)["passed"]


@pytest.mark.parametrize("kwargs", [
    {"dimension": "BRAND"}, {"confirmation": "CONFIRM_SUBSTITUTION"},
    {"refs": ({"order_id": "order-1", "item_id": "item-1"},)}, {"extra": True},
])
def test_unrelated_explanations_and_invented_item_scope_fail(dataset, batch, kwargs):
    case = next(c for c in dataset.cases if c.case_id == "fds-04-no-quantity")
    row = grade_case(case, changed(batch, case.case_id, **kwargs))
    assert row["checks"]["comparison"] and not row["checks"]["findings_and_confirmations"]
    assert not row["passed"]


def test_nullable_category_only_accepts_the_same_product(dataset, batch):
    case = next(c for c in dataset.cases if c.case_id == "fds-19-source-unknown")
    data = next(o for o in batch.observations if o.case_id == case.case_id).model_dump(mode="json")
    data["run"]["extraction"]["payload"]["items"][0]["category"] = None
    observation = Observation.model_validate_json(json.dumps(data))
    assert grade_case(case, observation)["passed"]
    assert not grade_case(case, observation, grader_version=LEGACY_GRADER_VERSION)["passed"]
    data["run"]["extraction"]["payload"]["items"][0]["quantity"] = 3
    assert not grade_case(case, Observation.model_validate_json(json.dumps(data)))["passed"]


def test_literal_numeric_condition_does_not_allow_new_semantic_conditions(dataset, batch):
    case = next(c for c in dataset.cases if c.case_id == "fds-23-injection-mismatch")
    data = next(o for o in batch.observations if o.case_id == case.case_id).model_dump(mode="json")
    for conditions, passed in [(["2개만"], True), (["한국어 자판 필수"], False), (["2개만", "흰색 필수"], False)]:
        data["run"]["extraction"]["payload"]["explicit_conditions"] = conditions
        assert grade_case(case, Observation.model_validate_json(json.dumps(data)))["passed"] is passed


def test_v2_rejects_modified_dataset_even_with_matching_batch_hash(dataset, batch):
    changed_dataset = dataset.model_copy(update={"version": "other"})
    changed_batch = batch.model_copy(update={"dataset_version": "other", "dataset_sha256": changed_dataset.sha256})
    with pytest.raises(ValueError, match="frozen"):
        evaluate(changed_dataset, changed_batch)


def test_unknown_grader_is_rejected(dataset, batch):
    with pytest.raises(ValueError, match="unsupported"):
        evaluate(dataset, batch, grader_version="missing")
