from datetime import timedelta

import pytest

from copenhagen_fds.contracts.analysis_inputs import IntentAnalysisRequest
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.features import FeatureTransformInput
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.engine_behavior_inputs import build_engine_behavior_input, validate_engine_behavior_input
from copenhagen_fds.population_baselines import derive_population_baseline


def test_preparation_matches_existing_cold_transition_personal_and_expiry_inputs(behavior_cases):
    for request, row in behavior_cases.comparison():
        before = request.model_dump_json()
        supplied = build_engine_behavior_input(request)
        assert supplied == row.input
        validate_engine_behavior_input(request, supplied)
        assert PersonalizedScoringInput.model_validate_json(supplied.model_dump_json()) == supplied
        assert request.model_dump_json() == before


def test_partial_population_keeps_derived_identity_and_feature_fallback_diagnostics(behavior_cases):
    request = behavior_cases.population()
    supplied = build_engine_behavior_input(request)
    derived = derive_population_baseline(request.population.state.published, request.population_baseline_request)
    assert supplied.population == request.population
    assert supplied.population_baseline == derived
    assert derived.status == "PARTIAL"
    assert supplied.cold.request.population_reference == derived.baseline.profile.reference
    assert derived.baseline.profile.reference != request.population.expected_reference
    selected = {f.feature_id: f for f in supplied.cold.selection.features}
    assert selected["amount_minor"].basis.kind == "POPULATION_PROFILE"
    assert selected["purchase_count_10m"].basis.kind == "INITIAL_POLICY"
    assert selected["purchase_count_10m"].issues[0].code == "FEATURE_MISSING"
    assert next(f for f in derived.features if f.feature_id == "purchase_count_10m").reason == "ZERO_SCALE"


def test_unavailable_population_retains_missing_support_while_using_explicit_initial_policy(behavior_cases):
    supplied = build_engine_behavior_input(behavior_cases.population(unavailable=True))
    assert supplied.population_baseline.status == "UNAVAILABLE"
    assert supplied.population_baseline.issue is None
    assert supplied.population_baseline.baseline is None
    assert all(f.reason == "EMPTY" for f in supplied.population_baseline.features)
    assert all(f.basis.kind == "INITIAL_POLICY" for f in supplied.cold.selection.features)


def test_expired_population_is_not_silently_treated_as_missing(behavior_cases):
    supplied = build_engine_behavior_input(behavior_cases.population(expired=True))
    assert supplied.profile_availability()[1].status == "EXPIRED"
    assert all(f.status == "UNAVAILABLE" and f.basis is None for f in supplied.cold.selection.features)
    assert all(f.issues[0].code == "EXPIRED" for f in supplied.cold.selection.features)


def test_derivation_after_expiry_is_an_input_error_not_a_cold_fallback(behavior_cases):
    request = behavior_cases.population(expired=True)
    invalid = request.population_baseline_request.model_copy(update={"available_at": request.feature_input.evaluation.evaluated_at})
    with pytest.raises(ValueError, match="INVALID_AVAILABILITY"):
        build_engine_behavior_input(request.model_copy(update={"population_baseline_request": invalid}))


@pytest.mark.parametrize("coverage,expected", [("none", "NOT_COLLECTED"), ("NOT_COLLECTED", "NOT_COLLECTED"),
    ("NOT_APPLICABLE", "NOT_APPLICABLE"), ("short", "INSUFFICIENT_HISTORY"), ("complete", "AVAILABLE")])
def test_uncollected_short_and_observed_empty_histories_remain_distinct(engine_cases, coverage, expected):
    data = engine_cases.inputs()[0].model_dump()
    history = data["feature_input"]["history"]
    if coverage == "none":
        data["feature_input"]["history"] = None
    elif coverage in {"NOT_COLLECTED", "NOT_APPLICABLE"}:
        history["purchases"] = history["attempts"] = dict(status=coverage)
    elif coverage == "short":
        for stream in ("purchases", "attempts"):
            history[stream]["complete_from"] = engine_cases.at - timedelta(minutes=1)
    supplied = build_engine_behavior_input(EngineEvaluationRequest.model_validate(data))
    counts = [f for f in supplied.cold.raw_features.features if f.window_seconds is not None]
    assert {f.status for f in counts} == {expected}
    assert all(f.value == (0 if expected == "AVAILABLE" else None) for f in counts)


def test_observation_visibility_deduplication_and_current_purchase_exclusion(engine_cases):
    request = engine_cases.inputs()[0]
    data = request.model_dump()
    at = engine_cases.at
    def event(eid, pid, kind="PURCHASE_REQUESTED", aid=None, **changes):
        return dict(event_id=eid, kind=kind, user_id="a", purchase_request_id=pid, attempt_id=aid,
            currency="KRW", **{**dict(occurred_at=at-timedelta(minutes=2), available_at=at-timedelta(minutes=1)), **changes})
    current = request.analysis.target.purchase_request_id
    data["feature_input"]["history"]["events"] = [event("merchant-1", "past"), event("merchant-2", "past"),
        event("attempt-1", "past", "PAYMENT_ATTEMPTED", "a1"), event("attempt-2", "past", "PAYMENT_ATTEMPTED", "a2"),
        event("late", "invisible", available_at=at+timedelta(seconds=1)),
        event("current", current, occurred_at=request.feature_input.current_occurred_at, available_at=request.feature_input.current_available_at)]
    supplied = build_engine_behavior_input(EngineEvaluationRequest.model_validate(data))
    values = {f.feature_id: f.value for f in supplied.cold.raw_features.features}
    assert values["purchase_count_10m"] == 1
    assert values["attempt_count_10m"] == 2


@pytest.mark.parametrize("change", ["user", "currency", "profile_version", "state_version", "future_publication", "future_feedback"])
def test_mismatched_or_future_publication_is_rejected_even_after_unchecked_copy(engine_cases, change):
    request = engine_cases.inputs(profile=True)[0]
    source = request.personal
    if change in {"user", "currency"}:
        profile = source.state.published.profile
        altered = (profile.model_copy(update={"reference": profile.reference.model_copy(update={"user_id": "other"})})
                   if change == "user" else profile.model_copy(update={"currency": "USD"}))
        source = source.model_copy(update={"state": source.state.model_copy(update={
            "published": source.state.published.model_copy(update={"profile": altered})})})
    elif change == "profile_version":
        source = source.model_copy(update={"expected_reference": source.expected_reference.model_copy(update={"version": "another"})})
    elif change == "state_version":
        source = source.model_copy(update={"expected_policy_state_version": "another"})
    elif change == "future_publication":
        source = source.model_copy(update={"available_at": engine_cases.at + timedelta(seconds=1)})
    else:
        first, *rest = source.state.observations
        first = first.model_copy(update={"feedback": first.feedback.model_copy(update={"available_at": engine_cases.at + timedelta(days=1)})})
        source = source.model_copy(update={"state": source.state.model_copy(update={"observations": (first, *rest)})})
    with pytest.raises(ValueError):
        build_engine_behavior_input(request.model_copy(update={"personal": source}))


def test_already_learned_current_purchase_is_rejected_without_profile_rewrite(engine_cases):
    request = engine_cases.inputs(profile=True)[0]
    before = request.personal.model_dump_json()
    pid = request.personal.state.observations[0].sample.eligibility.target.purchase_request_id
    feature = request.feature_input.model_dump()
    original = feature["evaluation"]["request"]
    for part in ("context", "order", "intent"):
        original[part]["purchase_request_id"] = pid
    analysis = request.analysis.model_dump()
    for part in ("context", "order", "user_request"):
        analysis[part]["purchase_request_id"] = pid
    invalid = request.model_copy(update={"feature_input": FeatureTransformInput.model_validate(feature),
        "analysis": IntentAnalysisRequest.model_validate(analysis)})
    with pytest.raises(ValueError, match="current purchase"):
        build_engine_behavior_input(invalid)
    assert request.personal.model_dump_json() == before


def test_conflicting_observable_facts_raise_without_inventing_counts(engine_cases):
    data = engine_cases.inputs()[0].model_dump()
    at = engine_cases.at
    event = dict(event_id="same", kind="PURCHASE_REQUESTED", user_id="a", purchase_request_id="past", currency="KRW",
                 occurred_at=at-timedelta(minutes=2), available_at=at-timedelta(minutes=1))
    data["feature_input"]["history"]["events"] = [event, {**event, "occurred_at": at-timedelta(minutes=3)}]
    with pytest.raises(ValueError, match="conflicting contents"):
        build_engine_behavior_input(EngineEvaluationRequest.model_validate(data))
