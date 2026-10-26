from datetime import timedelta, timezone

import pytest
from pydantic import ValidationError

import copenhagen_fds.personal_amount as amount_module
from copenhagen_fds.contracts.personal_amount import PersonalAmountPolicy, PersonalAmountResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.personal_amount import score_personal_amount


def test_recent_and_long_term_candidates_do_not_select_or_mix_windows(amount_cases):
    profile = amount_cases.publication((100, 100, 1000, 1000, 1000), days=(1, 2, 10, 11, 12))
    result = score_personal_amount(amount_cases.request(200, profile=profile))
    assert [w.window for w in result.windows] == ["RECENT", "LONG_TERM"]
    assert [w.result.score.value for w in result.windows] == [100.0, 0.0]
    assert [w.evidence.distribution.observation.valid_sample_count for w in result.windows] == [2, 5]
    assert "stage" not in result.model_dump() and "personal_weight" not in result.model_dump()


@pytest.mark.parametrize("amounts,days,expected", [
    ((), (), ["EMPTY", "EMPTY"]),
    ((100,), (1,), ["INSUFFICIENT_SAMPLES", "INSUFFICIENT_SAMPLES"]),
    ((100, 100), (1, 8), ["INSUFFICIENT_SAMPLES", "SCORED"]),
    ((100, 100), (8, 9), ["EMPTY", "SCORED"]),
    ((100, 100), (1, 7), ["SCORED", "SCORED"]),
])
def test_each_window_retains_its_own_sample_sufficiency(amount_cases, amounts, days, expected):
    profile = amount_cases.publication(amounts, days=days)
    result = score_personal_amount(amount_cases.request(100, profile=profile))
    assert [w.diagnostic for w in result.windows] == expected
    for window in result.windows:
        assert (window.result.score is not None) == (window.diagnostic == "SCORED")


@pytest.mark.parametrize("source", ["published", "scoring"])
def test_sample_threshold_respects_profile_and_scoring_settings(amount_cases, source):
    profile = amount_cases.publication((100, 100), minimum=3 if source == "published" else 1)
    result = score_personal_amount(amount_cases.request(profile=profile, minimum=3 if source == "scoring" else 1))
    assert result.minimum_samples == 3
    assert all(w.diagnostic == "INSUFFICIENT_SAMPLES" for w in result.windows)


def test_single_sample_is_not_promoted_by_threshold_one(amount_cases):
    profile = amount_cases.publication((100,), minimum=1)
    result = score_personal_amount(amount_cases.request(profile=profile, minimum=1))
    assert result.minimum_samples == 2
    assert result.windows[0].diagnostic == "INSUFFICIENT_SAMPLES"


@pytest.mark.parametrize("state,status,reason", [
    ("NOT_COLLECTED", "NOT_COLLECTED", "MISSING_INPUT"),
    ("INSUFFICIENT_HISTORY", "INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    ("NOT_APPLICABLE", "NOT_APPLICABLE", "NOT_APPLICABLE"),
])
def test_missing_current_amount_preserves_cause_before_reference_status(amount_cases, state, status, reason):
    result = score_personal_amount(amount_cases.request(profile=False, states={"amount_minor": state}))
    for window in result.windows:
        assert window.result.status == status and window.result.reasons == (reason,)
        assert window.result.score is None and not window.result.bases


@pytest.mark.parametrize("expired", [False, True])
def test_missing_or_expired_profile_never_returns_zero_score(amount_cases, expired):
    profile = amount_cases.publication()
    supplied = amount_cases.request(profile=profile if expired else False,
        at=profile.state.published.profile.valid_until if expired else None)
    result = score_personal_amount(supplied)
    for window in result.windows:
        assert window.diagnostic == ("PROFILE_EXPIRED" if expired else "PROFILE_NOT_SUPPLIED")
        assert window.result.score is None and not window.result.bases
        assert (window.evidence is not None) == expired


def test_scoring_uses_published_values_excluding_withheld_large_purchase(amount_cases, personalized):
    profile = personalized.publication(include_large=True)
    result = score_personal_amount(amount_cases.request(115, profile=profile))
    assert profile.state.candidate.snapshot.long_term.included_count == 4
    assert result.windows[1].evidence.distribution.observation.valid_sample_count == 3
    assert result.windows[1].result.score.value == 50.0
    assert result.windows[1].result.bases[0].profile == profile.expected_reference


@pytest.mark.parametrize("change", ["currency", "unit", "user", "reference", "raw_amount", "version", "future"])
def test_scoring_revalidates_wrong_currency_unit_reference_and_version(amount_cases, change):
    supplied = amount_cases.request()
    if change in {"currency", "unit", "raw_amount"}:
        raw = supplied.cold.raw_features.features[0]
        updates = {"currency": "USD"} if change == "currency" else {"unit": "PURCHASE_COUNT"} if change == "unit" else {"value": 999}
        vector = supplied.cold.raw_features.model_copy(update={"features": (
            raw.model_copy(update=updates), *supplied.cold.raw_features.features[1:])})
        supplied = supplied.model_copy(update={"cold": supplied.cold.model_copy(update={"raw_features": vector})})
    elif change == "version":
        config = supplied.config.model_copy(update={"features": (
            supplied.config.features[0].model_copy(update={"score_version": "unknown"}), *supplied.config.features[1:])})
        supplied = supplied.model_copy(update={"config": config})
    else:
        publication = supplied.personal
        updates = {"available_at": amount_cases.cutoff + timedelta(days=1)} if change == "future" else {
            "expected_reference": publication.expected_reference.model_copy(update={
                "user_id" if change == "user" else "version": "other"})}
        supplied = supplied.model_copy(update={"personal": publication.model_copy(update=updates)})
    with pytest.raises(ValueError):
        score_personal_amount(supplied)


@pytest.mark.parametrize("change", [
    {"spread_multiplier": 0.0}, {"spread_multiplier": float("inf")}, {"spread_multiplier": float("nan")},
    {"relative_scale_floor": -0.1}, {"relative_scale_floor": 1.1},
    {"minimum_scale_minor": 0}, {"minimum_scale_minor": True}, {"minimum_scale_minor": 0.1},
    {"version": "unknown"},
])
def test_invalid_amount_policy_is_rejected(change):
    with pytest.raises(ValidationError):
        PersonalAmountPolicy(**change)


def test_roundtrip_reexecution_timezone_and_input_immutability(amount_cases):
    supplied = amount_cases.request(115)
    before = supplied.model_dump_json()
    result = score_personal_amount(supplied)
    restored = PersonalAmountResult.model_validate_json(result.model_dump_json())
    assert restored.validate_for_input(supplied) == result == score_personal_amount(supplied)
    data = supplied.model_dump(mode="python")
    data["personal"]["available_at"] = data["personal"]["available_at"].astimezone(timezone.utc)
    equivalent = score_personal_amount(PersonalizedScoringInput.model_validate(data))
    assert equivalent.input_sha256 == result.input_sha256 and equivalent == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("change", ["score", "evidence", "input_hash", "policy", "basis", "window_order"])
def test_result_rejects_detached_or_changed_evidence(amount_cases, change):
    supplied = amount_cases.request(115)
    data = score_personal_amount(supplied).model_dump(mode="python")
    if change == "score":
        data["windows"][0]["result"]["score"]["value"] = 99.0
    elif change == "evidence":
        data["windows"][0]["calculation"]["median_minor"]["numerator"] = 90
    elif change == "input_hash":
        data["input_sha256"] = "b" * 64
    elif change == "policy":
        data["policy"]["spread_multiplier"] = 2.0
    elif change == "basis":
        data["windows"][0]["result"]["bases"][0]["profile"]["version"] = "other"
    else:
        data["windows"] = data["windows"][::-1]
    with pytest.raises(ValueError):
        PersonalAmountResult.model_validate(data).validate_for_input(supplied)


def test_policy_parameters_are_hashed_even_when_formula_version_matches(amount_cases):
    supplied = amount_cases.request(115)
    normal = score_personal_amount(supplied)
    adjusted = score_personal_amount(supplied, PersonalAmountPolicy(spread_multiplier=6.0))
    assert normal.policy.version == adjusted.policy.version
    assert normal.policy_sha256 != adjusted.policy_sha256
    assert (normal.windows[0].result.score.value, adjusted.windows[0].result.score.value) == (50.0, 25.0)


def test_amount_must_be_requested_before_scoring(amount_cases):
    data = amount_cases.request().model_dump(mode="python")
    data["cold"]["request"]["evaluation"]["requested_features"] = ("purchase_count_10m", "kst_hour")
    data["cold"]["raw_features"]["features"] = data["cold"]["raw_features"]["features"][1:]
    data["cold"]["selection"]["features"] = data["cold"]["selection"]["features"][1:]
    data["config"]["features"] = data["config"]["features"][1:]
    supplied = PersonalizedScoringInput.model_validate(data)
    with pytest.raises(ValueError, match="requested amount"):
        score_personal_amount(supplied)


def test_window_calculation_failure_preserves_other_candidate(amount_cases, monkeypatch):
    original = amount_module._calculation
    calls = 0

    def fail_long(distribution, policy):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise ArithmeticError("synthetic computation failure")
        return original(distribution, policy)

    monkeypatch.setattr(amount_module, "_calculation", fail_long)
    result = score_personal_amount(amount_cases.request(115))
    assert result.windows[0].result.score.value == 50.0
    assert result.windows[1].diagnostic == "CALCULATION_FAILED"
    assert result.windows[1].result.score is None
    assert result.windows[1].result.bases[0].profile == result.profile.context.profile.reference
