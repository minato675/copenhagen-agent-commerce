"""Synthetic policy cases reuse the real Cold/personal scorer; GPT is explicitly mocked."""

from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.analysis_results import AnalysisFailure, IntentComparisonResult
from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES, RiskFloorRule, RiskPolicyConfig
from copenhagen_fds.personal_evaluation import evaluate_personalized_features
from copenhagen_fds.risk_groups import aggregate_risk_group


@pytest.fixture
def risk_cases(evaluation_cases):
    def policy():
        return RiskPolicyConfig(version="fixture-risk-v1", intent_required=True, intent_max_age_seconds=300,
            groups=[dict(group_id=name, weight=weight,
                         features=[dict(feature_id=f, required=True) for f in members])
                    for (name, members), weight in zip(GROUP_FEATURES.items(), (0.4, 0.35, 0.25))],
            thresholds=dict(medium_from=30.0, high_from=70.0),
            floor_rules=[dict(rule_id="strong-amount", conditions=[dict(group_id="AMOUNT", minimum_score=90.0)],
                             minimum_risk="HIGH")])

    def request(*, intent_state="MATCH", **kwargs):
        options = dict(profile=False, cold_parameters={"amount_minor": dict(center=10000.0, scale=10000.0)})
        options.update(kwargs)
        if options.get("cold") is False:
            options["cold_parameters"] = None
        scoring = evaluation_cases.request(**options)
        evaluation = scoring.cold.request.evaluation
        metadata = dict(target=evaluation.target, trace_id=scoring.cold.trace_id,
                        sources=["MOCK"], evaluated_at=evaluation.evaluated_at,
                        model_version="fixture-model", prompt_version="fixture-prompt")
        result = dict(status="SUCCESS", metadata=metadata, telemetry=dict(gateway="MOCK"), reasons=[],
                      payload=dict(comparison=intent_state, findings=[], confirmation_requests=[]))
        if intent_state in {"MISMATCH", "UNKNOWN"}:
            code = "INTENT_MISMATCH" if intent_state == "MISMATCH" else "INTENT_UNCERTAIN"
            result["payload"]["findings"] = [dict(code=code, dimension="PRODUCT", related_items=[])]
            result["reasons"] = [dict(code=code, source="MOCK")]
        elif intent_state in {"FAILED", "NOT_RUN"}:
            result.update(status=intent_state, payload=None,
                          reasons=[dict(code="TIMEOUT" if intent_state == "FAILED" else "NOT_EXECUTED", source="MOCK")])
            if intent_state == "FAILED":
                result["failure"] = dict(kind="TIMEOUT")
            else:
                metadata["evaluated_at"] = None
        return RiskPolicyInput(request=evaluation.request, scoring_input=scoring,
            behavior=evaluate_personalized_features(scoring), intent=IntentComparisonResult.model_validate(result),
            policy=policy(), evaluated_at=evaluation.evaluated_at)

    return SimpleNamespace(request=request, policy=policy)


@pytest.fixture
def group_cases(risk_cases):
    """Explicit synthetic scores for independent group arithmetic checks."""
    def case(group_id, scores):
        setting = next(g for g in risk_cases.policy().groups if g.group_id == group_id)
        features = tuple(FeatureScoreResult(feature_id=member.feature_id, status="SCORED",
            score=dict(value=value, unit="BEHAVIOR_ANOMALY_0_100"),
            bases=[dict(kind="INITIAL_POLICY", policy_version="fixture-group-v1")],
            reasons=["WITHIN_REFERENCE" if value == 0 else "PATTERN_DEVIATION"])
            for member, value in zip(setting.features, scores, strict=True))
        return setting, features
    return case


@pytest.fixture
def rule_cases(group_cases):
    def rule(*, rule_id="fixture-rule", floor="HIGH", **conditions):
        return RiskFloorRule(rule_id=rule_id, minimum_risk=floor,
            conditions=[dict(group_id=name, minimum_score=conditions[name])
                        for name in GROUP_FEATURES if name in conditions])

    def groups(*, missing=None, **scores):
        result = []
        for name, members in GROUP_FEATURES.items():
            setting, features = group_cases(name, [scores.get(name, 0.0)] + [0.0] * (len(members) - 1))
            if name == missing:
                features = (*features[:-1], FeatureScoreResult(feature_id=members[-1],
                    status="NOT_COLLECTED", reasons=["MISSING_INPUT"]))
            result.append(aggregate_risk_group(setting, features))
        return tuple(result)

    def configure(supplied, *rules):
        data = supplied.model_dump()
        data["policy"]["floor_rules"] = rules
        return RiskPolicyInput.model_validate(data)

    return SimpleNamespace(rule=rule, groups=groups, configure=configure)


@pytest.fixture
def intent_cases(risk_cases):
    def request(*, state="MATCH", required=True, failure=None, detailed=False, **kwargs):
        data = risk_cases.request(intent_state=state, **kwargs).model_dump()
        data["policy"]["intent_required"] = required
        source = data["intent"]
        if failure is not None:
            source["failure"] = AnalysisFailure.model_validate(failure)
            source["reasons"] = [dict(code=source["failure"].reason_code, source="MOCK")]
        if detailed:
            order = data["request"]["order"]["orders"][0]
            reference = dict(order_id=order["order_id"], item_id=order["items"][0]["item_id"])
            source["payload"]["findings"][0]["related_items"] = [reference]
            source["payload"]["confirmation_requests"] = [dict(code="CLARIFY_INTENT", related_items=[reference])]
            source["telemetry"].update(request_id="fixture-call", response_id="fixture-response",
                requested_model="fixture-model", duration_ms=15.0,
                usage=dict(prompt_tokens=10, completion_tokens=5, total_tokens=15))
        return RiskPolicyInput.model_validate(data)

    return SimpleNamespace(request=request)


@pytest.fixture
def classification_cases(intent_cases, rule_cases, monkeypatch):
    def request(*, behavior="NORMAL", **kwargs):
        options = {}
        if behavior in {"MEDIUM", "HIGH", "COMPOUND"}:
            options["values"] = {"purchase_count_10m": 1}
        if behavior in {"STRONG", "HIGH", "FAILED"}:
            options["cold_parameters"] = {"amount_minor": dict(center=0.0, scale=10000.0)}
        if behavior == "COMPOUND":
            options["values"] = {"purchase_count_10m": 3}
            options["cold_parameters"] = {"amount_minor": dict(center=4000.0, scale=10000.0),
                                          "purchase_count_10m": dict(center=0.0, scale=5.0)}
        if behavior in {"MISSING", "OPTIONAL_MISSING"}:
            options["states"] = {"kst_day_of_week": "NOT_COLLECTED"}
        options.update(kwargs)
        with monkeypatch.context() as patch:
            if behavior == "FAILED":
                import copenhagen_fds.personal_amount as module

                def fail(*args):
                    raise ArithmeticError("injected transient failure")

                patch.setattr(module, "_calculation", fail)
                options["profile"] = True
            supplied = intent_cases.request(**options)
        if behavior == "COMPOUND":
            supplied = rule_cases.configure(supplied, rule_cases.rule(AMOUNT=60.0, FREQUENCY=60.0))
        data = supplied.model_dump()
        if behavior == "NOT_RUN":
            data["behavior"] = None
        if behavior == "OPTIONAL_MISSING":
            data["policy"]["groups"][2]["features"][1]["required"] = False
        return RiskPolicyInput.model_validate(data)

    return SimpleNamespace(request=request)
