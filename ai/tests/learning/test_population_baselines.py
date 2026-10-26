"""Story 4 artifact compatibility and explicit refusal of unsupported learned references."""

from datetime import datetime, timedelta

import pytest

from copenhagen_fds.contracts.baselines import PopulationBaseline, parameter_error
from copenhagen_fds.contracts.population_baselines import PopulationBaselineRequest, PopulationBaselineResult
from copenhagen_fds.population_baselines import derive_population_baseline


def _request(snapshot, **changes):
    data = {"expected_source_reference": snapshot.profile.reference, "currency": snapshot.profile.currency,
            "available_at": snapshot.profile.generated_at}
    data.update(changes)
    return PopulationBaselineRequest.model_validate(data)


def _supported(population):
    p = population
    sunday = datetime.fromisoformat("2026-04-26T23:00:00+09:00")
    return p.fit(p.sample("a", at=sunday, amount=100, counts=0),
                 p.sample("b", user="b", at=sunday + timedelta(hours=1), amount=300, counts=4))


def test_all_feature_units_references_and_story4_json_contract(population):
    snapshot = _supported(population)
    result = derive_population_baseline(snapshot, _request(snapshot))
    assert result.status == "COMPATIBLE" and len(result.baseline.parameters) == 9
    assert parameter_error(result.baseline.parameters) is None
    baseline = PopulationBaseline.model_validate_json(result.baseline.model_dump_json())
    assert baseline.validation_status == "UNVALIDATED"
    assert baseline.profile.currency == "KRW" and baseline.profile.origin == "SYNTHETIC"
    assert baseline.profile.observations == snapshot.profile.observations
    assert baseline.profile.reference.cohort_id == snapshot.cohort.cohort_id
    assert baseline.profile.reference != snapshot.profile.reference
    assert baseline.profile.reference.version.startswith("population-cold-v1-")
    amount = baseline.parameters[0]
    assert (amount.feature_id, amount.unit, amount.method, amount.center, amount.scale) == (
        "amount_minor", "MINOR_UNIT", "UPPER_DISTANCE", 100, 200)
    hours = next(p for p in baseline.parameters if p.feature_id == "kst_hour")
    assert hours.center == pytest.approx(23.5) and hours.scale == pytest.approx(.5) and hours.period == 24
    baseline.profile.validate_for_evaluation(user_id="new-user", currency="KRW", evaluated_at=baseline.available_at,
                                            expected_reference=baseline.profile.reference, feature_schema_version="cold-features-v1")
    assert PopulationBaselineResult.model_validate_json(result.model_dump_json()) == result


@pytest.mark.parametrize("hours", [(8, 20), (8, 10), (8, 8, 9, 10, 10)])
def test_multimodal_or_disconnected_time_is_not_compressed_into_a_center(population, hours):
    p = population
    users = tuple(str(i) for i in range(len(hours)))
    samples = tuple(p.sample(str(i), user=str(i), at=(p.cutoff - timedelta(days=1)).replace(hour=hour), amount=100 + i * 100)
                    for i, hour in enumerate(hours))
    snapshot = p.fit(*samples, users=users)
    result = derive_population_baseline(snapshot, _request(snapshot))
    assert result.status == "PARTIAL"
    hour = next(f for f in result.features if f.feature_id == "kst_hour")
    assert hour.parameter is None and hour.reason == "CIRCULAR_NOT_REPRESENTABLE"
    assert "kst_hour" not in {f.feature_id for f in result.baseline.parameters}
    assert len(p.feature(snapshot, "kst_hour").histogram) == 24


@pytest.mark.parametrize("mode,reason", [("empty", "EMPTY"), ("few", "INSUFFICIENT_SAMPLES"),
                                        ("one-user", "INSUFFICIENT_USERS"), ("constant", "ZERO_SCALE")])
def test_unavailable_sample_support_and_zero_scale_have_no_fake_parameters(population, mode, reason):
    p = population
    samples = () if mode == "empty" else ((p.sample("a"),) if mode == "few" else (
        (p.sample("a"), p.sample("a2")) if mode == "one-user" else (p.sample("a"), p.sample("b", user="b"))))
    snapshot = p.fit(*samples)
    result = derive_population_baseline(snapshot, _request(snapshot))
    assert result.status == "UNAVAILABLE" and result.baseline is None
    assert {feature.reason for feature in result.features} == {reason}


@pytest.mark.parametrize("mode,reason", [("reference", "REFERENCE_MISMATCH"), ("version", "VERSION_MISMATCH"),
                                        ("currency", "CURRENCY_MISMATCH"), ("schema", "VERSION_MISMATCH"),
                                        ("before-generation", "INVALID_AVAILABILITY"), ("expired", "INVALID_AVAILABILITY")])
def test_invalid_reference_currency_version_and_availability_return_reasons(population, mode, reason):
    snapshot = _supported(population)
    data = _request(snapshot).model_dump(mode="python")
    if mode in {"reference", "version"}:
        data["expected_source_reference"]["profile_id" if mode == "reference" else "version"] = "wrong"
    elif mode == "currency":
        data["currency"] = "USD"
    elif mode == "schema":
        data["feature_schema_version"] = "unknown"
    else:
        data["available_at"] = snapshot.profile.as_of if mode == "before-generation" else snapshot.profile.valid_until
    result = derive_population_baseline(snapshot, PopulationBaselineRequest.model_validate(data))
    assert result.status == "UNAVAILABLE" and result.issue == reason
    assert result.baseline is None and not result.features


def test_derived_policy_source_and_availability_have_reproducible_versions(population):
    snapshot = _supported(population)
    first = derive_population_baseline(snapshot, _request(snapshot))
    assert derive_population_baseline(snapshot, _request(snapshot)).model_dump_json() == first.model_dump_json()
    later = derive_population_baseline(snapshot, _request(snapshot, available_at=snapshot.profile.generated_at + timedelta(seconds=1)))
    assert later.baseline.profile.reference.profile_id == first.baseline.profile.reference.profile_id
    assert later.baseline.profile.reference.version != first.baseline.profile.reference.version
    assert later.source_profile_reference == first.source_profile_reference
    assert first.policy.version == "population-cold-quantiles-v1"


def test_serialized_derivation_rejects_mismatched_unit_reference_and_observation_support(population):
    snapshot = _supported(population)
    result = derive_population_baseline(snapshot, _request(snapshot))
    for mutate in (
        lambda d: d["features"][0]["parameter"].update(unit="wrong"),
        lambda d: d["baseline"]["profile"]["reference"].update(cohort_id="wrong"),
        lambda d: d["baseline"]["profile"].update(observations=()),
        lambda d: d.update(status="UNAVAILABLE"),
    ):
        data = result.model_dump(mode="python")
        mutate(data)
        with pytest.raises(ValueError):
            PopulationBaselineResult.model_validate(data)
