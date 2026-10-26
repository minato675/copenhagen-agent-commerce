"""Hand-calculated cases and numerical boundaries for Cold distance scoring."""

from math import isfinite

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.baselines import FeatureReference
from copenhagen_fds.contracts.behavior import AnomalyScore
from copenhagen_fds.contracts.features import FEATURE_SPECS, RawFeatureValue
from copenhagen_fds.scoring import SCORING_VERSION, score_feature


def pair(name, value, center, scale, method="UPPER_DISTANCE"):
    unit, window = FEATURE_SPECS[name]
    raw = RawFeatureValue(feature_id=name, status="AVAILABLE", value=value,
                          unit=unit, window_seconds=window,
                          currency=None if name.startswith("kst_") else "KRW")
    reference = FeatureReference(feature_id=name, unit=unit, window_seconds=window,
                                 center=center, scale=scale, method=method,
                                 period={"kst_hour": 24, "kst_day_of_week": 7}.get(name))
    return raw, reference


@pytest.mark.parametrize("name,value,center,scale,method,expected", [
    ("amount_minor", 0, 10000, 20000, "UPPER_DISTANCE", 0),
    ("amount_minor", 9999, 10000, 20000, "UPPER_DISTANCE", 0),
    ("amount_minor", 10000, 10000, 20000, "UPPER_DISTANCE", 0),
    ("amount_minor", 10001, 10000, 20000, "UPPER_DISTANCE", 0.005),
    ("amount_minor", 20000, 10000, 20000, "UPPER_DISTANCE", 50),
    ("amount_minor", 29999, 10000, 20000, "UPPER_DISTANCE", 99.995),
    ("amount_minor", 30000, 10000, 20000, "UPPER_DISTANCE", 100),
    ("amount_minor", 30001, 10000, 20000, "UPPER_DISTANCE", 100),
    ("amount_minor", 0, 10000, 20000, "ABSOLUTE_DISTANCE", 50),
    ("amount_minor", 10000, 10000, 20000, "ABSOLUTE_DISTANCE", 0),
    ("amount_minor", 20000, 10000, 20000, "ABSOLUTE_DISTANCE", 50),
    ("purchase_count_10m", 0, 2, 8, "UPPER_DISTANCE", 0),
    ("purchase_count_10m", 2, 2, 8, "UPPER_DISTANCE", 0),
    ("purchase_count_10m", 3, 2, 8, "UPPER_DISTANCE", 12.5),
    ("purchase_count_10m", 6, 2, 8, "UPPER_DISTANCE", 50),
    ("purchase_count_10m", 10, 2, 8, "UPPER_DISTANCE", 100),
    ("attempt_count_1h", 7, 3, 8, "ABSOLUTE_DISTANCE", 50),
    ("kst_hour", 23, 0, 12, "CIRCULAR_DISTANCE", 100 / 12),
    ("kst_hour", 0, 23, 12, "CIRCULAR_DISTANCE", 100 / 12),
    ("kst_hour", 0, 0, 12, "CIRCULAR_DISTANCE", 0),
    ("kst_hour", 6, 0, 12, "CIRCULAR_DISTANCE", 50),
    ("kst_hour", 12, 0, 12, "CIRCULAR_DISTANCE", 100),
    ("kst_hour", 0, 23.5, 1, "CIRCULAR_DISTANCE", 50),
    ("kst_day_of_week", 6, 0, 2, "CIRCULAR_DISTANCE", 50),
    ("kst_day_of_week", 0, 6, 2, "CIRCULAR_DISTANCE", 50),
    ("kst_day_of_week", 3, 0, 3.5, "CIRCULAR_DISTANCE", 600 / 7),
])
def test_representative_scores_and_boundaries(name, value, center, scale, method, expected):
    score = score_feature(*pair(name, value, center, scale, method))
    assert score.value == pytest.approx(expected)
    assert score.unit == "BEHAVIOR_ANOMALY_0_100"
    assert AnomalyScore.model_validate_json(score.model_dump_json()) == score


@pytest.mark.parametrize("name", FEATURE_SPECS)
def test_all_features_use_their_declared_unit_and_window(name):
    method = "CIRCULAR_DISTANCE" if name.startswith("kst_") else "UPPER_DISTANCE"
    assert score_feature(*pair(name, 1, 0, 2, method)).value == 50


@pytest.mark.parametrize("value,center,scale,expected", [
    (10**400, 1, 1, 100),
    (10**400, 1e308, 1e308, 100),
    (1, 0, 5e-324, 100),
    (0, 1e308, 1e308, 100),
    (1, 0, 1e308, 1e-306),
])
def test_finite_extremes_do_not_overflow(value, center, scale, expected):
    score = score_feature(*pair("amount_minor", value, center, scale, "ABSOLUTE_DISTANCE"))
    assert score.value == expected
    assert isfinite(score.value)


def test_tiny_positive_deviation_cannot_become_reference_match():
    score = score_feature(*pair("amount_minor", 0, 5e-324, 1e308, "ABSOLUTE_DISTANCE"))
    assert 0 < score.value < 1e-300


@pytest.mark.parametrize("state", ["NOT_COLLECTED", "INSUFFICIENT_HISTORY", "NOT_APPLICABLE"])
def test_unavailable_raw_value_is_never_scored_as_zero(state):
    raw, reference = pair("amount_minor", 0, 0, 1)
    raw = RawFeatureValue.model_validate({**raw.model_dump(), "status": state, "value": None})
    with pytest.raises(ValueError, match="available raw"):
        score_feature(raw, reference)


@pytest.mark.parametrize("change", [
    {"feature_id": "purchase_count_1h"}, {"unit": "KST_HOUR"}, {"window_seconds": 3600},
    {"method": "CIRCULAR_DISTANCE", "period": 24}, {"center": -1},
    {"scale": 0}, {"scale": -1}, {"scale": float("nan")}, {"center": float("inf")},
])
def test_invalid_reference_is_rejected_instead_of_returning_plausible_score(change):
    raw, reference = pair("amount_minor", 1, 0, 1)
    with pytest.raises((ValueError, ValidationError)):
        score_feature(raw, reference.model_copy(update=change))


def test_circular_reference_must_use_valid_period_and_center():
    raw, reference = pair("kst_hour", 1, 0, 12, "CIRCULAR_DISTANCE")
    for change in ({"period": 12}, {"center": 24}, {"method": "UPPER_DISTANCE"}):
        with pytest.raises(ValueError):
            score_feature(raw, reference.model_copy(update=change))


def test_fixed_version_and_inputs_are_deterministic_and_do_not_mutate():
    raw, reference = pair("amount_minor", 12345, 10000, 20000)
    before = (raw.model_dump_json(), reference.model_dump_json())
    outputs = [score_feature(raw, reference).model_dump_json() for _ in range(5)]
    assert SCORING_VERSION == "cold-distance-linear-v1"
    assert len(set(outputs)) == 1
    assert before == (raw.model_dump_json(), reference.model_dump_json())
