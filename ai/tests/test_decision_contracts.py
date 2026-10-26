"""Overall result consistency is checked without selecting a risk policy."""

from copy import deepcopy
import json

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.decisions import FraudDecision
from copenhagen_fds.contracts.statuses import DecisionStatus, FdsRecommendation, RiskLevel


def fail_sift(payload, status="FAILED"):
    sift = payload["sift"]
    sift["status"] = status
    sift["score"] = None
    sift["metadata"]["valid_until"] = None
    if status == "NOT_RUN":
        sift["metadata"]["evaluated_at"] = None
    sift["reasons"] = [{"code": "TIMEOUT" if status == "FAILED" else "NOT_EXECUTED", "source": "MOCK"}]


def unresolved(payload, status="INCOMPLETE"):
    payload.update(status=status, risk_level="UNKNOWN", recommendation=None)
    payload["metadata"]["valid_until"] = None
    payload["reasons"] = [{"code": "REQUIRED_EVALUATION_UNAVAILABLE", "source": "FDS"}]
    payload["review_actions"] = ["RETRY_EVALUATION"]


def test_complete_result_preserves_references_and_recommendation(decision_payload):
    result = FraudDecision.model_validate(decision_payload)
    assert result.status == DecisionStatus.SUCCESS
    assert result.recommendation == FdsRecommendation.ALLOW
    assert result.metadata.target.order.version == "2"
    assert result.metadata.target.intent_version == "intent-v1"
    assert result.metadata.policy_version == "policy-v1"
    assert FraudDecision.model_validate_json(json.dumps(decision_payload)) == result
    assert FraudDecision.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("status", ["FAILED", "NOT_RUN"])
def test_required_failed_or_missing_evaluation_cannot_be_success(decision_payload, status):
    fail_sift(decision_payload, status)
    with pytest.raises(ValidationError, match="required evaluations"):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("status", ["FAILED", "NOT_RUN"])
def test_incomplete_result_preserves_unavailable_score(decision_payload, status):
    fail_sift(decision_payload, status)
    unresolved(decision_payload)
    result = FraudDecision.model_validate(decision_payload)
    assert result.risk_level == RiskLevel.UNKNOWN
    assert result.recommendation is None
    assert result.sift.score is None
    assert result.review_actions[0] == "RETRY_EVALUATION"


@pytest.mark.parametrize("component", ["intent", "rule"])
def test_required_semantic_or_rule_unknown_is_incomplete(decision_payload, component):
    result = decision_payload["intent"] if component == "intent" else decision_payload["rules"][0]
    result["comparison" if component == "intent" else "risk_level"] = "UNKNOWN"
    result["reasons"] = [{"code": "INSUFFICIENT_DATA", "source": "MOCK"}]
    with pytest.raises(ValidationError, match="required evaluations"):
        FraudDecision.model_validate(decision_payload)
    unresolved(decision_payload)
    assert FraudDecision.model_validate(decision_payload).status == DecisionStatus.INCOMPLETE


def test_optional_failure_requires_explicit_component_selection(decision_payload):
    fail_sift(decision_payload)
    decision_payload["required_components"] = ["INTENT", "RULES"]
    result = FraudDecision.model_validate(decision_payload)
    assert result.status == DecisionStatus.SUCCESS
    assert result.sift.score is None


@pytest.mark.parametrize("field,value", [("risk_level", "LOW"), ("recommendation", "ALLOW"), ("recommendation", "DENY")])
def test_unresolved_result_cannot_claim_known_risk_or_action(decision_payload, field, value):
    fail_sift(decision_payload)
    unresolved(decision_payload)
    decision_payload[field] = value
    with pytest.raises(ValidationError, match="UNKNOWN risk"):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("field,value", [("risk_level", "UNKNOWN"), ("recommendation", None)])
def test_success_requires_explicit_risk_and_recommendation(decision_payload, field, value):
    decision_payload[field] = value
    with pytest.raises(ValidationError, match="known risk"):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("component", ["intent", "sift", "rule"])
@pytest.mark.parametrize("field", ["evaluation_id", "user_id", "purchase_request_id", "intent_version"])
def test_results_from_different_targets_cannot_be_combined(decision_payload, component, field):
    result = decision_payload[component] if component != "rule" else decision_payload["rules"][0]
    result["metadata"]["target"][field] = "different-target"
    with pytest.raises(ValidationError, match="target"):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("field,value", [("snapshot_id", "another-checkout"), ("version", "1"), ("snapshot_hash", "b" * 64)])
def test_results_for_different_order_snapshots_are_rejected(decision_payload, field, value):
    decision_payload["sift"]["metadata"]["target"]["order"][field] = value
    with pytest.raises(ValidationError, match="target"):
        FraudDecision.model_validate(decision_payload)


def test_missing_required_rules_cannot_produce_success(decision_payload):
    decision_payload["rules"] = []
    with pytest.raises(ValidationError, match="required evaluations"):
        FraudDecision.model_validate(decision_payload)


def test_duplicate_rules_are_rejected(decision_payload):
    decision_payload["rules"].append(deepcopy(decision_payload["rules"][0]))
    with pytest.raises(ValidationError, match="rule IDs"):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("required", [[], ["SIFT", "SIFT"], ["UNRECOGNIZED"]])
def test_required_component_set_is_explicit_and_unambiguous(decision_payload, required):
    decision_payload["required_components"] = required
    with pytest.raises(ValidationError):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("field,value", [("trace_id", "other-trace"), ("evaluated_at", "2026-09-09T10:02:00+09:00")])
def test_component_trace_and_time_are_consistent(decision_payload, field, value):
    decision_payload["sift"]["metadata"][field] = value
    with pytest.raises(ValidationError):
        FraudDecision.model_validate(decision_payload)


@pytest.mark.parametrize("until", [None, "2026-09-09T10:10:01+09:00"])
def test_decision_cannot_outlive_required_result(decision_payload, until):
    decision_payload["metadata"]["valid_until"] = until
    with pytest.raises(ValidationError, match="outlive"):
        FraudDecision.model_validate(decision_payload)


def test_expired_required_result_can_only_be_incomplete(decision_payload):
    decision_payload["sift"]["metadata"]["valid_until"] = decision_payload["metadata"]["evaluated_at"]
    with pytest.raises(ValidationError, match="required evaluations"):
        FraudDecision.model_validate(decision_payload)
    unresolved(decision_payload)
    assert FraudDecision.model_validate(decision_payload).status == DecisionStatus.INCOMPLETE


def test_incomplete_requires_actual_unavailable_input(decision_payload):
    unresolved(decision_payload)
    with pytest.raises(ValidationError, match="INCOMPLETE"):
        FraudDecision.model_validate(decision_payload)


def test_aggregator_failure_keeps_successful_component_evidence(decision_payload):
    unresolved(decision_payload, "FAILED")
    decision_payload["reasons"] = [{"code": "EVALUATION_FAILED", "source": "FDS"}]
    result = FraudDecision.model_validate(decision_payload)
    assert result.recommendation is None
    assert result.sift.score.value == 0.2


def test_not_run_decision_keeps_all_components_not_run(decision_payload):
    fail_sift(decision_payload, "NOT_RUN")
    for result, field in [(decision_payload["intent"], "comparison"), (decision_payload["rules"][0], "risk_level")]:
        result.update(status="NOT_RUN", reasons=[{"code": "NOT_EXECUTED", "source": "MOCK"}])
        result[field] = None
        result["metadata"].update(evaluated_at=None, valid_until=None)
    unresolved(decision_payload, "NOT_RUN")
    decision_payload["metadata"].update(evaluated_at=None, policy_version=None)
    decision_payload["reasons"] = [{"code": "NOT_EXECUTED", "source": "FDS"}]
    assert FraudDecision.model_validate(decision_payload).status == DecisionStatus.NOT_RUN


@pytest.mark.parametrize("field,value", [("policy_version", None), ("evaluated_at", None)])
def test_success_requires_policy_and_evaluation_time(decision_payload, field, value):
    decision_payload["metadata"][field] = value
    decision_payload["metadata"]["valid_until"] = None
    with pytest.raises(ValidationError):
        FraudDecision.model_validate(decision_payload)


def test_payment_authority_flags_are_not_result_fields(decision_payload):
    decision_payload["payment_authorized"] = True
    with pytest.raises(ValidationError):
        FraudDecision.model_validate(decision_payload)


def test_failed_decision_requires_failure_reason(decision_payload):
    unresolved(decision_payload, "FAILED")
    with pytest.raises(ValidationError, match="failure reason"):
        FraudDecision.model_validate(decision_payload)


def test_not_run_decision_cannot_hide_evaluated_components(decision_payload):
    unresolved(decision_payload, "NOT_RUN")
    decision_payload["metadata"]["evaluated_at"] = None
    with pytest.raises(ValidationError, match="evaluated components"):
        FraudDecision.model_validate(decision_payload)
