"""Explainable deviation checks and confirmed-change support; no fraud score."""

from datetime import timedelta, timezone
from statistics import median

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.profile_policy import PolicyFeatureCheck
from copenhagen_fds.personal_profiles import content_hash


KST = timezone(timedelta(hours=9))
PERIODS = {"kst_hour": 24, "kst_day_of_week": 7}


def feature_values(observation):
    return {f.feature_id: f.value for f in observation.sample.raw_features.features if f.status == "AVAILABLE"}


def circular_distance(left, right, period):
    distance = abs(left - right) % period
    return min(distance, period - distance)


def _match(feature, value, values, config):
    if len(values) < config.min_reference_samples:
        return None, None, None
    if feature in PERIODS:
        distance = min(circular_distance(value, other, PERIODS[feature]) for other in values)
        tolerance = config.hour_tolerance if feature == "kst_hour" else config.weekday_tolerance
        return distance <= tolerance, None, float(distance)
    center = float(median(values))
    floor = config.amount_floor_minor if feature == "amount_minor" else config.count_floor
    low, high = min(center / config.deviation_ratio, center - floor), max(center * config.deviation_ratio, center + floor)
    return low <= value <= high, center, float(value - center)


def reference_checks(candidate, admitted, *, at, recent_days, long_days, replay_from, config):
    recent_start, long_start = max(replay_from, at - timedelta(days=recent_days)), max(replay_from, at - timedelta(days=long_days))
    long_rows = [row for row in admitted if long_start <= row.sample.purchase_occurred_at <= at]
    recent_rows = [row for row in long_rows if row.sample.purchase_occurred_at >= recent_start]
    long_values, recent_values = [feature_values(row) for row in long_rows], [feature_values(row) for row in recent_rows]
    checks = []
    candidate_values = feature_values(candidate)
    for name in FEATURE_SPECS:
        if name not in candidate_values:
            continue
        value = candidate_values[name]
        recent = [row[name] for row in recent_values if name in row]
        long = [row[name] for row in long_values if name in row]
        r_match, r_center, r_distance = _match(name, value, recent, config)
        l_match, l_center, l_distance = _match(name, value, long, config)
        checks.append(PolicyFeatureCheck(feature_id=name, value=float(value), recent_count=len(recent), long_count=len(long),
            recent_center=r_center, long_center=l_center, recent_distance=r_distance, long_distance=l_distance,
            recent_match=r_match, long_match=l_match, abrupt=l_match is False and r_match is not True))
    def evidence(rows):
        return sorted((row.sample.eligibility.target.purchase_request_id, feature_values(row), row.feedback.feedback_id)
                      for row in rows)
    reference_hash = content_hash({"recent": evidence(recent_rows), "long": evidence(long_rows)})
    return tuple(checks), reference_hash, recent_start, long_start


def change_support(candidate, seen, checks, *, at, config):
    """Each supporter independently has verified evidence available by this replay tick."""
    flagged = [check.feature_id for check in checks if check.abrupt]
    values = feature_values(candidate)
    lower = at - timedelta(days=config.adaptation_window_days)
    supporters = []
    for row in seen:
        if not lower <= row.sample.purchase_occurred_at <= at:
            continue
        other = feature_values(row)
        similar = True
        for name in flagged:
            if name not in other:
                similar = False
                break
            if name in PERIODS:
                tolerance = config.hour_tolerance if name == "kst_hour" else config.weekday_tolerance
                match = circular_distance(values[name], other[name], PERIODS[name]) <= tolerance
            else:
                floor = config.amount_floor_minor if name == "amount_minor" else config.count_floor
                low, high = min(values[name], other[name]), max(values[name], other[name])
                match = high <= max(low * config.similarity_ratio, low + floor)
            if not match:
                similar = False
                break
        if similar:
            supporters.append(row)
    ids = tuple(sorted(row.sample.eligibility.target.purchase_request_id for row in supporters))
    days = len({row.sample.purchase_occurred_at.astimezone(KST).date() for row in supporters})
    return ids, days
