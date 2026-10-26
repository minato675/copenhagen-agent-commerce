from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.behavior import BehaviorEvaluationRequest
from copenhagen_fds.contracts.features import FEATURE_SPECS, FeatureTransformInput
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.personal_time import TIME_PERIODS, TIME_SCORE_VERSION
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, PublishedProfileInput
from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.contracts.scoring import ColdScoringInput
from copenhagen_fds.features import transform_features
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.profile_policy_updates import initialize_profile_policy


@pytest.fixture
def time_cases(personalized, population):
    def occurred(hour=0, weekday=None):
        at = population.cutoff - timedelta(days=1)
        if weekday is not None:
            at -= timedelta(days=(at.weekday() - weekday) % 7)
        return at.replace(hour=hour)

    def publication(records=None, *, minimum=2, admission=None, user="a"):
        records = records if records is not None else [{"at": occurred()}] * 3
        rows = []
        for i, record in enumerate(records):
            sample = population.sample(f"time-{i}", user=user, at=record["at"],
                amount=record.get("amount", 100), missing=record.get("missing"))
            rows.append(PolicyObservation(sample=sample, feedback={
                "feedback_id": sample.eligibility.feedback_id, "target": sample.eligibility.target,
                "classification": "NORMAL_CONFIRMED", "verification": "VERIFIED", "source": "FIXTURE_REVIEW",
                "occurred_at": sample.purchase_occurred_at + timedelta(minutes=1),
                "available_at": sample.purchase_occurred_at + timedelta(minutes=1),
            }))
        context = population.request().model_dump(mode="python")
        context.pop("cohort")
        context.update(user_id=user, currency="KRW", config={"min_samples": minimum},
            reference={"scope": "PERSONAL", "user_id": user, "profile_id": "time-" + user, "version": "fixture-v1"})
        snapshot = build_personal_profile(PersonalProfileRequest.model_validate(context), tuple(r.sample for r in rows))
        state = initialize_profile_policy(snapshot, rows, admission or ProfilePolicyConfig(min_reference_samples=len(rows) + 1))
        return PublishedProfileInput(state=state, expected_reference=state.published.profile.reference,
            expected_policy_state_version=state.state_version, available_at=state.published.profile.generated_at)

    def request(*, current=True, profile=True, requested=tuple(TIME_PERIODS), user="a", at=None, minimum=None):
        base = personalized.request(personal=False, user=user, at=at)
        evaluation = BehaviorEvaluationRequest.model_validate({
            **base.cold.request.evaluation.model_dump(), "requested_features": requested,
        })
        baseline = BaselineSelectionRequest(evaluation=evaluation, population_reference=None,
                                            policy_id=None, policy_version=None)
        current = occurred() if current is True else current or None
        raw = transform_features(FeatureTransformInput(evaluation=evaluation, current_occurred_at=current,
            current_available_at=current, current_attempt_id=None, history=None))
        cold = ColdScoringInput(request=baseline, raw_features=raw,
            selection=select_cold_baselines(baseline), trace_id="time-fixture")
        config = base.config.model_dump(mode="python")
        bounds = base.config.features[0].readiness.model_dump()
        config["features"] = [{"feature_id": name, "score_version": TIME_SCORE_VERSION,
            "readiness": {**bounds, "minimum_samples": (minimum or {}).get(name, 2),
                          "full_samples": max(10, (minimum or {}).get(name, 2) + 1)}}
            for name in FEATURE_SPECS if name in requested]
        return PersonalizedScoringInput(cold=cold, config=config,
            personal=publication(user=user) if profile is True else profile or None)

    def feature(result, name="kst_hour"):
        return next(f for f in result.features if f.raw.feature_id == name)

    return SimpleNamespace(publication=publication, request=request, feature=feature,
                           occurred=occurred, cutoff=population.cutoff)
