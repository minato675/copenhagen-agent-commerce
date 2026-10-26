"""Independent statistical fixtures for paired reports (S15P21A207-358)."""

from copy import deepcopy
import json
from pathlib import Path

import pytest

from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan
from copenhagen_fds.temporal_benchmark.report import summarize_predictions


@pytest.fixture
def plan():
    settings = Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json"
    initial = BenchmarkPlan.model_validate({"settings": json.loads(settings.read_text(encoding="utf-8"))})
    payload = initial.model_dump(mode="json")
    payload["risk_policy"]["thresholds"] = {"medium_from": 50.0, "high_from": 80.0}
    return BenchmarkPlan.model_validate(payload)


def summary(score, *, stage="PERSONALIZED", behavior_status="SUCCESS", signal="MATCH", outcome_status="SUCCESS"):
    if signal in {"FAILED", "NOT_RUN"}:
        comparison = dict(status=signal, signal=None)
    else:
        comparison = dict(status="SUCCESS", signal=signal)
    if outcome_status == "SUCCESS":
        high = score is not None and score >= 50
        outcome = dict(status="SUCCESS", risk_level="HIGH" if high else "LOW",
                       recommendation="REQUIRE_AUTH" if high else "NO_ADDITIONAL_REVIEW",
                       review_actions=["REQUIRE_AUTH"] if high else [])
    else:
        outcome = dict(status=outcome_status, risk_level="UNKNOWN", recommendation=None,
                       review_actions=["RETRY_EVALUATION"])
    return dict(behavior_score=score, behavior_status=behavior_status, outcome=outcome,
                features=[dict(feature_id="amount_minor", stage=stage, score=score)],
                analysis=dict(comparison=comparison), mode="MOCK")


@pytest.fixture
def sample():
    # These are authored metric observations, not outputs from the implementation under test.
    arms = [
        (summary(80, stage="COLD", signal="MISMATCH"), summary(90, signal="MISMATCH")),
        (summary(80, stage="COLD"), summary(20)),
        (summary(20, stage="COLD", signal="FAILED", outcome_status="FAILED"),
         summary(None, stage="UNAVAILABLE", behavior_status="INCOMPLETE", signal="FAILED", outcome_status="FAILED")),
        (summary(20, stage="COLD", signal="NOT_RUN", outcome_status="INCOMPLETE"),
         summary(10, stage="COLD", signal="NOT_RUN", outcome_status="INCOMPLETE")),
    ]
    rows, truth, semantic = [], [], {}
    specifications = [(True, "amount_spike", "routine-user-private", 35, "MISMATCH"),
                      (False, "normal_high_amount", "routine-user-private", 35, "MATCH"),
                      (True, "combined", "new-user-private", None, "MISMATCH"),
                      (False, "cold_start", "new-user-private", 0, None)]
    for index, (spec, (cold, personal)) in enumerate(zip(specifications, arms, strict=True), start=1):
        label, scenario, user, count, expected = spec
        pid = f"purchase-private-{index}"
        rows.append(dict(purchase_request_id=pid, user_id=user, evaluation_id=f"evaluation-private-{index}",
                         evaluated_at=f"2026-04-0{index}T12:00:00+09:00",
                         arms=dict(cold=cold, personalized=personal),
                         profile_audit=dict(personal=dict(included_count=count, raw_profile="private-profile-payload")),
                         user_text="private-user-original-text"))
        truth.append(dict(purchase_request_id=pid, is_fraud=label, scenario=scenario))
        semantic[pid] = dict(comparison=expected, basis="private-semantic-reference-payload")
    targets = dict(truth=truth, semantic=semantic, selected_purchase_ids=[row["purchase_request_id"] for row in rows],
                   personas=[dict(user_id="routine-user-private", archetype="routine"),
                             dict(user_id="new-user-private", archetype="newcomer"),
                             dict(user_id="not-evaluated-user-private", archetype="night")])
    return rows, targets


def test_paired_report_has_known_global_confusion_coverage_and_delta_answers(sample, plan):
    report = summarize_predictions(*sample, plan)
    cold, personal = report["behavior"]["cold"], report["behavior"]["personalized"]
    assert cold["confusion"] == dict(tp=1, fp=1, fn=1, tn=1)
    assert cold["precision"] == cold["recall"] == cold["false_positive_rate"] == 0.5
    assert cold["average_precision"] == 0.5
    assert cold["coverage"] == 1
    assert personal["confusion"] == dict(tp=1, fp=0, fn=0, tn=2)
    assert personal["precision"] == personal["recall"] == personal["average_precision"] == 1
    assert personal["false_positive_rate"] == 0
    assert personal["coverage"] == personal["labeled_coverage"] == 0.75
    assert report["behavior"]["deltas"] == dict(
        precision=0.5, recall=0.5, false_positive_rate=-0.5,
        average_precision=0.5, coverage=-0.25, labeled_coverage=-0.25)
    assert report["scope"]["n_purchases"] == 4
    assert report["scope"]["n_users"] == 2
    assert report["scope"]["observed_modes"] == ["MOCK"]


def test_paired_scored_uses_the_same_eligible_purchases_in_both_arms(sample, plan):
    report = summarize_predictions(*sample, plan)
    paired = report["behavior"]["paired_scored"]
    assert paired["n_pairs"] == 3
    assert paired["cold"]["confusion"] == dict(tp=1, fp=1, fn=0, tn=1)
    assert paired["cold"]["recall"] == 1
    assert paired["personalized"]["recall"] == 1
    assert paired["deltas"]["recall"] == 0
    assert paired["deltas"]["coverage"] == 0
    assert report["behavior"]["deltas"]["recall"] == 0.5


def test_strata_use_personalized_amount_stage_and_observed_history_without_zero_filling(sample, plan):
    report = summarize_predictions(*sample, plan)
    strata = report["strata"]
    assert {key: value["n_purchases"] for key, value in strata["amount_stage"].items()} == {
        "COLD": 1, "PERSONALIZED": 2, "UNAVAILABLE": 1}
    assert {key: value["n_purchases"] for key, value in strata["history_samples"].items()} == {
        "0": 1, "30_PLUS": 2, "UNKNOWN": 1}
    assert strata["amount_stage"]["PERSONALIZED"]["behavior"]["cold"]["confusion"] == dict(tp=1, fp=1, fn=0, tn=0)
    assert set(strata["archetype"]) == {"routine", "newcomer"}
    assert len(strata["scenario"]) == 4
    for group in strata.values():
        assert sum(value["n_purchases"] for value in group.values()) == 4
    assert strata["scenario"]["cold_start"]["behavior"]["deltas"]["recall"] is None


@pytest.mark.parametrize("count,expected", [(0, "0"), (1, "1_4"), (4, "1_4"), (5, "5_29"), (29, "5_29"), (30, "30_PLUS")])
def test_history_bins_have_explicit_inclusive_boundaries(sample, plan, count, expected):
    rows, targets = sample
    for row in rows:
        row["profile_audit"]["personal"]["included_count"] = count
    report = summarize_predictions(rows, targets, plan)
    assert set(report["strata"]["history_samples"]) == {expected}


def test_semantic_and_final_outcome_failures_do_not_erase_valid_behavior_scores(sample, plan):
    report = summarize_predictions(*sample, plan)
    assert report["behavior"]["cold"]["n_scored_labeled"] == 4
    semantic = report["semantic"]["cold"]
    assert semantic["n_labeled"] == 3
    assert semantic["confusion"]["MISMATCH"]["FAILED"] == 1
    assert semantic["exact_match_accuracy"] == 2 / 3
    assert semantic["actual_counts"]["NOT_RUN"] == 1
    outcomes = report["outcomes"]["cold"]
    assert outcomes["status_counts"] == dict(SUCCESS=2, INCOMPLETE=1, FAILED=1, CANCELLED=0)
    assert outcomes["recommendation_counts"] == dict(NOT_PROVIDED=2, REQUIRE_AUTH=2)
    assert outcomes["review_action_counts"] == dict(REQUIRE_AUTH=2, RETRY_EVALUATION=2)
    assert report["outcomes"]["personalized"]["n_with_review_actions"] == 3


def test_semantic_labels_cannot_change_behavior_metrics_or_become_fraud_labels(sample, plan):
    rows, targets = sample
    before = summarize_predictions(rows, targets, plan)
    for target in targets["semantic"].values():
        target["comparison"] = "UNKNOWN"
    after = summarize_predictions(rows, targets, plan)
    assert after["behavior"] == before["behavior"]
    assert after["strata"] == before["strata"]
    assert after["semantic"] != before["semantic"]


def test_report_is_order_independent_does_not_mutate_inputs_and_never_copies_private_payloads(sample, plan):
    rows, targets = sample
    original = deepcopy(sample)
    report = summarize_predictions(rows, targets, plan)
    assert report == summarize_predictions(list(reversed(rows)), targets, plan)
    assert sample == original
    encoded = json.dumps(report, allow_nan=False)
    for sensitive in ("private-profile-payload", "private-user-original-text", "private-semantic-reference-payload",
                      "purchase-private-", "routine-user-private", "evaluation-private-"):
        assert sensitive not in encoded
    assert report["scope"]["suite"] == "stream"
    assert report["scope"]["data_role"] == "REGRESSION"
    assert any("Mock" in note and "not measured GMS" in note for note in report["limitations"])


def test_empty_report_keeps_null_metrics_and_complete_semantic_matrix(plan):
    report = summarize_predictions([], dict(truth=[], personas=[], semantic={}), plan)
    assert report["scope"]["n_purchases"] == 0
    assert report["behavior"]["cold"]["coverage"] is None
    assert all(value is None for value in report["behavior"]["deltas"].values())
    assert report["scope"]["first_evaluation_at"] is None
    assert report["strata"]["scenario"] == {}
    json.dumps(report, allow_nan=False)


@pytest.mark.parametrize("section", ["predictions", "truth", "personas"])
def test_duplicate_identities_are_rejected_before_join(sample, plan, section):
    rows, targets = sample
    destination = rows if section == "predictions" else targets[section]
    destination.append(deepcopy(destination[0]))
    with pytest.raises(ValueError, match="duplicate"):
        summarize_predictions(rows, targets, plan)


@pytest.mark.parametrize("table,mutation", [("truth", "missing"), ("truth", "extra"), ("semantic", "missing"), ("semantic", "extra")])
def test_targets_must_cover_exactly_the_evaluated_purchases(sample, plan, table, mutation):
    rows, targets = sample
    if table == "truth":
        if mutation == "missing":
            targets[table].pop()
        else:
            targets[table].append({**targets[table][0], "purchase_request_id": "extra"})
    elif mutation == "missing":
        targets[table].pop(rows[0]["purchase_request_id"])
    else:
        targets[table]["extra"] = dict(comparison="MATCH")
    with pytest.raises(ValueError, match="coverage"):
        summarize_predictions(rows, targets, plan)


@pytest.mark.parametrize("bad_arms", [{}, {"cold": {}}, {"cold": {}, "personalized": {}, "extra": {}}])
def test_every_purchase_requires_both_and_only_the_paired_arms(sample, plan, bad_arms):
    rows, targets = sample
    rows[0]["arms"] = bad_arms
    with pytest.raises(ValueError, match="exactly cold and personalized"):
        summarize_predictions(rows, targets, plan)


@pytest.mark.parametrize("count", [-1, True, "5", 5.0])
def test_invalid_history_count_is_not_a_valid_stratum(sample, plan, count):
    rows, targets = sample
    rows[0]["profile_audit"]["personal"]["included_count"] = count
    with pytest.raises(ValueError, match="included history count"):
        summarize_predictions(rows, targets, plan)


def test_missing_persona_is_rejected_but_unevaluated_personas_are_allowed(sample, plan):
    rows, targets = sample
    assert summarize_predictions(rows, targets, plan)["scope"]["n_users"] == 2
    targets["personas"] = targets["personas"][1:]
    with pytest.raises(ValueError, match="persona missing"):
        summarize_predictions(rows, targets, plan)


def test_retained_semantic_signal_cannot_disguise_failed_execution(sample, plan):
    rows, targets = sample
    rows[0]["arms"]["cold"]["analysis"]["comparison"] = dict(status="FAILED", signal="MATCH")
    with pytest.raises(ValueError, match="cannot supply a successful signal"):
        summarize_predictions(rows, targets, plan)


def test_duplicate_evaluation_ids_and_selected_purchase_ids_are_rejected(sample, plan):
    rows, targets = sample
    rows[1]["evaluation_id"] = rows[0]["evaluation_id"]
    with pytest.raises(ValueError, match="duplicate evaluation"):
        summarize_predictions(rows, targets, plan)
    rows[1]["evaluation_id"] = "unique-again"
    targets["selected_purchase_ids"].append(targets["selected_purchase_ids"][0])
    with pytest.raises(ValueError, match="selected purchase coverage"):
        summarize_predictions(rows, targets, plan)


def test_missing_behavior_status_is_not_inferred_from_overall_status(sample, plan):
    rows, targets = sample
    del rows[0]["arms"]["cold"]["behavior_status"]
    with pytest.raises(ValueError, match="independent behavior score and status"):
        summarize_predictions(rows, targets, plan)


def test_missing_amount_feature_is_not_reported_as_a_cold_stage(sample, plan):
    rows, targets = sample
    rows[0]["arms"]["personalized"]["features"] = []
    report = summarize_predictions(rows, targets, plan)
    assert report["strata"]["amount_stage"]["NOT_PRODUCED"]["n_purchases"] == 1


@pytest.mark.parametrize("comparison", [None, dict(status="NOT_RUN", signal=None)])
def test_failed_extraction_is_counted_as_failed_instead_of_skipped_comparison(sample, plan, comparison):
    rows, targets = sample
    first = rows[0]["arms"]["cold"]
    first["analysis"] = dict(extraction=dict(status="FAILED", signal=None), comparison=comparison)
    first["outcome"] = dict(status="FAILED", risk_level="UNKNOWN", recommendation=None, review_actions=["RETRY_EVALUATION"])
    report = summarize_predictions(rows, targets, plan)
    semantic = report["semantic"]["cold"]
    assert semantic["confusion"]["MISMATCH"]["FAILED"] == 2
    assert semantic["confusion"]["MISMATCH"]["NOT_RUN"] == 0
    assert semantic["actual_counts"]["FAILED"] == 2
    assert semantic["actual_counts"]["NOT_RUN"] == 1
    assert report["behavior"]["cold"]["n_scored_labeled"] == 4


@pytest.mark.parametrize("extraction_status,comparison,actual", [
    ("NOT_RUN", None, "NOT_RUN"),
    ("NOT_RUN", dict(status="NOT_RUN", signal=None), "NOT_RUN"),
    ("SUCCESS", dict(status="FAILED", signal=None), "FAILED"),
    ("SUCCESS", dict(status="SUCCESS", signal="UNKNOWN"), "UNKNOWN"),
])
def test_semantic_projection_preserves_skipped_failed_and_unknown_as_distinct_states(sample, plan, extraction_status, comparison, actual):
    rows, targets = sample
    rows[0]["arms"]["cold"]["analysis"] = dict(extraction=dict(status=extraction_status), comparison=comparison)
    report = summarize_predictions(rows, targets, plan)
    assert report["semantic"]["cold"]["confusion"]["MISMATCH"][actual] >= 1
    assert report["semantic"]["cold"]["n_labeled"] == 3


@pytest.mark.parametrize("extraction_status", ["FAILED", "NOT_RUN"])
@pytest.mark.parametrize("comparison", [dict(status="SUCCESS", signal="MATCH"), dict(status="FAILED", signal=None)])
def test_downstream_execution_cannot_follow_failed_or_skipped_extraction(sample, plan, extraction_status, comparison):
    rows, targets = sample
    rows[0]["arms"]["cold"]["analysis"] = dict(extraction=dict(status=extraction_status), comparison=comparison)
    with pytest.raises(ValueError, match="requires successful extraction"):
        summarize_predictions(rows, targets, plan)


@pytest.mark.parametrize("extraction", [[], {}, dict(status=True), dict(status=[]), dict(status="INVALID")])
def test_malformed_extraction_envelopes_raise_contract_error(sample, plan, extraction):
    rows, targets = sample
    rows[0]["arms"]["cold"]["analysis"]["extraction"] = extraction
    with pytest.raises(ValueError, match="unsupported extraction execution status"):
        summarize_predictions(rows, targets, plan)


@pytest.mark.parametrize("comparison", [dict(status=[], signal=None), dict(status=True, signal=None), dict(status="SUCCESS", signal=[])])
def test_malformed_comparison_values_raise_contract_error_without_implicit_coercion(sample, plan, comparison):
    rows, targets = sample
    rows[0]["arms"]["cold"]["analysis"]["comparison"] = comparison
    with pytest.raises(ValueError):
        summarize_predictions(rows, targets, plan)
