from datetime import timedelta, timezone

import pytest
from pydantic import ValidationError

import copenhagen_fds.personal_time as time_module
from copenhagen_fds.contracts.personal_time import PersonalTimePolicy, PersonalTimeResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.profile_policy import ProfilePolicyConfig
from copenhagen_fds.personal_time import score_personal_times


@pytest.mark.parametrize("days,minimum,expected", [
    ((), 2, ("EMPTY", "EMPTY")), ((1,), 1, ("INSUFFICIENT_SAMPLES", "INSUFFICIENT_SAMPLES")),
    ((1, 8), 2, ("INSUFFICIENT_SAMPLES", "SCORED")), ((8, 9), 2, ("EMPTY", "SCORED")),
    ((1, 7), 2, ("SCORED", "SCORED")), ((1, 2), 3, ("INSUFFICIENT_SAMPLES", "INSUFFICIENT_SAMPLES")),
])
def test_window_sample_shortage_is_not_an_unobserved_bin(time_cases, days, minimum, expected):
    profile = time_cases.publication([{"at": time_cases.cutoff - timedelta(days=d)} for d in days], minimum=minimum)
    result = score_personal_times(time_cases.request(current=time_cases.occurred(12), profile=profile))
    windows = result.features[0].windows
    assert tuple(w.diagnostic for w in windows) == expected
    for w in windows:
        if w.diagnostic == "SCORED":
            assert w.result.score.value > 0 and w.calculation.bin_status == "UNOBSERVED"
        else:
            assert w.result.score is None and w.calculation is None and not w.result.bases


def test_feature_missing_history_and_readiness_minimums_are_independent(time_cases):
    records = [{"at": time_cases.occurred(), "missing": {"kst_hour": "NOT_COLLECTED"}}] * 2
    records.append({"at": time_cases.occurred()})
    result = score_personal_times(time_cases.request(profile=time_cases.publication(records),
        minimum={"kst_day_of_week": 4}))
    hour, day = result.features
    assert hour.windows[0].evidence.distribution.not_collected_count == 2
    assert hour.windows[0].diagnostic == day.windows[0].diagnostic == "INSUFFICIENT_SAMPLES"
    assert (hour.minimum_samples, day.minimum_samples) == (2, 4)


@pytest.mark.parametrize("expired", [False, True])
def test_missing_or_expired_profile_retains_unavailability(time_cases, expired):
    profile = time_cases.publication()
    result = score_personal_times(time_cases.request(profile=profile if expired else False,
        at=profile.state.published.profile.valid_until if expired else None))
    for feature in result.features:
        for window in feature.windows:
            assert window.diagnostic == ("PROFILE_EXPIRED" if expired else "PROFILE_NOT_SUPPLIED")
            assert window.result.score is None and not window.result.bases
            assert (window.evidence is not None) == expired


def test_recent_and_long_term_patterns_remain_separate_candidates(time_cases):
    records = [{"at": time_cases.occurred()}] * 2
    records += [{"at": time_cases.occurred(12) - timedelta(days=10)}] * 4
    result = score_personal_times(time_cases.request(profile=time_cases.publication(records)))
    assert result.features[0].windows[0].result.score.value == 0
    assert result.features[0].windows[1].result.score.value == 40
    assert "stage" not in result.model_dump() and "personal_weight" not in result.model_dump()


def test_published_time_profile_excludes_withheld_candidate(time_cases):
    records = [{"at": time_cases.occurred() - timedelta(days=d)} for d in (3, 2, 1)]
    records.append({"at": time_cases.occurred(12), "amount": 10000})
    profile = time_cases.publication(records, admission=ProfilePolicyConfig(min_reference_samples=3))
    assert profile.state.candidate.snapshot.long_term.included_count == 4
    assert profile.state.published.long_term.included_count == 3
    result = score_personal_times(time_cases.request(current=time_cases.occurred(12), profile=profile))
    calc = result.features[0].windows[0].calculation
    assert calc.sample_count == 3 and calc.direct_count == 0
    assert result.features[0].windows[0].result.score.value == 75


@pytest.mark.parametrize("requested,expected", [
    (("kst_day_of_week",), ["kst_day_of_week"]),
    (("kst_day_of_week", "amount_minor", "kst_hour"), ["kst_hour", "kst_day_of_week"]),
])
def test_requested_time_subset_has_canonical_order(time_cases, requested, expected):
    result = score_personal_times(time_cases.request(requested=requested))
    assert [f.raw.feature_id for f in result.features] == expected


def test_absent_time_request_and_unknown_score_version_are_rejected(time_cases):
    with pytest.raises(ValueError, match="requested time"):
        score_personal_times(time_cases.request(requested=("amount_minor",)))
    data = time_cases.request().model_dump(mode="python")
    data["config"]["features"][-1]["score_version"] = "unknown"
    with pytest.raises(ValueError, match="supported score version"):
        score_personal_times(PersonalizedScoringInput.model_validate(data))


@pytest.mark.parametrize("change", [
    {"hour_neighbor_weight": -0.1}, {"hour_neighbor_weight": 0.6},
    {"weekday_neighbor_weight": float("nan")}, {"weekday_neighbor_weight": float("inf")},
    {"pseudocount": 0.0}, {"pseudocount": -1.0}, {"pseudocount": float("inf")}, {"version": "unknown"},
])
def test_invalid_time_policy_is_rejected(change):
    with pytest.raises(ValidationError):
        PersonalTimePolicy(**change)


def test_policy_and_nested_input_are_revalidated_at_execution(time_cases):
    supplied = time_cases.request()
    with pytest.raises(ValueError):
        score_personal_times(supplied, PersonalTimePolicy().model_copy(update={"pseudocount": 0.0}))
    raw = supplied.cold.raw_features.features[0].model_copy(update={"value": 24})
    vector = supplied.cold.raw_features.model_copy(update={"features": (raw, supplied.cold.raw_features.features[1])})
    with pytest.raises(ValueError):
        score_personal_times(supplied.model_copy(update={"cold": supplied.cold.model_copy(update={"raw_features": vector})}))


def test_json_roundtrip_timezone_reexecution_and_immutability(time_cases):
    supplied = time_cases.request(current=time_cases.occurred(12))
    before = supplied.model_dump_json()
    result = score_personal_times(supplied)
    restored = PersonalTimeResult.model_validate_json(result.model_dump_json())
    assert restored.validate_for_input(PersonalizedScoringInput.model_validate_json(before)) == result
    data = supplied.model_dump(mode="python")
    data["personal"]["available_at"] = data["personal"]["available_at"].astimezone(timezone.utc)
    assert score_personal_times(PersonalizedScoringInput.model_validate(data)) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("change", ["score", "mass", "bin", "count", "basis", "feature", "period", "missing_feature", "input_hash", "policy"])
def test_detached_result_evidence_is_rejected(time_cases, change):
    supplied = time_cases.request(current=time_cases.occurred(12))
    data = score_personal_times(supplied).model_dump(mode="python")
    window = data["features"][0]["windows"][0]
    if change == "score":
        window["result"]["score"]["value"] = 99.0
    elif change == "mass":
        window["calculation"]["total_mass"]["numerator"] += 2
    elif change == "bin":
        window["calculation"]["bin_index"] = 11
    elif change == "count":
        window["calculation"]["direct_count"] = 1
    elif change == "basis":
        window["result"]["bases"][0]["profile"]["version"] = "other"
    elif change == "feature":
        window["result"]["feature_id"] = "kst_day_of_week"
    elif change == "period":
        window["calculation"]["period"] = 7
    elif change == "missing_feature":
        data["features"] = data["features"][:-1]
    elif change == "input_hash":
        data["input_sha256"] = "b" * 64
    else:
        data["policy"]["pseudocount"] = 2.0
    with pytest.raises(ValueError):
        PersonalTimeResult.model_validate(data).validate_for_input(supplied)


def test_single_window_failure_preserves_other_candidates(time_cases, monkeypatch):
    original = time_module._calculation
    calls = 0

    def fail_once(distribution, position, policy):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise ArithmeticError("synthetic computation failure")
        return original(distribution, position, policy)

    monkeypatch.setattr(time_module, "_calculation", fail_once)
    result = score_personal_times(time_cases.request())
    assert result.features[0].windows[1].diagnostic == "CALCULATION_FAILED"
    assert result.features[0].windows[1].result.score is None
    assert result.features[0].windows[0].diagnostic == "SCORED"
    assert all(w.diagnostic == "SCORED" for w in result.features[1].windows)
