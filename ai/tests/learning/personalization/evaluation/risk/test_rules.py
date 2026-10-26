from itertools import permutations

import pytest

from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_rules import RiskRuleResult
from copenhagen_fds.risk_aggregation import aggregate_risk_behavior
from copenhagen_fds.risk_rules import evaluate_risk_rules


@pytest.mark.parametrize("group", ["AMOUNT", "FREQUENCY"])
def test_strong_signal_dilution_preserves_ten_points_and_adds_high_floor(risk_cases, rule_cases, group):
    options = dict(cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)}) if group == "AMOUNT" else (
        dict(values={"purchase_count_10m": 1}))
    data = rule_cases.configure(risk_cases.request(**options), rule_cases.rule(**{group: 90.0})).model_dump()
    for setting, weight in zip(data["policy"]["groups"], (0.1, 0.4, 0.5) if group == "AMOUNT" else (0.4, 0.1, 0.5)):
        setting["weight"] = weight
    supplied = RiskPolicyInput.model_validate(data)
    result = evaluate_risk_rules(supplied)
    assert result.aggregation.behavior_score.value == 10.0
    assert result.minimum_risk == "HIGH" and result.checks[0].status == "MATCHED"
    assert result.rule_evidence[0].observations[0].score.value == 100.0
    assert result.aggregation == aggregate_risk_behavior(supplied)
    assert "risk_level" not in type(result).model_fields


@pytest.mark.parametrize("frequency,expected", [(3, "HIGH"), (2, None)])
def test_concurrent_signal_requires_both_group_thresholds(risk_cases, rule_cases, frequency, expected):
    supplied = risk_cases.request(values={"purchase_count_10m": frequency}, cold_parameters={
        "amount_minor": dict(center=4000.0, scale=10000.0), "purchase_count_10m": dict(center=0.0, scale=5.0)})
    supplied = rule_cases.configure(supplied, rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0))
    result = evaluate_risk_rules(supplied)
    assert result.minimum_risk == expected
    assert result.aggregation.behavior_score.value == (45.0 if frequency == 3 else 38.0)
    if expected:
        assert [row.score.value for row in result.rule_evidence[0].observations] == [60.0, 60.0]


@pytest.mark.parametrize("stage", ["COLD", "TRANSITION", "PERSONALIZED"])
def test_feature_stages_use_final_score_without_another_discount(risk_cases, rule_cases, evaluation_cases, stage):
    if stage == "COLD":
        supplied = risk_cases.request(cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)})
    else:
        profile = True if stage == "PERSONALIZED" else evaluation_cases.publication([{"days": d} for d in (1, 2, 3)])
        supplied = risk_cases.request(profile=profile)
    supplied = rule_cases.configure(supplied, rule_cases.rule(AMOUNT=50.0))
    result = evaluate_risk_rules(supplied)
    original = supplied.behavior.result.features[0]
    assert original.stage == stage
    assert original.combined.score.value == (50.0 if stage == "TRANSITION" else 100.0)
    assert result.minimum_risk == "HIGH"
    assert result.checks[0].observations[0].score == original.combined.score
    assert result.aggregation.assessment.evidence[0].detail == original


def test_overlap_priority_and_equal_high_floors_are_not_added(risk_cases, rule_cases):
    supplied = risk_cases.request(values={"purchase_count_10m": 1},
        cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)})
    rules = [rule_cases.rule(rule_id="c-high", AMOUNT=90.0),
             rule_cases.rule(rule_id="a-medium", floor="MEDIUM", AMOUNT=60.0, FREQUENCY=60.0),
             rule_cases.rule(rule_id="b-high", AMOUNT=90.0)]
    observed = []
    for ordering in permutations(rules):
        configured = rule_cases.configure(supplied, *ordering)
        result = evaluate_risk_rules(configured)
        assert result.minimum_risk == "HIGH"
        assert result.decisive_rule_ids == ("b-high", "c-high")
        assert [check.rule.rule_id for check in result.checks] == ["a-medium", "b-high", "c-high"]
        assert [reason.rule_id for reason in result.reasons] == ["a-medium", "b-high", "c-high"]
        assert len(result.rule_evidence) == 3
        assert result.aggregation.behavior_score.value == 75.0
        assert result.aggregation == aggregate_risk_behavior(configured)
        observed.append((result.checks, result.rule_evidence, result.minimum_risk, result.decisive_rule_ids))
    assert all(row == observed[0] for row in observed)


def test_duplicate_rule_id_rejected(risk_cases, rule_cases):
    rule = rule_cases.rule(AMOUNT=60.0)
    with pytest.raises(ValueError, match="IDs must be unique"):
        rule_cases.configure(risk_cases.request(), rule, rule)


@pytest.mark.parametrize("configured", [False, True])
def test_no_matched_rule_returns_no_floor_not_low(risk_cases, rule_cases, configured):
    rules = [rule_cases.rule(AMOUNT=90.0)] if configured else []
    result = evaluate_risk_rules(rule_cases.configure(risk_cases.request(), *rules))
    assert result.minimum_risk is None
    assert result.decisive_rule_ids == result.rule_evidence == result.reasons == ()
    assert len(result.checks) == int(configured)


@pytest.mark.parametrize("missing,expected_status,floor", [
    ("amount_minor", "UNAVAILABLE", None), ("purchase_count_24h", "UNAVAILABLE", None),
    ("kst_hour", "MATCHED", "HIGH"),
])
def test_missing_groups_preserve_partial_result_without_inventing_risk(risk_cases, rule_cases, missing, expected_status, floor):
    supplied = risk_cases.request(states={missing: "NOT_COLLECTED"}, values={"purchase_count_10m": 1},
        cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)})
    supplied = rule_cases.configure(supplied, rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0))
    result = evaluate_risk_rules(supplied)
    assert result.checks[0].status == expected_status and result.minimum_risk == floor
    assert result.aggregation.behavior_score is None
    assert result.aggregation.assessment.status == "INCOMPLETE"
    assert result.aggregation == aggregate_risk_behavior(supplied)


def test_missing_execution_does_not_match_rules(risk_cases, rule_cases):
    supplied = rule_cases.configure(risk_cases.request(), rule_cases.rule(AMOUNT=90.0))
    supplied = supplied.model_copy(update={"behavior": None})
    result = evaluate_risk_rules(supplied)
    assert result.checks[0].status == "UNAVAILABLE" and result.minimum_risk is None
    assert result.aggregation.assessment.status == "NOT_RUN"


@pytest.mark.parametrize("side,cold_high,status,floor", [
    ("personal", True, "MATCHED", "HIGH"), ("personal", False, "NOT_MET", None),
    ("both", True, "UNAVAILABLE", None), ("cold", True, "MATCHED", "HIGH"),
])
def test_calculation_failure_and_fallback_preserved(risk_cases, rule_cases, monkeypatch, side, cold_high, status, floor):
    import copenhagen_fds.personal_amount as personal
    import copenhagen_fds.cold_scoring as cold

    original_cold = cold.score_feature

    def fail(*args):
        raise ArithmeticError("injected transient failure")

    def fail_amount(raw, reference):
        return fail() if raw.feature_id == "amount_minor" else original_cold(raw, reference)

    with monkeypatch.context() as patch:
        if side in {"personal", "both"}:
            patch.setattr(personal, "_calculation", fail)
        if side in {"cold", "both"}:
            patch.setattr(cold, "score_feature", fail_amount)
        supplied = risk_cases.request(profile=True, cold_parameters={
            "amount_minor": dict(center=0.0 if cold_high else 10000.0, scale=10000.0)})
    supplied = rule_cases.configure(supplied, rule_cases.rule(AMOUNT=90.0))
    before = supplied.model_dump_json()
    result = evaluate_risk_rules(supplied)
    assert result.checks[0].status == status and result.minimum_risk == floor
    assert result.aggregation.assessment.status == "FAILED"
    assert result.aggregation.assessment.failures
    assert result.aggregation == aggregate_risk_behavior(supplied)
    assert (result.aggregation.behavior_score is None) == (side == "both")
    assert RiskRuleResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    assert supplied.model_dump_json() == before


def test_roundtrip_and_preserved_input_without_scorer_calls(risk_cases, rule_cases, monkeypatch):
    import copenhagen_fds.personal_evaluation as module

    supplied = rule_cases.configure(risk_cases.request(), rule_cases.rule(AMOUNT=90.0))

    def forbidden(*args):
        pytest.fail("rule evaluation cannot invoke the feature scorer")

    monkeypatch.setattr(module, "evaluate_personalized_features", forbidden)
    first = evaluate_risk_rules(supplied)
    assert evaluate_risk_rules(RiskPolicyInput.model_validate_json(supplied.model_dump_json())) == first
    assert RiskRuleResult.model_validate_json(first.model_dump_json()).validate_for_input(supplied) == first


@pytest.mark.parametrize("change", ["floor", "winners", "evidence", "reasons", "checks", "observation", "version"])
def test_resolution_tamper_rejected(risk_cases, rule_cases, change):
    supplied = rule_cases.configure(risk_cases.request(cold_parameters={
        "amount_minor": dict(center=0.0, scale=10000.0)}), rule_cases.rule(AMOUNT=90.0))
    data = evaluate_risk_rules(supplied).model_dump()
    if change == "floor":
        data["minimum_risk"] = "MEDIUM"
    elif change == "winners":
        data["decisive_rule_ids"] = ()
    elif change == "evidence":
        data["rule_evidence"] = data["rule_evidence"] * 2
    elif change == "reasons":
        data["reasons"] = ()
    elif change == "checks":
        data["checks"] = ()
    elif change == "observation":
        data["checks"][0]["observations"][0]["score"]["value"] = 99.0
    else:
        data["evaluation_version"] = "unsupported-v2"
    with pytest.raises(ValueError):
        RiskRuleResult.model_validate(data)


def test_wrong_policy_binding_and_unchecked_result_tamper_rejected(risk_cases, rule_cases):
    supplied = risk_cases.request()
    result = evaluate_risk_rules(supplied)
    changed = rule_cases.configure(supplied, rule_cases.rule(AMOUNT=80.0))
    with pytest.raises(ValueError):
        result.validate_for_input(changed)
    with pytest.raises(ValueError):
        result.model_copy(update={"minimum_risk": "HIGH"}).validate_for_input(supplied)
