from datetime import timedelta

import pytest

from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput
from copenhagen_fds.contracts.risk_policy import RiskPolicyConfig


def test_json_roundtrip_and_exact_profile_binding(risk_cases):
    supplied = risk_cases.request(profile=True)
    restored = RiskPolicyInput.model_validate_json(supplied.model_dump_json())
    assert restored == supplied
    assert RiskInputReference.from_input(restored) == RiskInputReference.from_input(supplied)
    data = supplied.model_dump()
    data["behavior"]["result"]["profiles"][0]["context"]["policy_state_version"] = "changed-policy-state"
    with pytest.raises(ValueError):
        RiskPolicyInput.model_validate(data)


@pytest.mark.parametrize("field", ["evaluation_id", "user_id", "purchase_request_id", "intent_version",
                                   "snapshot_id", "version", "snapshot_hash", "trace_id"])
def test_wrong_target_and_trace_rejected(risk_cases, field):
    data = risk_cases.request().model_dump()
    metadata = data["intent"]["metadata"]
    target = metadata["target"]
    if field == "trace_id":
        metadata[field] = "other-trace"
    elif field in {"snapshot_id", "version", "snapshot_hash"}:
        target["order"][field] = "b" * 64 if field == "snapshot_hash" else "changed"
    else:
        target[field] = "different-target"
    with pytest.raises(ValueError):
        RiskPolicyInput.model_validate(data)


def test_changed_order_content_rejected_even_with_unchanged_reference(risk_cases):
    data = risk_cases.request().model_dump()
    data["request"]["order"]["orders"][0]["items"][0]["name"] = "changed product"
    with pytest.raises(ValueError, match="exact purchase and order"):
        RiskPolicyInput.model_validate(data)


@pytest.mark.parametrize("offset,valid", [(0, True), (-299, True), (-300, False), (1, False)])
def test_intent_time_boundary(risk_cases, offset, valid):
    data = risk_cases.request().model_dump()
    data["intent"]["metadata"]["evaluated_at"] += timedelta(seconds=offset)
    if valid:
        RiskPolicyInput.model_validate(data)
    else:
        with pytest.raises(ValueError, match="exclusive age limit"):
            RiskPolicyInput.model_validate(data)


def test_expired_intent_and_reused_behavior_rejected(risk_cases):
    supplied = risk_cases.request()
    data = supplied.model_dump()
    data["intent"]["metadata"]["evaluated_at"] -= timedelta(seconds=10)
    data["intent"]["metadata"]["valid_until"] = supplied.evaluated_at
    with pytest.raises(ValueError, match="expired intent"):
        RiskPolicyInput.model_validate(data)
    data = supplied.model_dump()
    data["evaluated_at"] += timedelta(seconds=1)
    with pytest.raises(ValueError, match="point-in-time"):
        RiskPolicyInput.model_validate(data)


@pytest.mark.parametrize("field", ["schema_version", "score_scale_version", "feature_schema_version", "validation_status"])
def test_unsupported_policy_versions_rejected(risk_cases, field):
    data = risk_cases.policy().model_dump()
    data[field] = "unsupported-v999"
    with pytest.raises(ValueError):
        RiskPolicyConfig.model_validate(data)


@pytest.mark.parametrize("field", ["score_scale_version", "transition_policy_version", "score_version"])
def test_unsupported_personal_score_versions_rejected(risk_cases, field):
    data = risk_cases.request().model_dump()
    config = data["scoring_input"]["config"]
    if field == "score_version":
        config["features"][0][field] = "unsupported"
    else:
        config[field] = "unsupported"
    with pytest.raises(ValueError, match="supported"):
        RiskPolicyInput.model_validate(data)


@pytest.mark.parametrize("weight", [-0.1, 0, 1.1, 0.5, float("nan"), float("inf"), True])
def test_invalid_weights_rejected_without_normalization(risk_cases, weight):
    data = risk_cases.policy().model_dump()
    data["groups"][0]["weight"] = weight
    with pytest.raises(ValueError):
        RiskPolicyConfig.model_validate(data)


@pytest.mark.parametrize("medium,high", [(0, 70), (70, 70), (80, 70), (30, 101), (float("nan"), 70)])
def test_invalid_thresholds_rejected(risk_cases, medium, high):
    data = risk_cases.policy().model_dump()
    data["thresholds"] = dict(medium_from=medium, high_from=high)
    with pytest.raises(ValueError):
        RiskPolicyConfig.model_validate(data)


@pytest.mark.parametrize("change", ["duplicate_group", "wrong_feature", "duplicate_rule", "duplicate_condition", "all_optional"])
def test_invalid_group_and_rule_structure_rejected(risk_cases, change):
    data = risk_cases.policy().model_dump()
    if change == "duplicate_group":
        data["groups"] = (data["groups"][0],) * 3
    elif change == "wrong_feature":
        data["groups"][0]["features"][0]["feature_id"] = "purchase_count_10m"
    elif change == "duplicate_rule":
        data["floor_rules"] *= 2
    elif change == "duplicate_condition":
        data["floor_rules"][0]["conditions"] *= 2
    else:
        for group in data["groups"]:
            for feature in group["features"]:
                feature["required"] = False
    with pytest.raises(ValueError):
        RiskPolicyConfig.model_validate(data)


@pytest.mark.parametrize("intent_state", ["MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"])
def test_intent_execution_and_meaning_preserved_without_sift(risk_cases, intent_state):
    supplied = risk_cases.request(intent_state=intent_state)
    assert "sift" not in type(supplied).model_fields
    assert supplied.intent.status == (intent_state if intent_state in {"FAILED", "NOT_RUN"} else "SUCCESS")
    data = supplied.model_dump()
    data["behavior"] = None
    assert RiskPolicyInput.model_validate(data).behavior is None


def test_recorded_failure_is_not_recomputed_on_binding(risk_cases, monkeypatch):
    import copenhagen_fds.personal_amount as module

    with monkeypatch.context() as patch:
        def fail(*args):
            raise ArithmeticError("injected transient error")
        patch.setattr(module, "_calculation", fail)
        supplied = risk_cases.request(profile=True)
    assert supplied.behavior.result.behavior.status == "FAILED"
    assert supplied.behavior.result.behavior.coverage == "COMPLETE"
    assert RiskPolicyInput.model_validate_json(supplied.model_dump_json()) == supplied
