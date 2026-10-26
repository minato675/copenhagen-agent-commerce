from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.personal_profiles import build_personal_profile


@pytest.fixture
def policy(population):
    def observation(pid, *, status="INCLUDE", at=None, review_at=None, review_id=None, cutoff=None, **features):
        cutoff = cutoff or population.cutoff
        at = at or cutoff - timedelta(days=1)
        raw_status = "DEFER" if status in {"SUSPECTED", "UNVERIFIED"} else status
        sample = population.sample(pid, status=raw_status, at=at, **features)
        data = sample.model_dump(mode="python")
        data["eligibility"]["as_of"] = cutoff
        if status in {"SUSPECTED", "UNVERIFIED"}:
            data["eligibility"].update(reason="SUSPECTED" if status == "SUSPECTED" else "UNVERIFIED_FEEDBACK", feedback_id="review-" + pid)
        if review_id is not None:
            data["eligibility"]["feedback_id"] = review_id
        sample = LearningSample.model_validate(data)
        feedback = None if status == "DEFER" else {
            "feedback_id": review_id or "review-" + pid, "target": sample.eligibility.target,
            "classification": {"INCLUDE": "NORMAL_CONFIRMED", "EXCLUDE": "FRAUD_CONFIRMED", "SUSPECTED": "SUSPECTED",
                               "UNVERIFIED": "NORMAL_CONFIRMED"}[status],
            "source": "TEST_REVIEW", "verification": "UNVERIFIED" if status == "UNVERIFIED" else "VERIFIED",
            "occurred_at": at + timedelta(minutes=1), "available_at": review_at or at + timedelta(minutes=1),
        }
        return PolicyObservation(sample=sample, feedback=feedback)

    def snapshot(rows, *, population_scope=False, cutoff=None, **config):
        cutoff = cutoff or population.cutoff
        context = population.request(as_of=cutoff, generated_at=cutoff + timedelta(seconds=1), valid_until=cutoff + timedelta(days=1))
        samples = tuple(row.sample for row in rows)
        if population_scope:
            return population.fit(*samples, as_of=cutoff, generated_at=cutoff + timedelta(seconds=1), valid_until=cutoff + timedelta(days=1),
                                  config={"min_samples": 2, "min_users": 2, **config})
        data = context.model_dump(mode="python")
        data.pop("cohort")
        data.update(user_id="a", currency="KRW", config=config,
                    reference={"scope": "PERSONAL", "user_id": "a", "profile_id": "personal-a", "version": "fixture-v1"})
        return build_personal_profile(PersonalProfileRequest.model_validate(data), samples)

    def config(**changes):
        return ProfilePolicyConfig(**{"min_reference_samples": 3, "adaptation_min_samples": 3, "adaptation_min_days": 2,
                                      "hour_tolerance": 12, "weekday_tolerance": 3, **changes})

    def evaluate(*rows, config_value=None, **context):
        from copenhagen_fds.profile_policy import assess_profile_policy

        return assess_profile_policy(snapshot(rows, **context), rows, config_value or config())

    def baseline():
        return tuple(observation("base-" + str(i), at=population.cutoff - timedelta(days=30 - i), amount=100) for i in range(3))

    return SimpleNamespace(row=observation, snapshot=snapshot, config=config, evaluate=evaluate, baseline=baseline,
                           cutoff=population.cutoff)
