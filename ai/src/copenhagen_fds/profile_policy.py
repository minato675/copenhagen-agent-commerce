"""Replay known confirmation evidence with per-user daily admission limits."""

from collections import Counter, defaultdict
from datetime import datetime, time, timedelta, timezone

from copenhagen_fds.contracts.learning import LearningFeedback
from copenhagen_fds.contracts.profile_policy import PolicyDecision, PolicyEvaluation, PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.personal_profiles import canonical_sample, content_hash
from copenhagen_fds.profile_policy_reference import KST, change_support, reference_checks
from copenhagen_fds.profile_revision_log import in_profile_scope
from copenhagen_fds.profile_update_records import normalized_data, sample_content_hash


def select_policy_observations(snapshot, observations):
    """Select complete retained observations and discard unavailable reviews before hashing."""
    selected = {}
    profile, lower = snapshot.profile, snapshot.long_term.start_inclusive
    for row in observations:
        sample = row.sample
        if (sample.eligibility.as_of, sample.origin, sample.source_reference) != (
            profile.as_of, profile.origin, profile.source_reference,
        ):
            raise ValueError("policy observations must match the profile cutoff and provenance")
        if not in_profile_scope(snapshot, sample.eligibility.target) or not lower <= sample.purchase_occurred_at < profile.as_of:
            continue
        feedback = row.feedback
        if feedback is not None:
            feedback = None if feedback.available_at > profile.as_of else LearningFeedback.model_validate(normalized_data(feedback))
        known = PolicyObservation(sample=canonical_sample(sample), feedback=feedback)
        pid = sample.eligibility.target.purchase_request_id
        if pid in selected and selected[pid] != known:
            raise ValueError("policy observations contain conflicting purchase evidence")
        selected[pid] = known
    expected = {s.eligibility.target.purchase_request_id: sample_content_hash(s) for s in snapshot.state.retained_samples}
    actual = {pid: sample_content_hash(row.sample) for pid, row in selected.items()}
    if expected != actual:
        raise ValueError("policy observations must cover every retained candidate purchase exactly")
    return tuple(selected[key] for key in sorted(selected))


def _base_reason(row):
    decision = row.sample.eligibility
    if decision.reason in {"CURRENT_PURCHASE", "PURCHASE_NOT_OBSERVED"}:
        return "RAW_NOT_OBSERVED"
    if decision.reason == "UNCONFIRMED":
        return "RAW_UNCONFIRMED"
    if row.feedback is None:
        return "MISSING_REVIEW_EVIDENCE"
    if decision.reason == "NORMAL_CONFIRMED":
        return None
    return {"FRAUD_CONFIRMED": "RAW_FRAUD_CONFIRMED", "SUSPECTED": "RAW_SUSPECTED",
            "UNVERIFIED_FEEDBACK": "RAW_UNVERIFIED_FEEDBACK"}[decision.reason]


def _ready_at(row):
    return max(row.feedback.available_at, row.sample.raw_features.evaluated_at, row.sample.purchase_available_at)


def _replay_user(rows, snapshot, config, config_hash):
    cutoff, lower = snapshot.profile.as_of, snapshot.long_term.start_inclusive
    decisions, arrivals = {}, defaultdict(list)

    def decide(row, at, reason, **context):
        sample = row.sample
        status = "EXCLUDE" if reason == "RAW_FRAUD_CONFIRMED" else (
            "INCLUDE" if reason in {"BOOTSTRAP_NORMAL", "REFERENCE_MATCH", "NORMAL_CHANGE_SUPPORTED"} else "DEFER"
        )
        return PolicyDecision(target=sample.eligibility.target, sample_sha256=sample_content_hash(sample),
            status=status, reason=reason, decided_at=at, admitted_at=at if status == "INCLUDE" else None,
            config_sha256=config_hash, raw_status=sample.eligibility.status, raw_reason=sample.eligibility.reason,
            feedback=row.feedback, **context)

    for row in rows:
        reason = _base_reason(row)
        if reason is not None:
            decisions[row.sample.eligibility.target.purchase_request_id] = decide(row, cutoff, reason)
        else:
            arrivals[_ready_at(row)].append(row)
    if not arrivals:
        return tuple(decisions.values())
    ticks = set(arrivals) | {cutoff}
    day = min(ticks).astimezone(KST).date() + timedelta(days=1)
    while datetime.combine(day, time(), KST) <= cutoff:
        ticks.add(datetime.combine(day, time(), KST).astimezone(timezone.utc))
        day += timedelta(days=1)
    seen, admitted, pending, budgets = [], [], {}, Counter()
    for at in sorted(ticks):
        for row in arrivals.get(at, ()):
            seen.append(row)
            pending[row.sample.eligibility.target.purchase_request_id] = row
        # Admissions can make a previously held candidate match the recent reference.
        progress = True
        while progress:
            progress = False
            for pid, row in sorted(tuple(pending.items()), key=lambda item: (_ready_at(item[1]), item[0])):
                checks, ref_hash, recent_start, long_start = reference_checks(
                    row, admitted, at=at, recent_days=snapshot.config.recent_days,
                    long_days=snapshot.config.long_term_days, replay_from=lower, config=config,
                )
                abrupt = any(check.abrupt for check in checks)
                support, days = change_support(row, seen, checks, at=at, config=config) if abrupt else ((), 0)
                supported = len(support) >= config.adaptation_min_samples and days >= config.adaptation_min_days
                day_key = at.astimezone(KST).date()
                capped = budgets[day_key] >= config.max_admissions_per_user_day
                if abrupt and not supported:
                    reason = "ABRUPT_CHANGE"
                elif capped:
                    reason = "DAILY_LIMIT"
                elif abrupt:
                    reason = "NORMAL_CHANGE_SUPPORTED"
                elif not any(check.long_match is not None for check in checks):
                    reason = "BOOTSTRAP_NORMAL"
                else:
                    reason = "REFERENCE_MATCH"
                decision = decide(row, at, reason, checks=checks, reference_sha256=ref_hash,
                    recent_start=recent_start, long_start=long_start, support_purchase_ids=support,
                    support_distinct_days=days, day_admissions_before=budgets[day_key], daily_limit_reached=capped)
                decisions[pid] = decision
                if decision.status == "INCLUDE":
                    admitted.append(row)
                    budgets[day_key] += 1
                    del pending[pid]
                    progress = True
    return tuple(decisions.values())


def assess_profile_policy(snapshot, observations, config: ProfilePolicyConfig = ProfilePolicyConfig()) -> PolicyEvaluation:
    """Recompute bounded admission history from facts known at this cutoff, per user.

    Latest fraud corrections remove the purchase before replay. Published history is
    not rewritten in place; callers compare the previous and new immutable results.
    """
    known = select_policy_observations(snapshot, observations)
    config_hash = content_hash(config.model_dump(mode="json"))
    groups = defaultdict(list)
    for row in known:
        groups[row.sample.eligibility.target.user_id, row.sample.eligibility.target.currency].append(row)
    decisions = []
    for key in sorted(groups):
        decisions.extend(_replay_user(groups[key], snapshot, config, config_hash))
    return PolicyEvaluation(config=config, config_sha256=config_hash,
        input_sha256=content_hash(normalized_data(known)), as_of=snapshot.profile.as_of,
        replay_from=snapshot.long_term.start_inclusive,
        decisions=tuple(sorted(decisions, key=lambda d: d.target.purchase_request_id)))
