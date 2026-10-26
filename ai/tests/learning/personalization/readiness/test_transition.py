from datetime import timedelta

import pytest

from copenhagen_fds.contracts.personal_readiness import READINESS_VERSION
from copenhagen_fds.contracts.personal_transition import PersonalTransitionResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.profile_policy import ProfilePolicyConfig
from copenhagen_fds.personal_transition import decide_personal_transition


@pytest.mark.parametrize("count,stage,weight", [(2, "COLD", 0), (3, "TRANSITION", 0.5), (4, "PERSONALIZED", 1)])
@pytest.mark.parametrize("cold", [True, False])
def test_reference_combination_table_never_promotes_partial_support(readiness_cases, count, stage, weight, cold):
    profile = readiness_cases.publication([{"days": d} for d in range(1, count + 1)])
    result = decide_personal_transition(readiness_cases.request(profile=profile, cold=cold))
    for row in result.features:
        assert row.preferred_personal_weight == weight
        if not cold and weight < 1:
            assert row.stage == "UNAVAILABLE" and row.personal_weight is None and row.cold_weight is None
        else:
            assert row.stage == stage and row.personal_weight == weight and row.cold_weight == 1 - weight


def test_feature_observation_states_produce_different_stages(readiness_cases):
    records = [{"days": d, "missing": {"kst_hour": "NOT_COLLECTED"}} for d in (4, 3, 2, 1)]
    records[0]["missing"]["purchase_count_10m"] = "INSUFFICIENT_HISTORY"
    result = decide_personal_transition(readiness_cases.request(profile=readiness_cases.publication(records)))
    assert readiness_cases.feature(result, "amount_minor").stage == "PERSONALIZED"
    assert readiness_cases.feature(result, "purchase_count_10m").stage == "TRANSITION"
    assert readiness_cases.feature(result, "purchase_count_10m").personal_weight == 0.5
    assert readiness_cases.feature(result, "kst_hour").stage == "COLD"


@pytest.mark.parametrize("days,expected", [((1, 2, 3, 4), "RECENT"), ((1, 8, 9, 10), "LONG_TERM"), ((8, 9, 10, 11), "LONG_TERM")])
def test_window_selection_prefers_higher_support_and_recent_ties(readiness_cases, days, expected):
    profile = readiness_cases.publication([{"days": d} for d in days])
    settings = {name: {"fresh_for_seconds": 864000.0, "stale_after_seconds": 1728000.0}
                for name in ("amount_minor",)}
    result = decide_personal_transition(readiness_cases.request(profile=profile, settings=settings))
    assert result.features[0].selected_window == expected
    if expected == "RECENT":
        assert "RECENT_TIE" in result.features[0].reasons


def test_recent_can_win_when_long_term_has_more_missingness(readiness_cases):
    records = [{"days": d} for d in (1, 2, 3, 4)]
    records += [{"days": d, "missing": {"kst_hour": "NOT_COLLECTED"}} for d in (8, 9, 10, 11)]
    result = decide_personal_transition(readiness_cases.request(profile=readiness_cases.publication(records)))
    row = readiness_cases.feature(result, "kst_hour")
    assert row.selected_window == "RECENT" and "RECENT_HIGHER_SUPPORT" in row.reasons
    assert row.personal_weight == 1


def test_aging_and_expiry_reduce_personal_weight_and_fall_back_to_cold(readiness_cases):
    profile = readiness_cases.publication()
    states = []
    for age in (1, 7, 8.5, 10, 31):
        at = readiness_cases.cutoff - timedelta(days=1) + timedelta(days=age, seconds=2)
        row = decide_personal_transition(readiness_cases.request(profile=profile, at=at)).features[0]
        states.append(row)
    assert [s.stage for s in states] == ["PERSONALIZED", "TRANSITION", "TRANSITION", "COLD", "COLD"]
    assert all(a.preferred_personal_weight >= b.preferred_personal_weight for a, b in zip(states, states[1:]))
    assert states[-1].cold_weight == 1 and states[-1].selected_window is None


def test_expiry_boundary_and_missing_profile_have_no_personal_weight(readiness_cases):
    profile = readiness_cases.publication()
    for supplied in (readiness_cases.request(profile=False), readiness_cases.request(profile=profile,
                    at=profile.state.published.profile.valid_until)):
        result = decide_personal_transition(supplied)
        assert all(f.stage == "COLD" and f.personal_weight == 0 for f in result.features)
        assert all(f.selected_window is None for f in result.features)


@pytest.mark.parametrize("state", ["NOT_COLLECTED", "INSUFFICIENT_HISTORY", "NOT_APPLICABLE"])
def test_missing_current_input_never_uses_full_personal_or_cold_support(readiness_cases, state):
    result = decide_personal_transition(readiness_cases.request(states={"kst_hour": state}))
    row = readiness_cases.feature(result, "kst_hour")
    assert row.preferred_personal_weight == 1 and row.stage == "UNAVAILABLE"
    assert row.personal_weight is None and row.cold_weight is None and "INPUT_" + state in row.reasons
    assert result.features[0].stage == "PERSONALIZED"


def test_observed_zero_count_is_not_missing_and_scores_do_not_choose_stage(readiness_cases):
    left = decide_personal_transition(readiness_cases.request())
    profile = readiness_cases.publication([{"days": d, "counts": 100, "amount": 1000} for d in (1, 2, 3, 4)])
    right = decide_personal_transition(readiness_cases.request(profile=profile))
    assert [(f.stage, f.selected_window, f.personal_weight) for f in left.features] == [
        (f.stage, f.selected_window, f.personal_weight) for f in right.features]
    assert readiness_cases.feature(left, "purchase_count_10m").raw.value == 0


def test_withheld_candidate_does_not_increase_published_readiness(readiness_cases):
    records = [{"days": d} for d in (4, 3, 2)] + [{"days": 1, "amount": 10000}]
    profile = readiness_cases.publication(records, admission=ProfilePolicyConfig(min_reference_samples=3))
    assert profile.state.candidate.snapshot.long_term.included_count == 4
    assert profile.state.published.long_term.included_count == 3
    result = decide_personal_transition(readiness_cases.request(profile=profile))
    assert result.features[0].stage == "TRANSITION" and result.features[0].personal_weight == 0.5


def test_published_population_and_initial_policy_keep_the_selected_cold_basis(personalized):
    profile = personalized.publication(scope="POPULATION")
    data = personalized.request(personal=False, population_profile=profile).model_dump()
    data["config"]["transition_policy_version"] = READINESS_VERSION
    supplied = PersonalizedScoringInput.model_validate(data)
    result = decide_personal_transition(supplied)
    assert all(f.stage == "COLD" for f in result.features)
    assert {f.cold_reference.basis.kind for f in result.features} == {"POPULATION_PROFILE", "INITIAL_POLICY"}


def test_transition_roundtrip_order_and_immutability(readiness_cases):
    supplied = readiness_cases.request(requested=("kst_hour", "amount_minor"))
    before = supplied.model_dump_json()
    result = decide_personal_transition(supplied)
    assert [f.raw.feature_id for f in result.features] == ["amount_minor", "kst_hour"]
    assert PersonalTransitionResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    assert decide_personal_transition(PersonalizedScoringInput.model_validate_json(before)) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("change", ["stage", "weight", "cold_weight", "selected_window", "reason", "raw", "missing_feature"])
def test_detached_transition_result_is_rejected(readiness_cases, change):
    supplied = readiness_cases.request()
    data = decide_personal_transition(supplied).model_dump()
    row = data["features"][0]
    if change == "stage":
        row["stage"] = "COLD"
    elif change == "weight":
        row["personal_weight"] = 0.5
    elif change == "cold_weight":
        row["cold_weight"] = 0.1
    elif change == "selected_window":
        row["selected_window"] = "LONG_TERM"
    elif change == "reason":
        row["reasons"] = ["OTHER"]
    elif change == "raw":
        row["raw"]["value"] = 99
    else:
        data["features"] = data["features"][:-1]
    with pytest.raises(ValueError):
        PersonalTransitionResult.model_validate(data).validate_for_input(supplied)


def test_unknown_policy_and_bypassed_config_are_rejected(readiness_cases):
    supplied = readiness_cases.request()
    config = supplied.config.model_copy(update={"transition_policy_version": "unknown"})
    with pytest.raises(ValueError, match="supported transition"):
        decide_personal_transition(supplied.model_copy(update={"config": config}))
    feature = supplied.config.features[0]
    invalid = feature.model_copy(update={"readiness": feature.readiness.model_copy(update={"full_samples": 1})})
    config = supplied.config.model_copy(update={"features": (invalid, *supplied.config.features[1:])})
    with pytest.raises(ValueError):
        decide_personal_transition(supplied.model_copy(update={"config": config}))
