import math

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_aggregation import RiskAggregationResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_aggregation import aggregate_risk_behavior


@pytest.fixture
def hand_input(risk_cases):
    """Real Cold scoring yields 80 / (10,60,60,20,30,40) / (20,40)."""
    values = dict(purchase_count_10m=1, purchase_count_1h=6, purchase_count_24h=6,
                  attempt_count_10m=2, attempt_count_1h=3, attempt_count_24h=4,
                  kst_hour=2, kst_day_of_week=2)
    parameters = {name: dict(center=0.0, scale=10.0) for name in values}
    parameters["amount_minor"] = dict(center=2000.0, scale=10000.0)
    parameters["kst_day_of_week"]["scale"] = 5.0
    return risk_cases.request(values=values, cold_parameters=parameters)


def test_hand_calculated_total_and_feature_contributions(hand_input):
    result = aggregate_risk_behavior(hand_input)
    assert [g.score.value for g in result.groups] == [80.0, 60.0, 40.0]
    assert [g.weighted_points for g in result.groups] == [32.0, 21.0, 10.0]
    assert result.behavior_score.value == 63.0
    assert math.fsum(f.weighted_points for g in result.groups for f in g.features) == 63.0
    assert [f.weighted_points for f in result.groups[1].features] == [0.0, 21.0, 0.0, 0.0, 0.0, 0.0]
    assert "risk_level" not in type(result).model_fields


def test_custom_weights_used_without_normalization(hand_input):
    data = hand_input.model_dump()
    for group, weight in zip(data["policy"]["groups"], (0.25, 0.25, 0.5)):
        group["weight"] = weight
    result = aggregate_risk_behavior(RiskPolicyInput.model_validate(data))
    assert [g.weighted_points for g in result.groups] == [20.0, 15.0, 20.0]
    assert result.behavior_score.value == 55.0


@pytest.mark.parametrize("maximum,tolerance", [(False, 0.0), (True, 0.0), (True, 5e-13)])
def test_total_bounds_zero_and_hundred(risk_cases, maximum, tolerance):
    options = {}
    if maximum:
        names = [f.feature_id for g in risk_cases.policy().groups for f in g.features]
        options = dict(values={name: 1 for name in names if name != "amount_minor"},
            cold_parameters={name: dict(center=0.0, scale=1.0) for name in names})
    data = risk_cases.request(**options).model_dump()
    data["policy"]["groups"][2]["weight"] += tolerance
    result = aggregate_risk_behavior(RiskPolicyInput.model_validate(data))
    assert result.behavior_score.value == (100.0 if maximum else 0.0)
    assert all(g.score.value == (100.0 if maximum else 0.0) for g in result.groups)
    if tolerance:
        assert math.fsum(g.weighted_points for g in result.groups) > 100.0
        assert result.groups[2].weight == 0.25 + tolerance


@pytest.mark.parametrize("kwargs,missing_group", [
    ({"states": {"amount_minor": "NOT_COLLECTED"}}, 0),
    ({"states": {"purchase_count_24h": "NOT_APPLICABLE"}}, 1),
    ({"states": {"kst_hour": "INSUFFICIENT_HISTORY"}}, 2),
])
def test_missing_score_preserves_other_group_and_feature_evidence(risk_cases, kwargs, missing_group):
    supplied = risk_cases.request(**kwargs)
    result = aggregate_risk_behavior(supplied)
    assert result.behavior_score is None and result.assessment.status == "INCOMPLETE"
    assert result.groups[missing_group].score is None
    for i, group in enumerate(result.groups):
        assert (group.weighted_points is None) == (i == missing_group)
    originals = supplied.behavior.result.behavior.features
    assert tuple(f.feature for g in result.groups for f in g.features) == originals


def test_optional_missing_and_omitted_request_cannot_produce_total(risk_cases):
    data = risk_cases.request(states={"kst_day_of_week": "NOT_COLLECTED"}).model_dump()
    data["policy"]["groups"][2]["features"][1]["required"] = False
    optional = aggregate_risk_behavior(RiskPolicyInput.model_validate(data))
    assert optional.assessment.coverage.evaluation_scope == "FULL"
    assert optional.behavior_score is None and optional.groups[2].score is None
    omitted = aggregate_risk_behavior(risk_cases.request(requested=("amount_minor",)))
    assert omitted.behavior_score is None and len(omitted.assessment.coverage.unrequested_features) == 8
    assert omitted.groups[0].score.value == 0.0
    assert all(f.feature.status == "NOT_RUN" for g in omitted.groups[1:] for f in g.features)


def test_missing_behavior_has_no_fabricated_zero(risk_cases):
    supplied = risk_cases.request().model_copy(update={"behavior": None})
    result = aggregate_risk_behavior(supplied)
    assert result.behavior_score is None and result.assessment.status == "NOT_RUN"
    assert all(g.score is g.weighted_points is None for g in result.groups)


def test_cold_fallback_failure_survives_aggregation_and_roundtrip(risk_cases, monkeypatch):
    import copenhagen_fds.personal_amount as module

    with monkeypatch.context() as patch:
        def fail(*args):
            raise ArithmeticError("injected transient failure")
        patch.setattr(module, "_calculation", fail)
        supplied = risk_cases.request(profile=True)
    result = aggregate_risk_behavior(supplied)
    assert result.behavior_score is not None
    assert result.assessment.status == result.assessment.execution_status == "FAILED"
    assert result.assessment.cold_fallback_features == ("amount_minor",)
    assert result.assessment.failures[0].result.status == "FAILED"
    assert RiskAggregationResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result


def test_transition_weights_preserved_without_rescoring_or_second_discount(risk_cases, evaluation_cases, monkeypatch):
    import copenhagen_fds.personal_evaluation as module

    profile = evaluation_cases.publication([{"days": day} for day in (1, 2, 3)])
    supplied = risk_cases.request(profile=profile)
    before = supplied.model_dump_json()

    def forbidden(*args):
        pytest.fail("aggregation cannot rerun the feature scorer")

    monkeypatch.setattr(module, "evaluate_personalized_features", forbidden)
    result = aggregate_risk_behavior(supplied)
    assert result.assessment.evidence[0].detail.personal_weight == 0.5
    assert result.groups[0].score.value == 50.0
    assert result.groups[0].weighted_points == 20.0
    assert result.assessment.evidence[0].readiness == supplied.behavior.components.transition.readiness.features[0]
    assert supplied.model_dump_json() == before


def test_roundtrip_and_repeated_result_hash(hand_input):
    first = aggregate_risk_behavior(hand_input)
    decoded_input = RiskPolicyInput.model_validate_json(hand_input.model_dump_json())
    assert aggregate_risk_behavior(decoded_input) == first
    assert RiskAggregationResult.model_validate_json(first.model_dump_json()).validate_for_input(hand_input) == first


def test_request_array_order_preserves_calculation_hash_but_binds_exact_source(risk_cases):
    names = tuple(f.feature_id for g in risk_cases.policy().groups for f in g.features)
    first_input = risk_cases.request(requested=names, values={"purchase_count_1h": 1, "purchase_count_24h": 1})
    reversed_input = risk_cases.request(requested=tuple(reversed(names)),
                                        values={"purchase_count_1h": 1, "purchase_count_24h": 1})
    first, reordered = map(aggregate_risk_behavior, (first_input, reversed_input))
    assert first.groups == reordered.groups and first.behavior_score == reordered.behavior_score
    assert first.aggregation_sha256 == reordered.aggregation_sha256
    assert first.assessment.coverage.input_reference != reordered.assessment.coverage.input_reference
    with pytest.raises(ValueError, match="exact source"):
        first.validate_for_input(reversed_input)


def test_different_policy_content_changes_hash_and_binding(hand_input):
    first = aggregate_risk_behavior(hand_input)
    data = hand_input.model_dump()
    data["policy"]["version"] = "another-policy-v1"
    other_input = RiskPolicyInput.model_validate(data)
    other = aggregate_risk_behavior(other_input)
    assert first.groups == other.groups and first.aggregation_sha256 != other.aggregation_sha256
    with pytest.raises(ValueError):
        first.validate_for_input(other_input)


@pytest.mark.parametrize("state", ["MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"])
def test_gpt_signal_does_not_modify_behavior_sum_or_calculation_hash(risk_cases, state):
    expected = aggregate_risk_behavior(risk_cases.request())
    result = aggregate_risk_behavior(risk_cases.request(intent_state=state))
    assert result.groups == expected.groups and result.behavior_score == expected.behavior_score
    assert result.aggregation_sha256 == expected.aggregation_sha256


@pytest.mark.parametrize("change", ["total", "weight", "group_order", "hash", "version", "tie", "evidence"])
def test_result_tamper_rejected_even_with_updated_hash(hand_input, change):
    data = aggregate_risk_behavior(hand_input).model_dump()
    if change == "total":
        data["behavior_score"]["value"] = 62.0
    elif change == "weight":
        data["groups"][0]["weight"] = 0.5
    elif change == "group_order":
        data["groups"] = tuple(reversed(data["groups"]))
    elif change == "version":
        data["aggregation_version"] = "unsupported-v2"
    elif change == "tie":
        data["groups"][1]["features"][1]["weighted_points"] = 0.0
        data["groups"][1]["features"][2]["weighted_points"] = 21.0
    elif change == "evidence":
        data["groups"][0]["features"][0]["feature"]["bases"][0]["policy_version"] = "altered-basis"
    data["aggregation_sha256"] = personalization_hash(dict(aggregation_version=data["aggregation_version"],
        policy=data["assessment"]["coverage"]["policy"], groups=data["groups"], behavior_score=data["behavior_score"]))
    if change == "hash":
        data["aggregation_sha256"] = "0" * 64
    with pytest.raises(ValueError):
        RiskAggregationResult.model_validate(data)


@pytest.mark.parametrize("value", [-1.0, 101.0, float("nan"), float("inf")])
def test_invalid_total_values_rejected(hand_input, value):
    data = aggregate_risk_behavior(hand_input).model_dump()
    data["behavior_score"]["value"] = value
    with pytest.raises(ValueError):
        RiskAggregationResult.model_validate(data)


def test_invalid_weights_not_silently_normalized(hand_input):
    data = hand_input.model_dump()
    data["policy"]["groups"][0]["weight"] = 0.5
    with pytest.raises(ValueError, match="sum to one"):
        RiskPolicyInput.model_validate(data)


def test_unchecked_result_tamper_cannot_bypass_binding(hand_input):
    result = aggregate_risk_behavior(hand_input).model_copy(update={"behavior_score": None})
    with pytest.raises(ValueError):
        result.validate_for_input(hand_input)
