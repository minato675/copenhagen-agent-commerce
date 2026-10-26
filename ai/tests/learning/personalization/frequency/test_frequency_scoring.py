from datetime import timezone
from fractions import Fraction
from contextlib import nullcontext

import pytest
from pydantic import ValidationError

import copenhagen_fds.personal_frequency as frequency_module
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES, PersonalFrequencyPolicy, PersonalFrequencyResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.contracts.profile_policy import ProfilePolicyConfig
from copenhagen_fds.personal_frequency import score_personal_frequencies


@pytest.mark.parametrize("count,expected", [(0, 0.0), (1, 100 / 3), (2, 200 / 3), (3, 100.0), (10**1000, 100.0)])
def test_zero_history_and_increased_counts_have_hand_calculated_scores(frequency_cases, count, expected):
    result = score_personal_frequencies(frequency_cases.request(count))
    assert [f.raw.feature_id for f in result.features] == list(FREQUENCY_FEATURES)
    for feature in result.features:
        for window in feature.windows:
            assert window.result.score.value == expected
            assert window.calculation.zero_spread
            assert window.calculation.saturation_distance_count.as_fraction() == 3


def test_same_count_differs_between_users_with_normal_frequent_purchases(frequency_cases):
    low = frequency_cases.publication((2, 2, 2), user="a")
    frequent = frequency_cases.publication((20, 20, 20), user="b")
    a = score_personal_frequencies(frequency_cases.request(5, profile=low, user="a"))
    b = score_personal_frequencies(frequency_cases.request(5, profile=frequent, user="b"))
    assert all(f.windows[0].result.score.value == 100 for f in a.features)
    assert all(f.windows[0].result.score.value == 0 for f in b.features)
    assert a.profile.context.profile.reference.user_id != b.profile.context.profile.reference.user_id


def test_purchase_and_attempt_counts_and_time_windows_use_separate_distributions(frequency_cases):
    baseline = dict(zip(FREQUENCY_FEATURES, (1, 10, 100, 2, 20, 200), strict=True))
    profile = frequency_cases.publication((baseline, baseline, baseline))
    current = {name: value for name, value in baseline.items()}
    current["attempt_count_10m"] = 5
    result = score_personal_frequencies(frequency_cases.request(current, profile=profile))
    for feature in result.features:
        expected = 100 if feature.raw.feature_id == "attempt_count_10m" else 0
        assert feature.windows[0].result.score.value == expected
        assert feature.windows[0].calculation.median_count.as_fraction() == baseline[feature.raw.feature_id]


def test_spread_and_fractional_quantiles_match_hand_calculation(frequency_cases):
    profile = frequency_cases.publication((0, 1, 2, 3, 4))
    result = score_personal_frequencies(frequency_cases.request(3, profile=profile))
    calc = result.features[0].windows[0].calculation
    assert (calc.median_count.as_fraction(), calc.q95_count.as_fraction(), calc.iqr_count.as_fraction(),
            calc.mad_count.as_fraction(), calc.saturation_distance_count.as_fraction()) == (2, Fraction(19, 5), 2, 1, 6)
    assert result.features[0].windows[0].result.score.value == 100 / 6


def test_increasing_counts_are_monotonic_bounded_and_large_integer_safe(frequency_cases):
    center = 2**60
    profile = frequency_cases.publication((center, center, center))
    policy = PersonalFrequencyPolicy(relative_scale_floor=0.0, spread_multiplier=2.0)
    scores = [score_personal_frequencies(frequency_cases.request(n, profile=profile), policy).features[0].windows[0].result.score.value
              for n in (0, center, center + 1, center + 2, 10**1000)]
    assert scores == [0.0, 0.0, 50.0, 100.0, 100.0]


def test_requested_frequency_subset_is_returned_in_canonical_order(frequency_cases):
    requested = ("attempt_count_24h", "amount_minor", "purchase_count_10m", "kst_hour")
    result = score_personal_frequencies(frequency_cases.request(requested=requested))
    assert [f.raw.feature_id for f in result.features] == ["purchase_count_10m", "attempt_count_24h"]


def test_no_requested_frequency_is_rejected(frequency_cases):
    with pytest.raises(ValueError, match="requested frequency"):
        score_personal_frequencies(frequency_cases.request(requested=("amount_minor",)))


@pytest.mark.parametrize("state", ["NOT_COLLECTED", "INSUFFICIENT_HISTORY", "NOT_APPLICABLE"])
def test_missing_current_count_is_distinct_from_observed_zero(frequency_cases, state):
    supplied = frequency_cases.request({"purchase_count_10m": state})
    result = score_personal_frequencies(supplied)
    missing, available = result.features[:2]
    assert missing.windows[0].diagnostic == "INPUT_" + state
    assert missing.windows[0].result.score is None and not missing.windows[0].result.bases
    assert available.windows[0].result.score.value == 0


@pytest.mark.parametrize("records,days,expected", [
    ((), (), ("EMPTY", "EMPTY")),
    ((0,), (1,), ("INSUFFICIENT_SAMPLES", "INSUFFICIENT_SAMPLES")),
    ((0, 0), (1, 8), ("INSUFFICIENT_SAMPLES", "SCORED")),
    ((0, 0), (8, 9), ("EMPTY", "SCORED")),
    ((0, 0), (1, 7), ("SCORED", "SCORED")),
])
def test_recent_and_long_term_windows_preserve_sample_sufficiency(frequency_cases, records, days, expected):
    profile = frequency_cases.publication(records, days=days)
    result = score_personal_frequencies(frequency_cases.request(profile=profile))
    assert all(tuple(w.diagnostic for w in f.windows) == expected for f in result.features)


def test_historical_missingness_and_feature_specific_minimums_are_preserved(frequency_cases):
    unavailable = {"purchase_count_1h": "INSUFFICIENT_HISTORY", "attempt_count_24h": "NOT_COLLECTED"}
    profile = frequency_cases.publication((unavailable, unavailable, 0))
    result = score_personal_frequencies(frequency_cases.request(profile=profile, minimum={"attempt_count_10m": 4}))
    purchase = frequency_cases.feature(result, "purchase_count_1h").windows[0]
    attempts = frequency_cases.feature(result, "attempt_count_24h").windows[0]
    assert purchase.diagnostic == attempts.diagnostic == "INSUFFICIENT_SAMPLES"
    assert purchase.evidence.distribution.insufficient_history_count == 2
    assert attempts.evidence.distribution.not_collected_count == 2
    assert frequency_cases.feature(result, "attempt_count_10m").minimum_samples == 4
    assert frequency_cases.feature(result, "attempt_count_10m").windows[0].diagnostic == "INSUFFICIENT_SAMPLES"
    assert result.features[0].windows[0].diagnostic == "SCORED"


def test_recent_and_long_term_scores_are_both_returned_without_selecting(frequency_cases):
    profile = frequency_cases.publication((2, 2, 20, 20, 20), days=(1, 2, 10, 11, 12))
    result = score_personal_frequencies(frequency_cases.request(5, profile=profile))
    assert [w.result.score.value for w in result.features[0].windows] == [100, 0]
    assert "stage" not in result.model_dump() and "personal_weight" not in result.model_dump()


@pytest.mark.parametrize("expired", [False, True])
def test_missing_or_expired_profile_keeps_null_scores(frequency_cases, expired):
    profile = frequency_cases.publication()
    supplied = frequency_cases.request(profile=profile if expired else False,
        at=profile.state.published.profile.valid_until if expired else None)
    result = score_personal_frequencies(supplied)
    for feature in result.features:
        assert feature.windows[0].diagnostic == ("PROFILE_EXPIRED" if expired else "PROFILE_NOT_SUPPLIED")
        assert feature.windows[0].result.score is None
        assert (feature.windows[0].evidence is not None) == expired


def test_published_profile_excludes_withheld_burst(frequency_cases):
    profile = frequency_cases.publication((0, 0, 0, 100), admission=ProfilePolicyConfig(min_reference_samples=3))
    assert profile.state.candidate.snapshot.long_term.included_count == 4
    assert profile.state.published.long_term.included_count == 3
    result = score_personal_frequencies(frequency_cases.request(3, profile=profile))
    assert result.features[0].windows[0].result.score.value == 100
    assert result.features[0].windows[0].calculation.median_count.as_fraction() == 0


@pytest.mark.parametrize("field,value", [("unit", "ATTEMPT_COUNT"), ("window_seconds", 3600), ("currency", "USD"), ("value", -1), ("value", True), ("value", 1.5)])
def test_input_count_units_windows_and_values_are_revalidated(frequency_cases, field, value):
    supplied = frequency_cases.request()
    raw = supplied.cold.raw_features.features[0].model_copy(update={field: value})
    vector = supplied.cold.raw_features.model_copy(update={"features": (raw, *supplied.cold.raw_features.features[1:])})
    supplied = supplied.model_copy(update={"cold": supplied.cold.model_copy(update={"raw_features": vector})})
    warning = pytest.warns(UserWarning, match="Pydantic serializer warnings") if type(value) is float else nullcontext()
    with warning, pytest.raises(ValueError):
        score_personal_frequencies(supplied)


def test_every_requested_frequency_version_is_checked(frequency_cases):
    data = frequency_cases.request().model_dump(mode="python")
    data["config"]["features"][-1]["score_version"] = "unknown"
    with pytest.raises(ValueError, match="supported score version"):
        score_personal_frequencies(PersonalizedScoringInput.model_validate(data))


@pytest.mark.parametrize("change", [
    {"spread_multiplier": 0.0}, {"spread_multiplier": float("inf")},
    {"relative_scale_floor": float("nan")}, {"relative_scale_floor": -0.1}, {"relative_scale_floor": 1.1},
    {"minimum_scale_count": 0}, {"minimum_scale_count": True}, {"minimum_scale_count": 1.5}, {"version": "unknown"},
])
def test_invalid_frequency_policy_is_rejected(change):
    with pytest.raises(ValidationError):
        PersonalFrequencyPolicy(**change)


def test_roundtrip_reexecution_timezone_and_input_immutability(frequency_cases):
    supplied = frequency_cases.request(1)
    before = supplied.model_dump_json()
    result = score_personal_frequencies(supplied)
    restored = PersonalFrequencyResult.model_validate_json(result.model_dump_json())
    assert restored.validate_for_input(supplied) == score_personal_frequencies(supplied)
    data = supplied.model_dump(mode="python")
    data["personal"]["available_at"] = data["personal"]["available_at"].astimezone(timezone.utc)
    assert score_personal_frequencies(PersonalizedScoringInput.model_validate(data)) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("change", ["score", "calculation", "basis", "feature_kind", "window", "missing_feature", "input_hash", "policy"])
def test_result_rejects_detached_scores_features_and_evidence(frequency_cases, change):
    supplied = frequency_cases.request(1)
    data = score_personal_frequencies(supplied).model_dump(mode="python")
    window = data["features"][0]["windows"][0]
    if change == "score":
        window["result"]["score"]["value"] = 99.0
    elif change == "calculation":
        window["calculation"]["saturation_distance_count"]["numerator"] = 999
    elif change == "basis":
        window["result"]["bases"][0]["profile"]["version"] = "other"
    elif change == "feature_kind":
        window["result"]["feature_id"] = "attempt_count_10m"
    elif change == "window":
        window["evidence"]["distribution"]["window_seconds"] = 3600
    elif change == "missing_feature":
        data["features"] = data["features"][:-1]
    elif change == "input_hash":
        data["input_sha256"] = "b" * 64
    else:
        data["policy"]["minimum_scale_count"] = 2
    with pytest.raises(ValueError):
        PersonalFrequencyResult.model_validate(data).validate_for_input(supplied)


def test_single_window_failure_preserves_other_features_and_window(frequency_cases, monkeypatch):
    original = frequency_module._calculation
    calls = 0

    def fail_once(distribution, policy):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise ArithmeticError("synthetic computation failure")
        return original(distribution, policy)

    monkeypatch.setattr(frequency_module, "_calculation", fail_once)
    result = score_personal_frequencies(frequency_cases.request(1))
    assert result.features[0].windows[1].diagnostic == "CALCULATION_FAILED"
    assert result.features[0].windows[1].result.score is None
    assert result.features[0].windows[0].result.score.value == 100 / 3
    assert all(w.diagnostic == "SCORED" for f in result.features[1:] for w in f.windows)
