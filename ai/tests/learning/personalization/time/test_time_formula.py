from fractions import Fraction
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.personal_time import PersonalTimePolicy
from copenhagen_fds.personal_time import _calculation, _score, score_personal_times


def calculate(counts, position, policy=PersonalTimePolicy()):
    distribution = SimpleNamespace(feature_id="kst_hour" if len(counts) == 24 else "kst_day_of_week",
        histogram=tuple(SimpleNamespace(count=n) for n in counts))
    calc = _calculation(distribution, position, policy)
    return _score(calc).value, calc


@pytest.mark.parametrize("period", [7, 24])
def test_circular_boundary_neighbors_are_symmetric(period):
    counts = [0] * period
    counts[0] = 4
    mode, _ = calculate(counts, 0)
    left, left_calc = calculate(counts, period - 1)
    right, _ = calculate(counts, 1)
    distant, _ = calculate(counts, 3)
    assert (mode, left, right, distant) == (0, 60, 60, 80)
    assert left_calc.bin_status == "UNOBSERVED" and left_calc.next_count == 4


@pytest.mark.parametrize("period", [7, 24])
def test_rotating_activity_and_current_bin_preserves_scores(period):
    counts = [0] * period
    counts[0], counts[3] = 8, 2
    expected = [calculate(counts, i)[0] for i in range(period)]
    for shift in range(period):
        shifted = counts[-shift:] + counts[:-shift] if shift else counts
        assert [calculate(shifted, (i + shift) % period)[0] for i in range(period)] == expected


@pytest.mark.parametrize("period", [7, 24])
def test_uniform_activity_has_no_relative_rare_bin(period):
    assert all(calculate([2] * period, i)[0] == 0 for i in range(period))


def test_hand_calculation_preserves_exact_mass_and_policy_effect():
    counts = [0] * 24
    counts[0], counts[1], counts[2] = 8, 2, 1
    score, calc = calculate(counts, 1)
    assert (calc.smoothed_mass.as_fraction(), calc.peak_mass.as_fraction(), calc.total_mass.as_fraction()) == (
        Fraction(21, 4), Fraction(19, 2), Fraction(81, 2))
    assert score == float(Fraction(850, 19))
    assert calc.direct_count == 2 and calc.bin_status == "OBSERVED"
    raw, _ = calculate(counts, 1, PersonalTimePolicy(hour_neighbor_weight=0.0))
    regularized, _ = calculate(counts, 1, PersonalTimePolicy(pseudocount=10.0))
    assert regularized < score < raw


def test_hour_and_weekday_smoothing_weights_are_independent():
    policy = PersonalTimePolicy(hour_neighbor_weight=0.0, weekday_neighbor_weight=0.5)
    assert calculate([4] + [0] * 23, 1, policy)[0] == 80
    assert calculate([4] + [0] * 6, 1, policy)[0] == 40


@pytest.mark.parametrize("pseudocount", [5e-324, 1.0, 1e308])
def test_extreme_counts_and_correction_stay_finite_bounded(pseudocount):
    counts = [10**1000, 10**1000 - 1] + [0] * 22
    policy = PersonalTimePolicy(hour_neighbor_weight=0.0, pseudocount=pseudocount)
    scores = [calculate(counts, i, policy)[0] for i in range(24)]
    assert scores[0] == 0 and scores[1] > 0
    assert all(0 <= score <= 100 for score in scores)


def test_more_reference_support_strengthens_unobserved_bin_evidence():
    scores = [calculate([n] + [0] * 23, 12)[0] for n in (2, 10, 1000)]
    assert 0 < scores[0] < scores[1] < scores[2] < 100


def test_multimodal_activity_keeps_both_peaks_without_linear_mean(time_cases):
    profile = time_cases.publication([{"at": time_cases.occurred(hour)} for hour in (6, 6, 18, 18)])
    scores = []
    for hour in (6, 12, 18):
        result = score_personal_times(time_cases.request(current=time_cases.occurred(hour), profile=profile))
        scores.append(result.features[0].windows[0].result.score.value)
    assert scores == [0, 100 * 2 / 3, 0]


def test_night_user_and_day_user_get_different_scores_for_same_hour(time_cases):
    scores = []
    for user, hour in (("a", 0), ("b", 12)):
        profile = time_cases.publication([{"at": time_cases.occurred(hour)}] * 3, user=user)
        result = score_personal_times(time_cases.request(profile=profile, user=user))
        scores.append(result.features[0].windows[0].result.score.value)
    assert scores == [0, 75]


def test_weekend_activity_is_not_an_intrinsic_risk(time_cases):
    profile = time_cases.publication([{"at": time_cases.occurred(12, day)} for day in (5, 5, 6, 6)])
    scores = []
    for day in (5, 6, 2):
        result = score_personal_times(time_cases.request(current=time_cases.occurred(12, day), profile=profile))
        scores.append(time_cases.feature(result, "kst_day_of_week").windows[0].result.score.value)
    assert scores[0] == scores[1] == 0 and scores[2] > 0
