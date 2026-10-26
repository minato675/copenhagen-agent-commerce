from datetime import timedelta, timezone
from fractions import Fraction

import pytest

from copenhagen_fds.contracts.personal_readiness import PersonalReadinessResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.personal_readiness import _ramp, _unit_float, evaluate_personal_readiness


@pytest.mark.parametrize("value,expected", [(None, None), (0, 0), (2, 0), (3, Fraction(1, 2)), (4, 1), (5, 1)])
def test_ramp_boundaries_and_missing_are_distinct(value, expected):
    assert _ramp(value, 2, 4) == expected


def test_extreme_ramp_preserves_open_interval_and_exact_endpoints():
    denominator = 10**1000
    for value in (Fraction(1, denominator), Fraction(denominator - 1, denominator)):
        assert 0 < _unit_float(value) < 1
    assert _unit_float(Fraction(0)) == 0 and _unit_float(Fraction(1)) == 1
    assert _ramp(10**1000 + 1, 10**1000, 10**1000 + 2) == Fraction(1, 2)


@pytest.mark.parametrize("count,expected", [(0, 0), (1, 0), (2, 0), (3, 0.5), (4, 1), (5, 1)])
def test_effective_sample_boundaries(readiness_cases, count, expected):
    profile = readiness_cases.publication([{"days": d} for d in range(1, count + 1)])
    result = evaluate_personal_readiness(readiness_cases.request(profile=profile))
    assert result.features[0].windows[0].personal_weight == expected


def test_profile_minimum_cannot_be_bypassed_by_lower_config(readiness_cases):
    profile = readiness_cases.publication([{"days": d} for d in range(1, 7)], minimum=5)
    feature = evaluate_personal_readiness(readiness_cases.request(profile=profile)).features[0]
    assert (feature.effective_minimum_samples, feature.effective_full_samples) == (5, 6)
    assert feature.windows[0].personal_weight == 1


@pytest.mark.parametrize("span,expected", [(0, 0), (9, 0), (10, 0), (15, 0.5), (20, 1), (21, 1)])
def test_observed_span_boundaries_do_not_grow_with_idle_time(readiness_cases, span, expected):
    last = readiness_cases.cutoff - timedelta(days=1)
    records = [{"at": last - timedelta(seconds=span * i / 3)} for i in range(4)]
    settings = {"amount_minor": {"minimum_observed_seconds": 10.0, "full_observed_seconds": 20.0}}
    profile = readiness_cases.publication(records)
    row = evaluate_personal_readiness(readiness_cases.request(profile=profile, settings=settings)).features[0].windows[0]
    assert row.measures.observed_seconds == span and row.personal_weight == expected


@pytest.mark.parametrize("missing,expected", [(0, 1), (1, 0.5), (2, 0), (3, 0)])
def test_available_fraction_boundaries_include_missing_observations(readiness_cases, missing, expected):
    records = [{"days": 1 + i / 2, "missing": {"kst_hour": "NOT_COLLECTED"} if i < missing else None} for i in range(8)]
    settings = {"kst_hour": {"full_samples": 4, "minimum_available_fraction": 0.75, "full_available_fraction": 1.0}}
    row = readiness_cases.feature(evaluate_personal_readiness(readiness_cases.request(
        profile=readiness_cases.publication(records), settings=settings)), "kst_hour").windows[0]
    assert row.measures.available_fraction == (8 - missing) / 8
    assert row.personal_weight == expected


@pytest.mark.parametrize("age,expected", [(86399, 1), (86400, 1), (129600, 0.5), (172800, 0), (172801, 0)])
def test_freshness_uses_evaluation_time_and_boundary_decay(readiness_cases, age, expected):
    profile = readiness_cases.publication()
    last = readiness_cases.cutoff - timedelta(days=1)
    settings = {"amount_minor": {"fresh_for_seconds": 86400.0, "stale_after_seconds": 172800.0}}
    # Evaluation must be after the published cutoff; move the freshness boundaries
    # together to include the profile publication at all tested ages.
    settings["amount_minor"] = {k: v + 10 for k, v in settings["amount_minor"].items()}
    row = evaluate_personal_readiness(readiness_cases.request(profile=profile, at=last + timedelta(seconds=age + 10),
        settings=settings)).features[0].windows[0]
    assert row.measures.recency_seconds == age + 10 and row.personal_weight == expected


def test_missing_profile_empty_profile_zero_span_and_zero_fraction_are_distinct(readiness_cases):
    absent = evaluate_personal_readiness(readiness_cases.request(profile=False)).features[0].windows[0]
    empty = evaluate_personal_readiness(readiness_cases.request(profile=readiness_cases.publication([]))).features[0].windows[0]
    same_time = readiness_cases.publication([{"days": 1}] * 4)
    zero_span = evaluate_personal_readiness(readiness_cases.request(profile=same_time)).features[0].windows[0]
    missing = readiness_cases.publication([{"days": d, "missing": {"kst_hour": "NOT_COLLECTED"}} for d in (1, 2, 3, 4)])
    zero_fraction = readiness_cases.feature(evaluate_personal_readiness(readiness_cases.request(profile=missing)), "kst_hour").windows[0]
    assert absent.measures.valid_samples is None and absent.factors.samples is None
    assert empty.measures.valid_samples == 0 and empty.measures.available_fraction is None
    assert zero_span.measures.observed_seconds == 0 and zero_span.measures.recency_seconds > 0
    assert zero_fraction.measures.available_fraction == 0 and zero_fraction.measures.observed_seconds is None
    assert all(w.personal_weight == 0 for w in (absent, empty, zero_span, zero_fraction))


@pytest.mark.parametrize("seconds", [600, 3600, 86400])
@pytest.mark.parametrize("stream,prefix", [("purchase_coverage", "purchase"), ("attempt_coverage", "attempt")])
def test_count_source_coverage_boundary_and_stream_are_checked(readiness_cases, seconds, stream, prefix):
    name = f"{prefix}_count_" + {600: "10m", 3600: "1h", 86400: "24h"}[seconds]
    for delta, expected in ((0, 1), (1, 0)):
        records = []
        for day in (4, 3, 2, 1):
            occurred = readiness_cases.cutoff - timedelta(days=day)
            evaluated = occurred + timedelta(microseconds=1)
            records.append({"at": occurred, stream: {"status": "COLLECTED", "complete_until": evaluated,
                "complete_from": evaluated - timedelta(seconds=seconds) + timedelta(microseconds=delta)}})
        result = evaluate_personal_readiness(readiness_cases.request(profile=readiness_cases.publication(records)))
        row = readiness_cases.feature(result, name).windows[0]
        assert row.personal_weight == expected
        assert row.measures.coverage_checked_samples == 4
        assert row.measures.coverage_supported_samples == (4 if expected else 0)
        assert result.features[0].windows[0].coverage_status == "NOT_REQUIRED"


def test_uncollected_or_partial_source_cannot_support_available_counts(readiness_cases):
    records = [{"days": d} for d in (4, 3, 2)]
    records.append({"days": 1, "attempt_coverage": {"status": "NOT_COLLECTED"}})
    result = evaluate_personal_readiness(readiness_cases.request(profile=readiness_cases.publication(records)))
    row = readiness_cases.feature(result, "attempt_count_10m").windows[0]
    assert row.measures.coverage_checked_samples == 4 and row.measures.coverage_supported_samples == 3
    assert row.personal_weight == 0 and "INCOMPLETE_SOURCE_COVERAGE" in row.reasons
    assert readiness_cases.feature(result, "purchase_count_10m").windows[0].personal_weight == 1


def test_readiness_roundtrip_reexecution_and_timezone_preserve_input(readiness_cases):
    supplied = readiness_cases.request()
    before = supplied.model_dump_json()
    result = evaluate_personal_readiness(supplied)
    assert PersonalReadinessResult.model_validate_json(result.model_dump_json()).validate_for_input(supplied) == result
    data = supplied.model_dump()
    data["cold"]["request"]["evaluation"]["evaluated_at"] = data["cold"]["request"]["evaluation"]["evaluated_at"].astimezone(timezone.utc)
    assert evaluate_personal_readiness(PersonalizedScoringInput.model_validate(data)) == result
    assert supplied.model_dump_json() == before


@pytest.mark.parametrize("change", ["weight", "measure", "axis", "coverage", "hash", "threshold", "reason"])
def test_detached_readiness_evidence_is_rejected(readiness_cases, change):
    supplied = readiness_cases.request()
    data = evaluate_personal_readiness(supplied).model_dump()
    row = data["features"][0]["windows"][0]
    if change == "weight":
        row["personal_weight"] = 0.5
    elif change == "measure":
        row["measures"]["recency_seconds"] = 0.0
    elif change == "axis":
        row["factors"]["freshness"] = None
    elif change == "coverage":
        row["coverage_status"] = "INCOMPLETE"
    elif change == "hash":
        data["input_sha256"] = "a" * 64
    elif change == "threshold":
        data["features"][0]["thresholds"]["full_samples"] = 5
    else:
        row["reasons"] = ["OTHER"]
    with pytest.raises(ValueError):
        PersonalReadinessResult.model_validate(data).validate_for_input(supplied)
