import pytest

from copenhagen_fds.contracts.risk_assessment import RiskBehaviorAssessment
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_assessment import assess_risk_behavior


@pytest.mark.parametrize("kwargs,execution,status,coverage", [
    ({}, "SUCCESS", "SUCCESS", "COMPLETE"),
    ({"states": {"amount_minor": "NOT_COLLECTED"}}, "SUCCESS", "INCOMPLETE", "PARTIAL"),
    ({"cold": False}, "SUCCESS", "INCOMPLETE", "UNAVAILABLE"),
    ({"requested": ("amount_minor",)}, "SUCCESS", "INCOMPLETE", "PARTIAL"),
])
def test_behavior_state_decision_table(risk_cases, kwargs, execution, status, coverage):
    supplied = risk_cases.request(**kwargs)
    result = assess_risk_behavior(supplied)
    assert (result.execution_status, result.status, result.coverage.score_coverage) == (execution, status, coverage)
    assert result.failures == () and result.cold_fallback_features == ()
    assert result.source_reasons == supplied.behavior.result.behavior.reasons
    assert "risk_level" not in type(result).model_fields  # Readiness is not a LOW-risk decision.


def test_not_run_is_distinct_from_executed_but_unavailable(risk_cases):
    data = risk_cases.request().model_dump()
    data["behavior"] = None
    result = assess_risk_behavior(RiskPolicyInput.model_validate(data))
    assert result.status == result.execution_status == "NOT_RUN"
    assert result.evidence == result.failures == result.source_reasons == ()
    assert "BEHAVIOR_NOT_RUN" in result.reasons
    assert not result.coverage.can_aggregate


@pytest.mark.parametrize("side,cold,complete", [
    ("personal", True, True), ("personal", False, False), ("cold", True, True), ("both", True, False),
])
def test_calculation_failure_and_cold_fallback_preserved(risk_cases, monkeypatch, side, cold, complete):
    import copenhagen_fds.cold_scoring as cold_module
    import copenhagen_fds.personal_amount as personal_module

    original = cold_module.score_feature

    def fail_personal(*args):
        raise ArithmeticError("injected transient personal failure")

    def fail_cold(raw, reference):
        if raw.feature_id == "amount_minor":
            raise ArithmeticError("injected transient Cold failure")
        return original(raw, reference)

    with monkeypatch.context() as patch:
        if side in {"personal", "both"}:
            patch.setattr(personal_module, "_calculation", fail_personal)
        if side in {"cold", "both"}:
            patch.setattr(cold_module, "score_feature", fail_cold)
        supplied = risk_cases.request(profile=True, cold=cold)
    # Scorers are restored: recorded failure must not disappear when bound or decoded.
    result = assess_risk_behavior(supplied)
    assert result.status == result.execution_status == "FAILED"
    assert result.coverage.can_aggregate == complete
    assert result.coverage.score_coverage == ("COMPLETE" if complete else "PARTIAL")
    assert "CALCULATION_FAILED" in result.reasons
    assert {f.result.feature_id for f in result.failures} == {"amount_minor"}
    assert all(f.result.status == "FAILED" and f.result.score is None for f in result.failures)
    assert result.source_reasons == supplied.behavior.result.behavior.reasons
    assert tuple(row.detail for row in result.evidence) == supplied.behavior.result.features
    assert all(row.feature.score is not None for row in result.coverage.features[1:])
    if side == "personal" and cold:
        assert result.cold_fallback_features == ("amount_minor",)
        assert result.coverage.features[0].feature.score.value == 0.0
        assert "COLD_FALLBACK_AFTER_FAILURE" in result.reasons
        assert result.evidence[0].detail.stage == "COLD"
    else:
        assert result.cold_fallback_features == ()
    assert RiskBehaviorAssessment.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result


def test_readiness_and_mixture_preserved_without_second_discount(risk_cases, evaluation_cases, monkeypatch):
    profile = evaluation_cases.publication([{"days": day} for day in (1, 2, 3)])
    supplied = risk_cases.request(profile=profile)
    before = supplied.model_dump_json()
    import copenhagen_fds.personal_evaluation as module

    def forbidden(*args):
        pytest.fail("assessment must not rerun scoring")

    monkeypatch.setattr(module, "evaluate_personalized_features", forbidden)
    result = assess_risk_behavior(supplied)
    amount = result.evidence[0]
    assert amount.detail.stage == "TRANSITION" and amount.detail.personal_weight == 0.5
    assert amount.detail.combined.score.value == 50.0  # Cold 0, personal 100: no second 0.5 discount.
    assert amount.readiness == supplied.behavior.components.transition.readiness.features[0]
    assert amount.personal_execution == supplied.behavior.components.features[0]
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("intent", ["MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"])
def test_behavior_readiness_is_independent_of_gpt_policy(risk_cases, intent):
    result = assess_risk_behavior(risk_cases.request(intent_state=intent))
    assert result.status == result.execution_status == "SUCCESS"
    assert result.reasons == ("ALL_SCORES_AVAILABLE",)


def test_roundtrip_and_repeated_assessment_are_identical(risk_cases):
    supplied = risk_cases.request(profile=True)
    first = assess_risk_behavior(supplied)
    restored = RiskBehaviorAssessment.model_validate_json(first.model_dump_json())
    assert restored == first == restored.validate_for_input(supplied)
    assert first.model_dump_json() == assess_risk_behavior(supplied).model_dump_json()


@pytest.mark.parametrize("change", ["success", "erase_failure", "erase_fallback", "erase_diagnostic", "erase_source"])
def test_failure_state_tamper_rejected(risk_cases, monkeypatch, change):
    import copenhagen_fds.personal_amount as module

    with monkeypatch.context() as patch:
        def fail(*args):
            raise ArithmeticError("injected failure")
        patch.setattr(module, "_calculation", fail)
        supplied = risk_cases.request(profile=True)
    data = assess_risk_behavior(supplied).model_dump()
    if change == "success":
        data.update(status="SUCCESS", execution_status="SUCCESS")
    elif change == "erase_failure":
        data["failures"] = ()
    elif change == "erase_fallback":
        data["cold_fallback_features"] = ()
    elif change == "erase_diagnostic":
        data["reasons"] = ("ALL_SCORES_AVAILABLE",)
    else:
        data["source_reasons"] = ()
    with pytest.raises(ValueError):
        RiskBehaviorAssessment.model_validate(data)


def test_binding_rejects_changed_policy_and_readiness_evidence(risk_cases):
    supplied = risk_cases.request(profile=True)
    result = assess_risk_behavior(supplied)
    other = supplied.model_dump()
    other["policy"]["version"] = "different-policy"
    with pytest.raises(ValueError, match="exact source"):
        result.validate_for_input(RiskPolicyInput.model_validate(other))
    data = result.model_dump()
    data["evidence"][0]["readiness"]["thresholds"]["minimum_samples"] = 1
    with pytest.raises(ValueError, match="exact source"):
        RiskBehaviorAssessment.model_validate(data).validate_for_input(supplied)
