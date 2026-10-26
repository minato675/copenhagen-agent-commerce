import pytest

from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_intent_policy import RiskIntentPolicyResult
from copenhagen_fds.risk_intent_policy import evaluate_risk_intent_policy
from copenhagen_fds.risk_rules import evaluate_risk_rules


@pytest.mark.parametrize("required", [True, False])
@pytest.mark.parametrize("state,actions", [
    ("MATCH", ()), ("MISMATCH", ("REVIEW_ORDER", "VERIFY_USER_INTENT")),
    ("UNKNOWN", ("CHECK_INPUT", "VERIFY_USER_INTENT")), ("FAILED", ("RETRY_EVALUATION",)),
    ("NOT_RUN", ("RETRY_EVALUATION",)),
])
def test_required_optional_review_decision_table(intent_cases, required, state, actions):
    supplied = intent_cases.request(state=state, required=required)
    result = evaluate_risk_intent_policy(supplied)
    expected = "FAILED" if state == "FAILED" else (
        "INCOMPLETE" if required and state in {"UNKNOWN", "NOT_RUN"} else "SUCCESS")
    assert result.status == expected and result.review_actions == actions
    assert result.intent.signal == state and result.intent.source == supplied.intent
    assert RiskIntentPolicyResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result


@pytest.mark.parametrize("failure,check_first", [
    (dict(kind="TIMEOUT"), False), (dict(kind="TIMEOUT", retryable=False), True),
    (dict(kind="API_ERROR", api_error="RATE_LIMIT", http_status=429, retryable=True), False),
    (dict(kind="API_ERROR", api_error="SERVER", http_status=503), False),
    (dict(kind="API_ERROR", api_error="AUTHENTICATION", http_status=401, retryable=True), True),
    (dict(kind="API_ERROR", api_error="PERMISSION", http_status=403), True),
    (dict(kind="API_ERROR", api_error="REQUEST", http_status=400), True),
    (dict(kind="INSUFFICIENT_INPUT"), True), (dict(kind="INVALID_INPUT"), True),
    (dict(kind="SCHEMA_ERROR"), True), (dict(kind="REFUSAL"), True),
    (dict(kind="INCOMPLETE_RESPONSE"), True),
])
def test_failures_require_repair_before_reevaluation_when_needed(intent_cases, failure, check_first):
    supplied = intent_cases.request(state="FAILED", failure=failure)
    result = evaluate_risk_intent_policy(supplied)
    assert result.status == "FAILED"
    assert result.review_actions == (("CHECK_INPUT", "RETRY_EVALUATION") if check_first else ("RETRY_EVALUATION",))
    assert result.intent.source.failure == supplied.intent.failure


@pytest.mark.parametrize("state", ["MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"])
def test_gpt_never_discounts_high_behavior_or_adds_numeric_score(intent_cases, state):
    options = dict(values={"purchase_count_10m": 1}, cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)})
    supplied = intent_cases.request(state=state, **options)
    before = supplied.model_dump_json()
    result = evaluate_risk_intent_policy(supplied)
    assert result.rules.aggregation.behavior_score.value == 75.0
    assert result.rules.minimum_risk == "HIGH"
    assert result.rules == evaluate_risk_rules(supplied)
    reference = evaluate_risk_intent_policy(intent_cases.request(state="MATCH", **options))
    assert result.rules.aggregation.aggregation_sha256 == reference.rules.aggregation.aggregation_sha256
    assert result.rules.rule_evidence == reference.rules.rule_evidence
    assert not {"score", "risk_level", "probability", "recommendation"}.intersection(type(result).model_fields)
    assert supplied.model_dump_json() == before


def test_match_preserves_behavior_failure_and_high_cold_fallback(intent_cases, monkeypatch):
    import copenhagen_fds.personal_amount as module

    def fail(*args):
        raise ArithmeticError("injected transient failure")

    with monkeypatch.context() as patch:
        patch.setattr(module, "_calculation", fail)
        supplied = intent_cases.request(profile=True,
            cold_parameters={"amount_minor": dict(center=0.0, scale=10000.0)})
    result = evaluate_risk_intent_policy(supplied)
    assert result.status == "SUCCESS" and result.intent.signal == "MATCH"
    assert result.rules.aggregation.assessment.status == "FAILED"
    assert result.rules.aggregation.assessment.failures
    assert result.rules.minimum_risk == "HIGH"
    assert result.rules == evaluate_risk_rules(supplied)


def test_unknown_preserves_missing_behavior_without_filling_zero(intent_cases):
    supplied = intent_cases.request(state="UNKNOWN", states={"amount_minor": "NOT_COLLECTED"})
    result = evaluate_risk_intent_policy(supplied)
    assert result.status == "INCOMPLETE"
    assert result.rules.aggregation.behavior_score is None
    assert result.rules.aggregation.assessment.status == "INCOMPLETE"
    assert result.rules == evaluate_risk_rules(supplied)


def test_mismatch_details_and_no_model_or_scorer_call(intent_cases, monkeypatch):
    import copenhagen_fds.personal_evaluation as module
    from openai import OpenAI

    supplied = intent_cases.request(state="MISMATCH", detailed=True)

    def forbidden(*args, **kwargs):
        pytest.fail("intent review policy must not invoke an API client or feature scorer")

    monkeypatch.setattr(module, "evaluate_personalized_features", forbidden)
    monkeypatch.setattr(OpenAI, "__init__", forbidden)
    result = evaluate_risk_intent_policy(supplied)
    assert result.intent.source == supplied.intent
    assert result.review_actions == ("REVIEW_ORDER", "VERIFY_USER_INTENT")
    assert evaluate_risk_intent_policy(RiskPolicyInput.model_validate_json(supplied.model_dump_json())) == result


@pytest.mark.parametrize("field,value", [("status", "SUCCESS"), ("review_actions", ()),
                                        ("evaluation_version", "unsupported-v2")])
def test_review_tamper_rejected(intent_cases, field, value):
    supplied = intent_cases.request(state="FAILED")
    result = evaluate_risk_intent_policy(supplied)
    with pytest.raises(ValueError):
        RiskIntentPolicyResult.model_validate({**result.model_dump(), field: value})
    with pytest.raises(ValueError):
        result.model_copy(update={field: value}).validate_for_input(supplied)


def test_cross_input_signal_and_unchecked_invalid_input_rejected(intent_cases):
    supplied = intent_cases.request(state="UNKNOWN")
    result = evaluate_risk_intent_policy(supplied)
    other = evaluate_risk_intent_policy(intent_cases.request(state="MATCH"))
    with pytest.raises(ValueError, match="exact same input"):
        RiskIntentPolicyResult.model_validate({**result.model_dump(), "intent": other.intent.model_dump()})
    policy = supplied.policy.model_copy(update={"intent_max_age_seconds": 0})
    with pytest.raises(ValueError):
        evaluate_risk_intent_policy(supplied.model_copy(update={"policy": policy}))
