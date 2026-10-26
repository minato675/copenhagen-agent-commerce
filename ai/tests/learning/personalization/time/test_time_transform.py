from datetime import datetime, timedelta, timezone

import pytest

from copenhagen_fds.personal_time import score_personal_times


@pytest.mark.parametrize("text,expected", [
    ("2026-04-26T23:59:59.999999+09:00", (23, 6)),
    ("2026-04-27T00:00:00+09:00", (0, 0)),
    ("2026-04-30T23:59:59.999999+09:00", (23, 3)),
    ("2026-05-01T00:00:00+09:00", (0, 4)),
])
def test_real_transform_preserves_midnight_and_week_boundary(time_cases, text, expected):
    result = score_personal_times(time_cases.request(current=datetime.fromisoformat(text)))
    assert tuple(f.raw.value for f in result.features) == expected
    assert tuple(f.windows[0].calculation.bin_index for f in result.features) == expected


@pytest.mark.parametrize("offset", [-12, 0, 9, 14])
def test_same_instant_different_timezone_has_identical_raw_score_and_hash(time_cases, offset):
    current = datetime.fromisoformat("2026-04-27T00:00:00+09:00")
    expected = score_personal_times(time_cases.request(current=current))
    alternate = current.astimezone(timezone(timedelta(hours=offset)))
    actual = score_personal_times(time_cases.request(current=alternate))
    assert actual == expected and actual.model_dump_json() == expected.model_dump_json()


def test_missing_purchase_time_is_not_replaced_by_evaluation_clock(time_cases):
    result = score_personal_times(time_cases.request(current=False))
    assert all(f.raw.value is None for f in result.features)
    assert all(w.diagnostic == "INPUT_NOT_COLLECTED" and w.result.score is None
               for f in result.features for w in f.windows)


def test_current_purchase_time_drives_bins_even_when_evaluation_is_later(time_cases):
    current = time_cases.occurred(12, 6)
    result = score_personal_times(time_cases.request(current=current))
    assert tuple(f.raw.value for f in result.features) == (12, 6)
    assert result.profile.context.profile.as_of.weekday() == 4


def test_future_purchase_occurrence_is_rejected_before_scoring(time_cases):
    with pytest.raises(ValueError):
        time_cases.request(current=time_cases.cutoff + timedelta(days=1))
