"""Known metric answers, missing evidence and failures (S15P21A207-358)."""

from copy import deepcopy
from itertools import permutations
import json
import math

import pytest

from copenhagen_fds.temporal_benchmark.metrics import binary_metrics, semantic_confusion


def row(label, score, status="SUCCESS"):
    return dict(label=label, score=score, status=status)


def test_binary_confusion_threshold_is_inclusive_and_uses_real_denominators():
    report = binary_metrics([
        row(True, 100), row(True, 50), row(True, 10),
        row(False, 50), row(False, 20), row(False, 0),
    ], threshold=50)
    assert report["confusion"] == dict(tp=2, fp=1, fn=1, tn=2)
    assert report["precision"] == pytest.approx(2 / 3)
    assert report["recall"] == pytest.approx(2 / 3)
    assert report["false_positive_rate"] == pytest.approx(1 / 3)
    assert report["coverage"] == report["labeled_coverage"] == 1


def test_average_precision_known_answer_is_not_trapezoidal_interpolation():
    # Positive ranks 1, 3 and 4: AP = (1 + 2/3 + 3/4) / 3 = 29/36.
    report = binary_metrics([row(True, 90), row(False, 80), row(True, 70), row(True, 60)], threshold=50)
    assert report["average_precision"] == pytest.approx(29 / 36)
    assert report["rank_metric"] == "AVERAGE_PRECISION_TIED_SCORE_GROUPS"


def test_tied_scores_are_evaluated_as_groups_independent_of_input_order():
    rows = [row(True, 90), row(False, 90), row(True, 50), row(False, 10)]
    # At 90: recall gain 1/2 * precision 1/2; at 50: 1/2 * 2/3.
    for ordering in permutations(rows):
        assert binary_metrics(ordering, threshold=50)["average_precision"] == pytest.approx(7 / 12)


def test_identical_scores_give_prevalence_not_an_arbitrary_positive_first_ranking():
    report = binary_metrics([row(True, 20), row(True, 20), row(False, 20)], threshold=50)
    assert report["average_precision"] == pytest.approx(2 / 3)
    assert report["precision"] is None
    assert report["recall"] == 0


def test_missing_scores_unknown_truth_and_failed_retained_scores_stay_separate():
    report = binary_metrics([
        row(True, 80), row(False, 20), row(None, 99),
        row(True, None), row(None, None, "NOT_RUN"),
        row(True, 99, "FAILED"), row(False, 0, "INCOMPLETE"),
        row(False, None, "UNKNOWN"),
    ], threshold=50)
    assert report["n_total"] == 8
    assert report["n_labeled"] == 6
    assert report["n_unknown_label"] == 2
    assert report["n_scored"] == 3
    assert report["n_scored_labeled"] == 2
    assert report["n_missing_score"] == 3
    assert report["n_excluded_retained_score"] == 2
    assert report["coverage"] == 3 / 8
    assert report["labeled_coverage"] == 2 / 6
    assert report["confusion"] == dict(tp=1, fp=0, fn=0, tn=1)
    assert report["average_precision"] == 1
    assert report["status_counts"] == {
        "SUCCESS": 4, "INCOMPLETE": 1, "FAILED": 1, "CANCELLED": 0, "UNKNOWN": 1, "NOT_RUN": 1}


@pytest.mark.parametrize("status", ["INCOMPLETE", "FAILED", "CANCELLED", "UNKNOWN", "NOT_RUN"])
def test_non_success_status_never_promotes_retained_score_to_a_successful_prediction(status):
    report = binary_metrics([row(False, 99, status)], threshold=50)
    assert report["coverage"] == 0
    assert report["n_missing_score"] == 0
    assert report["n_excluded_retained_score"] == 1
    assert report["precision"] is None
    assert report["false_positive_rate"] is None
    assert report["confusion"] == dict(tp=0, fp=0, fn=0, tn=0)


@pytest.mark.parametrize("rows,expected", [
    ([], {"precision": None, "recall": None, "false_positive_rate": None, "average_precision": None}),
    ([row(True, 100)], {"precision": 1, "recall": 1, "false_positive_rate": None, "average_precision": 1}),
    ([row(False, 100)], {"precision": 0, "recall": None, "false_positive_rate": 1, "average_precision": None}),
    ([row(False, 0)], {"precision": None, "recall": None, "false_positive_rate": 0, "average_precision": None}),
    ([row(None, 99)], {"precision": None, "recall": None, "false_positive_rate": None, "average_precision": None}),
])
def test_zero_denominators_return_null_and_do_not_fabricate_perfect_or_zero_metrics(rows, expected):
    report = binary_metrics(rows, threshold=50)
    assert {key: report[key] for key in expected} == expected
    if not rows:
        assert report["coverage"] is report["labeled_coverage"] is None
    json.dumps(report, allow_nan=False)


@pytest.mark.parametrize("value", [True, False, "50", -0.1, 100.1, math.inf, -math.inf, math.nan, 10**1000])
def test_invalid_score_and_threshold_are_rejected_even_if_row_is_not_measured(value):
    with pytest.raises(ValueError):
        binary_metrics([row(None, value, "FAILED")], threshold=50)
    with pytest.raises(ValueError):
        binary_metrics([], threshold=value)


@pytest.mark.parametrize("label", [0, 1, "True", "UNKNOWN", [], {}])
def test_binary_truth_is_strict_bool_or_null(label):
    with pytest.raises(ValueError):
        binary_metrics([row(label, 50)], threshold=50)


@pytest.mark.parametrize("bad", [
    {"label": True, "score": 50},
    {"label": True, "score": 50, "status": "SUCCEEDED"},
    {"label": True, "score": 50, "status": None},
    {"label": True, "score": 50, "status": "SUCCESS", "silent_extra": 1},
    [], None,
])
def test_ambiguous_binary_rows_fail_validation(bad):
    with pytest.raises(ValueError):
        binary_metrics([bad], threshold=50)


def test_binary_metrics_do_not_mutate_inputs_or_depend_on_threshold_for_ranking():
    rows = [row(True, 10), row(False, 50), row(None, None, "NOT_RUN")]
    before = deepcopy(rows)
    first = binary_metrics(rows, threshold=0)
    last = binary_metrics(rows, threshold=100)
    assert first["average_precision"] == last["average_precision"] == 0.5
    assert first["confusion"] != last["confusion"]
    assert rows == before


def test_semantic_unknown_failure_and_no_reference_label_have_different_denominators():
    rows = [dict(expected="MATCH", actual="MATCH"),
            dict(expected="MISMATCH", actual="FAILED"),
            dict(expected="MISMATCH", actual="UNKNOWN"),
            dict(expected="UNKNOWN", actual="UNKNOWN"),
            dict(expected="UNKNOWN", actual="NOT_RUN"),
            dict(expected=None, actual="MISMATCH")]
    report = semantic_confusion(rows)
    assert report["n_total"] == 6
    assert report["n_labeled"] == 5
    assert report["n_unknown_label"] == 1
    assert report["n_expected_unknown"] == 2
    assert report["confusion"]["MISMATCH"]["FAILED"] == 1
    assert report["confusion"]["MISMATCH"]["UNKNOWN"] == 1
    assert report["confusion"]["UNKNOWN"]["NOT_RUN"] == 1
    assert sum(sum(columns.values()) for columns in report["confusion"].values()) == 5
    assert report["exact_match_accuracy"] == 2 / 5
    assert report["completed_coverage"] == 4 / 6
    assert report["resolved_coverage"] == 2 / 6
    assert report["actual_counts"]["MISMATCH"] == 1


def test_empty_semantic_report_is_finite_json_with_null_rates():
    report = semantic_confusion([])
    assert report["n_total"] == 0
    assert report["exact_match_accuracy"] is None
    assert report["completed_coverage"] is None
    assert report["resolved_coverage"] is None
    assert len(report["confusion"]) == 3
    assert all(len(columns) == 5 for columns in report["confusion"].values())
    json.dumps(report, allow_nan=False)


@pytest.mark.parametrize("bad", [
    dict(expected="FAILED", actual="MATCH"), dict(expected=True, actual="MATCH"),
    dict(expected="MATCH", actual=None), dict(expected="MATCH", actual="CANCELLED"),
    dict(expected="MATCH", actual="MATCH", score=0), dict(actual="MATCH"), None,
])
def test_semantic_confusion_rejects_missing_ambiguous_or_coerced_fields(bad):
    with pytest.raises(ValueError):
        semantic_confusion([bad])
