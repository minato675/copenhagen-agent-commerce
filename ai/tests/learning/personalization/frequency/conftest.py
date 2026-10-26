from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest
from copenhagen_fds.contracts.features import FEATURE_SPECS, RawFeatureVector
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, PublishedProfileInput
from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.contracts.scoring import ColdScoringInput
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.profile_policy_updates import initialize_profile_policy


@pytest.fixture
def frequency_cases(personalized, population):
    def publication(records=(0, 0, 0), *, days=None, user="a", minimum=2, admission=None):
        days = days if days is not None else tuple(range(len(records) + 1, 1, -1))
        rows = []
        for index, (record, day) in enumerate(zip(records, days, strict=True)):
            sample = population.sample(f"frequency-{index}", user=user,
                at=population.cutoff - timedelta(days=day)).model_dump(mode="python")
            for raw in sample["raw_features"]["features"]:
                if raw["feature_id"] not in FREQUENCY_FEATURES:
                    continue
                value = record.get(raw["feature_id"], 0) if isinstance(record, dict) else record
                raw.update(status=value if isinstance(value, str) else "AVAILABLE",
                           value=None if isinstance(value, str) else value)
            sample = LearningSample.model_validate(sample)
            rows.append(PolicyObservation(sample=sample, feedback={
                "feedback_id": sample.eligibility.feedback_id, "target": sample.eligibility.target,
                "classification": "NORMAL_CONFIRMED", "verification": "VERIFIED", "source": "FIXTURE_REVIEW",
                "occurred_at": sample.purchase_occurred_at + timedelta(minutes=1),
                "available_at": sample.purchase_occurred_at + timedelta(minutes=1),
            }))
        context = population.request().model_dump(mode="python")
        context.pop("cohort")
        context.update(user_id=user, currency="KRW", config={"min_samples": minimum},
            reference={"scope": "PERSONAL", "user_id": user, "profile_id": "frequency-" + user, "version": "fixture-v1"})
        snapshot = build_personal_profile(PersonalProfileRequest.model_validate(context), tuple(r.sample for r in rows))
        state = initialize_profile_policy(snapshot, rows, admission or ProfilePolicyConfig(min_reference_samples=len(rows) + 1))
        return PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
            expected_policy_state_version=state.state_version, available_at=state.published.profile.generated_at)

    def request(counts=0, *, profile=True, requested=FREQUENCY_FEATURES, user="a", at=None, minimum=None):
        base = personalized.request(personal=False, user=user, at=at)
        evaluation = BehaviorEvaluationRequest.model_validate({
            **base.cold.request.evaluation.model_dump(), "requested_features": requested,
        })
        baseline = BaselineSelectionRequest(evaluation=evaluation, population_reference=None,
                                            policy_id=None, policy_version=None)
        raw = []
        for name in requested:
            unit, seconds = FEATURE_SPECS[name]
            value = counts.get(name, 0) if isinstance(counts, dict) else counts
            if name == "amount_minor":
                value = evaluation.request.order.total.amount_minor
            elif name == "kst_hour":
                value = 0
            raw.append({"feature_id": name, "unit": unit, "window_seconds": seconds,
                        "currency": "KRW" if seconds is not None or name == "amount_minor" else None,
                        "status": value if isinstance(value, str) else "AVAILABLE",
                        "value": None if isinstance(value, str) else value})
        cold = ColdScoringInput(request=baseline, raw_features=RawFeatureVector(target=evaluation.target,
            evaluated_at=evaluation.evaluated_at, features=raw), selection=select_cold_baselines(baseline), trace_id="frequency-fixture")
        config = base.config.model_dump(mode="python")
        bounds = base.config.features[0].readiness.model_dump()
        config["features"] = [{"feature_id": name, "score_version": FREQUENCY_SCORE_VERSION,
            "readiness": {**bounds, "minimum_samples": (minimum or {}).get(name, 2),
                          "full_samples": max(10, (minimum or {}).get(name, 2) + 1)}}
            for name in FEATURE_SPECS if name in requested]
        return PersonalizedScoringInput(cold=cold, config=config,
            personal=publication(user=user) if profile is True else profile or None)

    def with_raw(supplied, vector):
        cold = ColdScoringInput.model_validate({**supplied.cold.model_dump(), "raw_features": vector})
        return PersonalizedScoringInput.model_validate({**supplied.model_dump(), "cold": cold})

    def feature(result, name):
        return next(f for f in result.features if f.raw.feature_id == name)

    return SimpleNamespace(publication=publication, request=request, with_raw=with_raw,
                           feature=feature, cutoff=population.cutoff)
