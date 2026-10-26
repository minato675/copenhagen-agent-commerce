from datetime import timedelta

import pytest

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput


def test_omitted_policy_clock_preserves_legacy_json_and_hash(risk_cases):
    supplied = risk_cases.request()
    reference = RiskInputReference.from_input(supplied)
    for record in (supplied, reference):
        assert "policy_evaluated_at" not in record.model_dump()
        assert "policy_evaluated_at" not in record.model_dump_json()
        legacy = record.model_dump()
        explicit_null = type(record).model_validate({**legacy, "policy_evaluated_at": None})
        assert explicit_null.model_dump() == legacy
        assert personalization_hash(explicit_null) == personalization_hash(legacy)


def test_live_completion_uses_policy_clock_without_moving_behavior_cutoff(risk_cases):
    supplied = risk_cases.request(profile=True)
    data = supplied.model_dump()
    data["intent"]["metadata"]["evaluated_at"] += timedelta(seconds=2)
    with pytest.raises(ValueError, match="exclusive age limit"):
        RiskPolicyInput.model_validate(data)
    data["policy_evaluated_at"] = supplied.evaluated_at + timedelta(seconds=3)
    live = RiskPolicyInput.model_validate(data)
    assert live.evaluated_at == supplied.evaluated_at
    assert live.scoring_input == supplied.scoring_input
    reference = RiskInputReference.from_input(live)
    assert reference.policy_evaluated_at == live.policy_evaluated_at
    assert reference.input_sha256 != RiskInputReference.from_input(supplied).input_sha256
    assert RiskPolicyInput.model_validate_json(live.model_dump_json()) == live


@pytest.mark.parametrize("age,valid", [(0, True), (299.999999, True), (300, False), (-0.000001, False)])
def test_intent_age_boundaries_apply_at_policy_start(risk_cases, age, valid):
    data = risk_cases.request().model_dump()
    data["policy_evaluated_at"] = data["evaluated_at"] + timedelta(seconds=310)
    data["intent"]["metadata"]["evaluated_at"] = data["policy_evaluated_at"] - timedelta(seconds=age)
    if valid:
        RiskPolicyInput.model_validate(data)
    else:
        with pytest.raises(ValueError, match="exclusive age limit"):
            RiskPolicyInput.model_validate(data)


@pytest.mark.parametrize("delta,valid", [(0, False), (-1, False), (1, True)])
def test_intent_expiry_is_exclusive_at_policy_clock(risk_cases, delta, valid):
    data = risk_cases.request().model_dump()
    data["policy_evaluated_at"] = data["evaluated_at"] + timedelta(seconds=2)
    data["intent"]["metadata"]["valid_until"] = data["policy_evaluated_at"] + timedelta(microseconds=delta)
    if valid:
        RiskPolicyInput.model_validate(data)
    else:
        with pytest.raises(ValueError, match="expired intent"):
            RiskPolicyInput.model_validate(data)


def test_policy_clock_cannot_backdate_or_authorize_behavior_reuse(risk_cases):
    supplied = risk_cases.request()
    for record in (supplied, RiskInputReference.from_input(supplied)):
        data = record.model_dump()
        data["policy_evaluated_at"] = supplied.evaluated_at - timedelta(microseconds=1)
        with pytest.raises(ValueError, match="precede"):
            type(record).model_validate(data)
    data = supplied.model_dump()
    data["evaluated_at"] += timedelta(seconds=1)
    data["policy_evaluated_at"] = data["evaluated_at"]
    with pytest.raises(ValueError, match="point-in-time"):
        RiskPolicyInput.model_validate(data)
