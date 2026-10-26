"""Hand-calculated weighted statistics, without reference to the implementation loop."""

import math
from fractions import Fraction

import pytest

from copenhagen_fds.profile_statistics import circular_summary, weighted_numeric_summary, weighted_quantile


def test_user_balanced_moments_and_inverse_cdf_boundaries():
    result = weighted_numeric_summary((100,) * 9 + (1000,), (Fraction(1, 18),) * 9 + (Fraction(1, 2),))
    assert result.mean == 550
    assert result.population_stddev == 450
    assert (result.q05, result.median, result.q75, result.q95) == (100, 100, 1000, 1000)
    assert result.median_absolute_deviation == 0


def test_weight_scale_order_and_integer_precision_do_not_change_quantiles():
    values, weights = (2**60, 2**60 + 10, 2**60 + 20), (Fraction(1), Fraction(2), Fraction(1))
    result = weighted_numeric_summary(values, weights)
    assert result.population_stddev == pytest.approx(math.sqrt(50))
    assert result.median_absolute_deviation == 0
    assert weighted_numeric_summary(tuple(reversed(values)), tuple(w * 3 for w in reversed(weights))) == result
    assert weighted_quantile((0, 10), (Fraction(1, 3), Fraction(2, 3)), Fraction(1, 3)) == 0


def test_circular_summary_accepts_balanced_mass_across_midnight():
    masses = [0.0] * 24
    masses[23] = masses[0] = .5
    result = circular_summary(tuple(masses))
    assert result.mean_position == pytest.approx(23.5)
    assert result.concentration > .99


@pytest.mark.parametrize("values,weights", [((), ()), ((1,), ()), ((1,), (Fraction(0),))])
def test_invalid_weighted_observations_are_rejected(values, weights):
    with pytest.raises(ValueError):
        weighted_numeric_summary(values, weights)


def test_nonfinite_output_and_invalid_probability_are_rejected():
    with pytest.raises(ValueError, match="finite"):
        weighted_numeric_summary((10**400,), (Fraction(1),))
    with pytest.raises(ValueError, match="probability"):
        weighted_quantile((1,), (Fraction(1),), Fraction(2))
