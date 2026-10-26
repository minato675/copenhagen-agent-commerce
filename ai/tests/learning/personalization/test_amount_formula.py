from fractions import Fraction
from math import nextafter

import pytest

from copenhagen_fds.contracts.personal_amount import PersonalAmountPolicy
from copenhagen_fds.personal_amount import _quantile, score_personal_amount


@pytest.mark.parametrize("amount,expected", [(0, 0.0), (100, 0.0), (103, 10.0), (115, 50.0), (130, 100.0), (1000, 100.0)])
def test_constant_reference_has_explicit_floor_and_hand_calculated_score(amount_cases, amount, expected):
    result = score_personal_amount(amount_cases.request(amount))
    for window in result.windows:
        assert window.result.score.value == expected
        assert window.calculation.zero_spread is True
        assert window.calculation.mad_minor.as_fraction() == 0
        assert window.calculation.saturation_distance_minor.as_fraction() == 30


def test_same_purchase_differs_between_personal_spending_patterns(amount_cases):
    low = amount_cases.publication((100, 100, 100))
    high = amount_cases.publication((1000, 1000, 1000))
    low_score = score_personal_amount(amount_cases.request(200, profile=low)).windows[0].result.score.value
    high_score = score_personal_amount(amount_cases.request(200, profile=high)).windows[0].result.score.value
    assert (low_score, high_score) == (100.0, 0.0)


def test_spread_and_linear_quantiles_have_independent_hand_calculated_evidence(amount_cases):
    profile = amount_cases.publication((80, 90, 100, 110, 120))
    result = score_personal_amount(amount_cases.request(115, profile=profile))
    calculation = result.windows[0].calculation
    assert calculation.median_minor.as_fraction() == 100
    assert calculation.q95_minor.as_fraction() == 118
    assert calculation.iqr_minor.as_fraction() == 20
    assert calculation.mad_minor.as_fraction() == 10
    assert calculation.saturation_distance_minor.as_fraction() == 60
    assert result.windows[0].result.score.value == 25.0
    assert not calculation.zero_spread


def test_fixed_reference_score_is_monotonic_and_bounded(amount_cases):
    profile = amount_cases.publication((80, 90, 100, 110, 120))
    values = [score_personal_amount(amount_cases.request(x, profile=profile)).windows[0].result.score.value
              for x in (0, 80, 99, 100, 101, 110, 140, 160, 10000, 10**1000)]
    assert values == sorted(values)
    assert all(0 <= value <= 100 for value in values)
    assert values[0] == 0 and values[-1] == 100


@pytest.mark.parametrize("currency", ["KRW", "USD", "KWD"])
def test_one_minor_unit_and_zero_amount_are_preserved(amount_cases, currency):
    profile = amount_cases.publication((0, 0), currency=currency)
    policy = PersonalAmountPolicy(spread_multiplier=1.0)
    zero = score_personal_amount(amount_cases.request(0, profile=profile, currency=currency), policy)
    one = score_personal_amount(amount_cases.request(1, profile=profile, currency=currency), policy)
    assert zero.windows[0].result.score.value == 0
    assert one.windows[0].result.score.value == 100
    assert one.raw.currency == currency and one.raw.unit == "MINOR_UNIT"


def test_large_integer_reference_keeps_single_minor_unit_deviation(amount_cases):
    center = 2**60
    profile = amount_cases.publication((center, center))
    policy = PersonalAmountPolicy(relative_scale_floor=0.0, spread_multiplier=2.0)
    result = score_personal_amount(amount_cases.request(center + 1, profile=profile), policy)
    assert result.windows[0].calculation.median_minor.as_fraction() == center
    assert result.windows[0].result.score.value == 50.0


def test_extreme_policy_scale_preserves_positive_deviation_underflow(amount_cases):
    profile = amount_cases.publication((10**200, 10**200))
    result = score_personal_amount(amount_cases.request(10**200 + 1, profile=profile),
                                  PersonalAmountPolicy(spread_multiplier=1e308))
    assert result.windows[0].result.score.value == nextafter(0.0, 1.0)
    assert result.windows[0].result.reasons == ("PATTERN_DEVIATION",)


@pytest.mark.parametrize("histogram,percent,expected", [
    ([(0, 1), (1, 1)], 50, Fraction(1, 2)),
    ([(0, 1), (1, 1)], 95, Fraction(19, 20)),
    ([(100, 10**9), (200, 10**9)], 50, Fraction(150)),
    ([(1, 0), (100, 2)], 50, Fraction(100)),
])
def test_exact_counted_quantiles_do_not_expand_histogram(histogram, percent, expected):
    assert _quantile(histogram, percent) == expected
