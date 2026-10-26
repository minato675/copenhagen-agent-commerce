"""Observable policy publications and hand-calculated score outputs, without future scorers."""

import json
import math
from datetime import timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.baselines import InitialPolicy
from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_readiness import READINESS_VERSION

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.cold_scoring import score_cold_features
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.features import FEATURE_SPECS, RawFeatureVector
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.population_baselines import PopulationBaselineRequest
from copenhagen_fds.contracts.personalization_config import PersonalizationConfig
from copenhagen_fds.contracts.personalization_inputs import (
    PersonalizedScoringInput, PublishedProfileInput, personalization_hash,
)
from copenhagen_fds.contracts.personalization_results import (
    PersonalizedScoringResult, personal_feature_evidence,
)
from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.contracts.scoring import ColdScoringInput
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.population_baselines import derive_population_baseline
from copenhagen_fds.profile_policy_updates import initialize_profile_policy


@pytest.fixture
def personalized(population):
    def publication(*, user="a", scope="PERSONAL", pid="normal", missing=None, include_large=False):
        rows = []
        purchases = [(pid + str(i), 100, 6 - i) for i in range(3)]
        if include_large:
            purchases.append(("large", 10000, 1))
        for name, amount, days in purchases:
            sample = population.sample(name, user=("b" if scope == "POPULATION" and name.endswith("2") else user),
                amount=amount + (50 if scope == "POPULATION" and name.endswith("2") else 0),
                at=population.cutoff - timedelta(days=days), missing=missing)
            rows.append(PolicyObservation(sample=sample, feedback={
                "feedback_id": sample.eligibility.feedback_id, "target": sample.eligibility.target,
                "classification": "NORMAL_CONFIRMED", "verification": "VERIFIED", "source": "FIXTURE_REVIEW",
                "occurred_at": sample.purchase_occurred_at + timedelta(minutes=1),
                "available_at": sample.purchase_occurred_at + timedelta(minutes=1),
            }))
        context = population.request()
        if scope == "POPULATION":
            snapshot = population.fit(*(r.sample for r in rows))
        else:
            data = context.model_dump(mode="python")
            data.pop("cohort")
            data.update(user_id=user, currency="KRW", config={"min_samples": 2},
                        reference={"scope": "PERSONAL", "user_id": user, "profile_id": "personal-" + user, "version": "fixture-v1"})
            snapshot = build_personal_profile(PersonalProfileRequest.model_validate(data), tuple(r.sample for r in rows))
        state = initialize_profile_policy(snapshot, rows, ProfilePolicyConfig(min_reference_samples=3))
        return PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
            expected_policy_state_version=state.state_version, available_at=state.published.profile.generated_at)

    def request(*, personal=True, population_profile=None, at=None, states=None, user="a", current="current-purchase", policy_enabled=True):
        at = at or population.cutoff + timedelta(seconds=2)
        data = json.loads((Path(__file__).resolve().parents[3] / "examples/cold_baseline_input.json").read_text(encoding="utf-8"))
        evaluation = data["request"]["evaluation"]
        evaluation["evaluated_at"] = at
        evaluation["request"]["context"].update(user_id=user, purchase_request_id=current)
        evaluation["request"]["order"]["purchase_request_id"] = current
        evaluation["request"]["intent"]["purchase_request_id"] = current
        derived = derive_population_baseline(population_profile.state.published, PopulationBaselineRequest(
            expected_source_reference=population_profile.expected_reference, currency="KRW",
            available_at=population_profile.available_at)) if population_profile else None
        baseline = derived.baseline if derived else None
        data["request"]["population_reference"] = baseline.profile.reference if baseline else None
        policy = data["policy"]
        policy.update(generated_at=population.cutoff - timedelta(days=5), available_at=population.cutoff - timedelta(days=5),
                      valid_from=population.cutoff - timedelta(days=5), valid_until=population.cutoff + timedelta(days=10))
        policy["parameters"].append({"feature_id": "amount_minor", "unit": "MINOR_UNIT", "method": "UPPER_DISTANCE",
                                      "center": 10000.0, "scale": 10000.0})
        baseline_request = BaselineSelectionRequest.model_validate(data["request"])
        raw = []
        for name, value in (("amount_minor", 10000), ("purchase_count_10m", 6), ("kst_hour", 0)):
            unit, window = FEATURE_SPECS[name]
            status = (states or {}).get(name, "AVAILABLE")
            raw.append({"feature_id": name, "unit": unit, "window_seconds": window,
                        "currency": None if name == "kst_hour" else "KRW", "status": status,
                        "value": value if status == "AVAILABLE" else None})
        cold = ColdScoringInput(request=baseline_request, trace_id="fixture-trace",
            raw_features=RawFeatureVector(target=baseline_request.evaluation.target, evaluated_at=at, features=raw),
            selection=select_cold_baselines(baseline_request, population=baseline, policy=policy if policy_enabled else None))
        bounds = dict(minimum_samples=2, full_samples=10, minimum_observed_seconds=86400.0,
            full_observed_seconds=259200.0, fresh_for_seconds=604800.0, stale_after_seconds=2592000.0,
            minimum_available_fraction=0.5, full_available_fraction=1.0)
        config = PersonalizationConfig(version="fixture-config-v1", score_scale_version="fixture-scale-v1",
            transition_policy_version="fixture-transition-v1", features=[
                {"feature_id": name, "score_version": "fixture-score-v1", "readiness": bounds}
                for name in evaluation["requested_features"]])
        return PersonalizedScoringInput(cold=cold, config=config,
            personal=publication() if personal is True else personal or None, population=population_profile,
            population_baseline=derived)

    def result(supplied, *, weight=0.25):
        cold = score_cold_features(supplied.cold)
        features = []
        can_use_personal = supplied.profile_availability()[0].status == "AVAILABLE"
        for component in cold.behavior.features:
            name = component.feature_id
            personal = {"feature_id": name, "status": "INSUFFICIENT_DATA", "reasons": ["REFERENCE_UNAVAILABLE"]}
            window, version = None, None
            evidence = personal_feature_evidence(supplied, name)
            if component.status == "SCORED" and can_use_personal:
                personal.update(status="SCORED", score={"value": 80.0, "unit": "BEHAVIOR_ANOMALY_0_100"},
                    reasons=["PATTERN_DEVIATION"], bases=[{"kind": "PERSONAL_PROFILE", "profile": supplied.personal.expected_reference}])
                window, version = "LONG_TERM", "fixture-score-v1"
            elif component.status in {"NOT_COLLECTED", "NOT_APPLICABLE", "INSUFFICIENT_DATA"}:
                personal.update(status=component.status, reasons=component.reasons)
            row = dict(cold=component, personal=personal, combined=component,
                       stage="UNAVAILABLE", stage_reasons=["FIXTURE_HAND_CALCULATION"], evidence=evidence,
                       personal_window=window, personal_score_version=version)
            if component.status == "SCORED":
                pw = weight if can_use_personal else 0.0
                cw = 1.0 - pw
                bases = list(component.bases) if cw else []
                if pw:
                    bases.extend(personal["bases"])
                score = math.fsum([cw * component.score.value, pw * 80.0])
                row.update(stage="COLD" if pw == 0 else "PERSONALIZED" if pw == 1 else "TRANSITION",
                    cold_weight=cw, personal_weight=pw, combined={"feature_id": name, "status": "SCORED",
                        "score": {"value": score, "unit": "BEHAVIOR_ANOMALY_0_100"}, "bases": bases,
                        "reasons": ["WITHIN_REFERENCE" if score == 0 else "PATTERN_DEVIATION"]})
            features.append(row)
        behavior = cold.behavior.model_dump(mode="python")
        behavior.update(scoring_version="personalized-features-v1", features=[f["combined"] for f in features])
        behavior["metadata"]["policy_version"] = supplied.config.transition_policy_version
        if can_use_personal and weight > 0 and any(f["stage"] != "UNAVAILABLE" for f in features):
            behavior["profiles"] = (*behavior["profiles"], supplied.personal.state.published.profile)
        return PersonalizedScoringResult(input_sha256=personalization_hash(supplied), config=supplied.config,
            config_sha256=personalization_hash(supplied.config), cold=cold, behavior=behavior,
            profiles=supplied.profile_availability(), features=features).validate_for_input(supplied)

    return SimpleNamespace(publication=publication, request=request, result=result, cutoff=population.cutoff)


@pytest.fixture
def amount_cases(personalized, population):
    def publication(amounts=(100, 100, 100), *, days=None, minimum=2, currency="KRW"):
        days = days if days is not None else tuple(range(1, len(amounts) + 1))
        rows = []
        for index, (amount, day) in enumerate(zip(amounts, days, strict=True)):
            sample = population.sample(f"amount-{index}", amount=amount, currency=currency,
                at=population.cutoff - timedelta(days=day))
            rows.append(PolicyObservation(sample=sample, feedback={
                "feedback_id": sample.eligibility.feedback_id, "target": sample.eligibility.target,
                "classification": "NORMAL_CONFIRMED", "verification": "VERIFIED", "source": "FIXTURE_REVIEW",
                "occurred_at": sample.purchase_occurred_at + timedelta(minutes=1),
                "available_at": sample.purchase_occurred_at + timedelta(minutes=1),
            }))
        context = population.request().model_dump(mode="python")
        context.pop("cohort")
        context.update(user_id="a", currency=currency, config={"min_samples": minimum},
            reference={"scope": "PERSONAL", "user_id": "a", "profile_id": "amount-a", "version": "fixture-v1"})
        snapshot = build_personal_profile(PersonalProfileRequest.model_validate(context), tuple(r.sample for r in rows))
        # Keep these explicit normal observations during bootstrap; admission itself
        # is tested by the existing policy suite and the withheld-profile case.
        state = initialize_profile_policy(snapshot, rows, ProfilePolicyConfig(min_reference_samples=len(rows) + 1))
        return PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
            expected_policy_state_version=state.state_version, available_at=state.published.profile.generated_at)

    def request(amount=100, *, profile=True, currency="KRW", states=None, at=None, minimum=2):
        from copenhagen_fds.contracts.personal_amount import AMOUNT_SCORE_VERSION

        data = personalized.request(personal=False, states=states, at=at).model_dump(mode="python")
        data["personal"] = publication(currency=currency).model_dump() if profile is True else (
            profile.model_dump() if profile else None)
        order = data["cold"]["request"]["evaluation"]["request"]["order"]
        for money in (order["total"], order["orders"][0]["total"],
                      order["orders"][0]["items"][0]["unit_price"], order["orders"][0]["items"][0]["line_total"]):
            money.update(amount_minor=amount, currency=currency)
        data["cold"]["selection"]["currency"] = currency
        for raw in data["cold"]["raw_features"]["features"]:
            if raw["currency"] is not None:
                raw["currency"] = currency
            if raw["feature_id"] == "amount_minor" and raw["status"] == "AVAILABLE":
                raw["value"] = amount
        data["config"]["features"][0]["score_version"] = AMOUNT_SCORE_VERSION
        data["config"]["features"][0]["readiness"].update(minimum_samples=minimum, full_samples=minimum + 10)
        return PersonalizedScoringInput.model_validate(data)

    return SimpleNamespace(publication=publication, request=request, cutoff=population.cutoff)


@pytest.fixture
def readiness_cases(personalized, population):
    def publication(records=None, *, minimum=2, admission=None):
        records = records if records is not None else [{"days": d} for d in (4, 3, 2, 1)]
        rows = []
        for i, record in enumerate(records):
            at = record.get("at", population.cutoff - timedelta(days=record.get("days", 1)))
            data = population.sample(f"readiness-{i}", at=at, missing=record.get("missing"),
                amount=record.get("amount", 100), counts=record.get("counts", 0)).model_dump()
            for stream in ("purchase_coverage", "attempt_coverage"):
                if stream in record:
                    data[stream] = record[stream]
            sample = LearningSample.model_validate(data)
            rows.append(PolicyObservation(sample=sample, feedback={
                "feedback_id": sample.eligibility.feedback_id, "target": sample.eligibility.target,
                "classification": "NORMAL_CONFIRMED", "verification": "VERIFIED", "source": "FIXTURE_REVIEW",
                "occurred_at": at + timedelta(minutes=1), "available_at": at + timedelta(minutes=1),
            }))
        context = population.request().model_dump()
        context.pop("cohort")
        context.update(user_id="a", currency="KRW", config={"min_samples": minimum},
            valid_until=population.cutoff + timedelta(days=30),
            reference={"scope": "PERSONAL", "user_id": "a", "profile_id": "readiness-a", "version": "fixture-v1"})
        snapshot = build_personal_profile(PersonalProfileRequest.model_validate(context), tuple(r.sample for r in rows))
        policy = admission or ProfilePolicyConfig(min_reference_samples=len(rows) + 1)
        state = initialize_profile_policy(snapshot, rows, policy)
        return PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
            expected_policy_state_version=state.state_version, available_at=state.published.profile.generated_at)

    def request(*, profile=True, cold=True, at=None, states=None, settings=None, requested=tuple(FEATURE_SPECS)):
        base = personalized.request(personal=False, at=at)
        evaluation = BehaviorEvaluationRequest.model_validate({
            **base.cold.request.evaluation.model_dump(), "requested_features": requested})
        baseline = BaselineSelectionRequest(evaluation=evaluation, population_reference=None,
            policy_id="readiness-fixture" if cold else None, policy_version="fixture-v1" if cold else None)
        parameters, values = [], []
        for name, (unit, seconds) in FEATURE_SPECS.items():
            if name not in requested:
                continue
            period = {"kst_hour": 24, "kst_day_of_week": 7}.get(name)
            parameters.append(dict(feature_id=name, unit=unit, window_seconds=seconds, center=0.0,
                scale=1.0, period=period, method="CIRCULAR_DISTANCE" if period else "UPPER_DISTANCE"))
            status = (states or {}).get(name, "AVAILABLE")
            values.append(dict(feature_id=name, unit=unit, window_seconds=seconds,
                currency=None if period else "KRW", status=status,
                value=(10000 if name == "amount_minor" else 0) if status == "AVAILABLE" else None))
        policy = InitialPolicy(policy_id="readiness-fixture", version="fixture-v1", source_reference="test-only",
            currency="KRW", feature_schema_version="cold-features-v1", generated_at=population.cutoff - timedelta(days=1),
            available_at=population.cutoff - timedelta(days=1), valid_from=population.cutoff - timedelta(days=1),
            valid_until=population.cutoff + timedelta(days=60), parameters=parameters)
        cold_input = ColdScoringInput(request=baseline, selection=select_cold_baselines(baseline, policy=policy if cold else None),
            raw_features=RawFeatureVector(target=evaluation.target, evaluated_at=evaluation.evaluated_at, features=values),
            trace_id="readiness-fixture")
        bounds = dict(minimum_samples=2, full_samples=4, minimum_observed_seconds=0.0, full_observed_seconds=86400.0,
            fresh_for_seconds=604800.0, stale_after_seconds=864000.0, minimum_available_fraction=0.0, full_available_fraction=1.0)
        config = base.config.model_dump()
        config.update(transition_policy_version=READINESS_VERSION,
            features=[dict(feature_id=name, score_version="fixture-score-v1", readiness={**bounds, **(settings or {}).get(name, {})})
                      for name in FEATURE_SPECS if name in requested])
        return PersonalizedScoringInput(cold=cold_input, config=config, personal=publication() if profile is True else profile or None)

    def feature(result, name="amount_minor"):
        return next(f for f in result.features if getattr(f, "feature_id", None) == name or getattr(f, "raw", None) and f.raw.feature_id == name)

    return SimpleNamespace(publication=publication, request=request, feature=feature, cutoff=population.cutoff)
