from datetime import timedelta

import pytest
from pydantic import ValidationError

from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.profile_policy_reference import circular_distance, reference_checks


@pytest.mark.parametrize("change", [
    {"max_admissions_per_user_day": 0}, {"deviation_ratio": 1.0}, {"similarity_ratio": float("nan")},
    {"adaptation_min_days": 6}, {"adaptation_window_days": 2}, {"hour_tolerance": 13},
    {"max_admissions_per_user_day": True}, {"unknown_setting": 1},
])
def test_policy_configuration_rejects_invalid_limits(change):
    with pytest.raises(ValidationError):
        ProfilePolicyConfig(**change)


def test_review_must_match_the_selected_purchase_and_classification(policy):
    row = policy.row("p")
    for field, value in (("feedback_id", "other"), ("classification", "FRAUD_CONFIRMED"), ("verification", "UNVERIFIED")):
        data = row.model_dump(mode="python")
        data["feedback"][field] = value
        with pytest.raises(ValidationError):
            PolicyObservation.model_validate(data)
    data = row.model_dump(mode="python")
    data["feedback"]["target"] = policy.row("other").sample.eligibility.target
    with pytest.raises(ValidationError):
        PolicyObservation.model_validate(data)


def test_circular_distance_preserves_midnight_and_week_boundary():
    assert circular_distance(23, 0, 24) == 1
    assert circular_distance(6, 0, 7) == 1
    assert circular_distance(3, 15, 24) == 12


def test_zero_reference_uses_absolute_floor_and_missing_features_stay_unsupported(policy):
    rows = tuple(policy.row(str(i), counts=0, missing={"attempt_count_10m": "NOT_COLLECTED"}) for i in range(3))
    candidate = policy.row("candidate", counts=2)
    checks, _, _, _ = reference_checks(candidate, rows, at=policy.cutoff, recent_days=7, long_days=90,
                                       replay_from=policy.cutoff - timedelta(days=90), config=policy.config())
    by_id = {check.feature_id: check for check in checks}
    assert by_id["purchase_count_10m"].abrupt
    assert by_id["attempt_count_10m"].long_match is None and not by_id["attempt_count_10m"].abrupt


def test_recent_supported_pattern_can_match_despite_long_reference_deviation(policy):
    old = tuple(policy.row("old-" + str(i), at=policy.cutoff - timedelta(days=20), amount=100) for i in range(7))
    recent = tuple(policy.row("new-" + str(i), amount=1000) for i in range(3))
    checks, digest, _, _ = reference_checks(policy.row("candidate", amount=1000), old + recent, at=policy.cutoff,
        recent_days=7, long_days=90, replay_from=policy.cutoff - timedelta(days=90), config=policy.config())
    amount = next(c for c in checks if c.feature_id == "amount_minor")
    assert amount.long_match is False and amount.recent_match is True and not amount.abrupt
    assert len(digest) == 64
