from collections import Counter
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.profile_policy import PolicyEvaluation, PolicyObservation
from copenhagen_fds.profile_policy import assess_profile_policy
from copenhagen_fds.profile_policy_reference import KST


def decisions(result):
    return {d.target.purchase_request_id: d for d in result.decisions}


def test_repeated_suspected_and_unconfirmed_purchases_do_not_change_normal_admissions(policy):
    base = policy.baseline()
    spam = tuple(policy.row("spam-" + str(i), amount=1000000, status="SUSPECTED") for i in range(30))
    result = policy.evaluate(*base, *spam, policy.row("unknown", status="DEFER"))
    assert {d.target.purchase_request_id for d in result.decisions if d.status == "INCLUDE"} == {r.sample.eligibility.target.purchase_request_id for r in base}
    assert {d.reason for d in result.decisions if d.target.purchase_request_id.startswith("spam")} == {"RAW_SUSPECTED"}
    assert decisions(result)["unknown"].reason == "RAW_UNCONFIRMED"


def test_confirmed_abrupt_exception_is_held_without_falsifying_raw_review(policy):
    row = policy.row("large", amount=10000)
    result = policy.evaluate(*policy.baseline(), row)
    decision = decisions(result)["large"]
    assert decision.status == "DEFER" and decision.reason == "ABRUPT_CHANGE"
    assert decision.raw_status == row.sample.eligibility.status == "INCLUDE"
    assert decision.feedback.classification == "NORMAL_CONFIRMED"
    assert next(c for c in decision.checks if c.feature_id == "amount_minor").abrupt


def test_confirmed_change_releases_only_after_sample_and_distinct_day_support(policy):
    changes = tuple(policy.row("new-" + str(i), amount=1000, at=policy.cutoff - timedelta(days=3 - i)) for i in range(3))
    result = policy.evaluate(*policy.baseline(), *changes)
    new = [decisions(result)[f"new-{i}"] for i in range(3)]
    assert all(d.status == "INCLUDE" and d.reason == "NORMAL_CHANGE_SUPPORTED" for d in new)
    assert all(d.decided_at == changes[-1].feedback.available_at for d in new)
    assert all(d.support_distinct_days == 3 and len(d.support_purchase_ids) == 3 for d in new)
    assert all(d.policy_version == "confirmed-normal-admission-v1" and d.reference_sha256 for d in new)


def test_same_day_normal_burst_is_not_habit_change_support(policy):
    changes = tuple(policy.row("new-" + str(i), amount=1000) for i in range(8))
    result = policy.evaluate(*policy.baseline(), *changes)
    assert all(d.reason == "ABRUPT_CHANGE" for d in result.decisions if d.target.purchase_request_id.startswith("new"))


def test_unrelated_extreme_normals_do_not_count_as_similar_support(policy):
    changes = tuple(policy.row("new-" + str(i), amount=1000 * 10 ** i, at=policy.cutoff - timedelta(days=3 - i)) for i in range(3))
    result = policy.evaluate(*policy.baseline(), *changes)
    assert all(d.status == "DEFER" for d in result.decisions if d.target.purchase_request_id.startswith("new"))


def test_daily_quota_refills_by_kst_day_and_does_not_depend_on_repeated_calls(policy):
    rows = tuple(policy.row(str(i)) for i in range(5))
    config = policy.config(max_admissions_per_user_day=2)
    result = policy.evaluate(*rows, config_value=config)
    assert sorted(Counter(d.admitted_at.astimezone(KST).date() for d in result.decisions if d.admitted_at).values()) == [2, 2]
    assert sum(d.status == "INCLUDE" for d in result.decisions) == 4
    assert sum(d.reason == "DAILY_LIMIT" for d in result.decisions) == 1
    assert policy.evaluate(*rows, config_value=config) == result


def test_population_quota_is_per_user_and_does_not_share_behavior_reference(policy):
    rows = tuple(policy.row(f"{user}-{i}", user=user, amount=100 if user == "a" else 100000) for user in ("a", "b") for i in range(5))
    result = policy.evaluate(*rows, population_scope=True, config_value=policy.config(max_admissions_per_user_day=2))
    counts = Counter(d.target.user_id for d in result.decisions if d.status == "INCLUDE")
    assert counts == {"a": 4, "b": 4}
    assert not any(d.reason == "ABRUPT_CHANGE" for d in result.decisions)


def test_future_and_missing_review_evidence_cannot_admit_claimed_normal(policy):
    row = policy.row("p", review_at=policy.cutoff + timedelta(days=1))
    missing = PolicyObservation(sample=row.sample)
    one, two = policy.evaluate(row), policy.evaluate(missing)
    assert one == two
    assert one.decisions[0].reason == "MISSING_REVIEW_EVIDENCE" and one.decisions[0].feedback is None


def test_unverified_review_stays_held_and_verified_fraud_is_excluded(policy):
    result = policy.evaluate(policy.row("unverified", status="UNVERIFIED"), policy.row("fraud", status="EXCLUDE"))
    assert decisions(result)["unverified"].reason == "RAW_UNVERIFIED_FEEDBACK"
    assert decisions(result)["fraud"].status == "EXCLUDE"


def test_circular_time_change_is_checked_without_false_midnight_deviation(policy):
    base = tuple(policy.row(str(i), at=(policy.cutoff - timedelta(days=10)).replace(hour=23)) for i in range(3))
    midnight = policy.row("midnight", at=(policy.cutoff - timedelta(days=1)).replace(hour=0))
    night = policy.row("noon", at=(policy.cutoff - timedelta(days=1)).replace(hour=12))
    result = policy.evaluate(*base, midnight, night, config_value=policy.config(hour_tolerance=2))
    assert decisions(result)["midnight"].status == "INCLUDE"
    assert decisions(result)["noon"].reason == "ABRUPT_CHANGE"


def test_json_and_input_order_reproduce_decisions_and_versions(policy):
    rows = (*policy.baseline(), policy.row("large", amount=10000))
    snapshot = policy.snapshot(rows)
    first = assess_profile_policy(snapshot, rows, policy.config())
    second = assess_profile_policy(snapshot, tuple(reversed(rows)) + rows, policy.config())
    assert first == second
    assert PolicyEvaluation.model_validate_json(first.model_dump_json()) == first


def test_incomplete_observation_set_and_unknown_truth_fields_are_rejected(policy):
    rows = policy.baseline()
    with pytest.raises(ValueError):
        assess_profile_policy(policy.snapshot(rows), rows[:-1], policy.config())
    data = rows[0].model_dump(mode="python")
    data["truth"] = "normal"
    with pytest.raises(ValueError):
        PolicyObservation.model_validate(data)
