import pytest

from copenhagen_fds.contracts.behavior import AnomalyScore
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.personal_evaluation import evaluate_personalized_features


@pytest.mark.parametrize("side,count,cold,scored", [
    ("personal", 3, True, True), ("personal", 4, True, True), ("personal", 4, False, False),
    ("cold", 3, True, False), ("cold", 4, True, True), ("both", 4, True, False),
])
def test_partial_failure_fallback_preserves_error_and_other_features(evaluation_cases, monkeypatch, side, count, cold, scored):
    import copenhagen_fds.cold_scoring as cold_module
    import copenhagen_fds.personal_amount as amount_module

    def fail_personal(*args):
        raise ArithmeticError("injected failure")

    original = cold_module.score_feature

    def fail_cold(raw, reference):
        if raw.feature_id == "amount_minor":
            raise ArithmeticError("injected failure")
        return original(raw, reference)

    if side in {"personal", "both"}:
        monkeypatch.setattr(amount_module, "_calculation", fail_personal)
    if side in {"cold", "both"}:
        monkeypatch.setattr(cold_module, "score_feature", fail_cold)
    profile = evaluation_cases.publication([{"days": d} for d in range(1, count + 1)])
    supplied = evaluation_cases.request(profile=profile, cold=cold)
    report = evaluate_personalized_features(supplied)
    row = report.result.features[0]
    assert (row.combined.status == "SCORED") == scored
    assert report.result.behavior.status == "FAILED"
    assert report.result.behavior.coverage == ("COMPLETE" if scored else "PARTIAL")
    assert all(f.combined.status == "SCORED" for f in report.result.features[1:])
    assert {r.code for r in report.result.behavior.reasons} == {"EVALUATION_FAILED"}
    assert report.validate_for_input(supplied) == report
    if not scored:
        assert row.stage == "UNAVAILABLE" and row.combined.score is None and row.personal_weight is None
        assert not row.combined.bases
    elif side == "personal":
        assert row.stage == "COLD" and row.personal_weight == 0 and row.combined == row.cold
        assert row.personal.status == "FAILED" and row.personal.bases
        assert report.components.features[0].candidate.calculation is None
    else:
        assert row.stage == "PERSONALIZED" and row.cold.status == "FAILED"


@pytest.mark.parametrize("group,feature,attribute", [
    ("amount", "amount_minor", "_score"), ("frequency", "purchase_count_10m", "bounded_upper_score"),
    ("time", "kst_hour", "_score"), ("cold", "amount_minor", "score_feature"),
])
@pytest.mark.parametrize("value", [float("nan"), float("inf"), -1.0, 101.0])
def test_invalid_computation_output_is_failed_not_zero_or_global_crash(evaluation_cases, monkeypatch, group, feature, attribute, value):
    import importlib

    module = importlib.import_module("copenhagen_fds." + ("cold_scoring" if group == "cold" else "personal_" + group))
    invalid = AnomalyScore(value=0.0, unit="BEHAVIOR_ANOMALY_0_100").model_copy(update={"value": value})
    monkeypatch.setattr(module, attribute, lambda *args: invalid)
    report = evaluate_personalized_features(evaluation_cases.request(requested=(feature,)))
    row = report.result.features[0]
    failed = row.cold if group == "cold" else row.personal
    assert failed.status == "FAILED" and failed.score is None
    assert report.result.behavior.status == "FAILED"
    assert row.combined.status == "SCORED"  # The other fully supported component remains usable.


def test_single_frequency_failure_does_not_erase_other_streams(evaluation_cases, monkeypatch):
    import copenhagen_fds.personal_frequency as module

    original = module._calculation

    def fail_one(distribution, policy):
        if distribution.feature_id == "purchase_count_10m":
            raise ValueError("injected computation failure")
        return original(distribution, policy)

    monkeypatch.setattr(module, "_calculation", fail_one)
    report = evaluate_personalized_features(evaluation_cases.request())
    assert report.result.features[1].personal.status == "FAILED"
    assert all(row.personal.status == "SCORED" for i, row in enumerate(report.result.features) if i != 1)
    assert report.result.behavior.coverage == "COMPLETE" and report.result.behavior.status == "FAILED"


def test_restored_failure_cannot_claim_success_even_with_complete_coverage(evaluation_cases, monkeypatch):
    import copenhagen_fds.personal_amount as module

    def fail(*args):
        raise ArithmeticError("injected computation failure")

    monkeypatch.setattr(module, "_calculation", fail)
    data = evaluate_personalized_features(evaluation_cases.request()).model_dump()
    data["result"]["behavior"].update(status="SUCCESS", reasons=[])
    with pytest.raises(ValueError, match="failures cannot be hidden"):
        PersonalizedEvaluationResult.model_validate(data)
