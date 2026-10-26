"""Shared mathematical definitions, including quantile interpolation and circular time."""

import math

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.personal_profiles import PersonalProfileConfig
from copenhagen_fds.profile_statistics import circular_summary, numeric_summary


def test_even_sample_quantiles_and_population_variance():
    result = numeric_summary((0, 10, 20, 30))
    assert (result.mean, result.median, result.q25, result.q75, result.q05, result.q95) == (15, 15, 7.5, 22.5, 1.5, 28.5)
    assert result.population_stddev == pytest.approx(math.sqrt(125))
    assert result.median_absolute_deviation == 10


def test_uniform_hour_distribution_has_no_mean_direction():
    result = circular_summary((1,) * 24)
    assert result.mean_position is None and result.concentration <= 1e-12


def test_empty_numeric_input_and_extreme_amount_do_not_create_invalid_statistics():
    with pytest.raises(ValueError, match="at least one"):
        numeric_summary(())
    with pytest.raises(ValueError, match="finite"):
        numeric_summary((10**400,))


@pytest.mark.parametrize("changes", [{"recent_days": 91}, {"min_samples": 0}, {"timezone": "UTC"}])
def test_invalid_config_cannot_define_a_profile(changes):
    with pytest.raises(ValidationError):
        PersonalProfileConfig(**changes)
