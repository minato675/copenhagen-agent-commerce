"""Fit one user's empirical normal pattern from a fixed, observable learning replay."""

import hashlib
import json
from collections import Counter
from datetime import datetime, timedelta, timezone

from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_profiles import (
    PersonalProfileRequest, PersonalProfileSnapshot, PersonalStatisticsState, ProfileWindow,
)
from copenhagen_fds.contracts.profiles import BehaviorProfile
from copenhagen_fds.profile_statistics import feature_distribution, sample_status


def canonical_json(value) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def content_hash(value) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def canonical_sample(sample: LearningSample) -> LearningSample:
    # Array order and equivalent timezone spellings are not different observations.
    data = sample.model_dump(mode="python")
    data["raw_features"]["features"] = sorted(data["raw_features"]["features"], key=lambda value: list(FEATURE_SPECS).index(value["feature_id"]))
    data["eligibility"]["observed_attempt_ids"] = sorted(data["eligibility"]["observed_attempt_ids"])

    def normalize(value):
        if isinstance(value, datetime):
            return value.astimezone(timezone.utc)
        if isinstance(value, dict):
            return {key: normalize(item) for key, item in value.items()}
        if isinstance(value, (tuple, list)):
            return [normalize(item) for item in value]
        return value

    return LearningSample.model_validate(normalize(data))


def _window(samples, request, days) -> ProfileWindow:
    lower = request.as_of - timedelta(days=days)
    selected = tuple(sample for sample in samples if lower <= sample.purchase_occurred_at < request.as_of)
    counts = Counter(sample.eligibility.status for sample in selected)
    included = tuple(sample for sample in selected if sample.eligibility.status == "INCLUDE")
    return ProfileWindow(
        start_inclusive=lower, end_exclusive=request.as_of,
        status=sample_status(len(included), request.config.min_samples), retained_count=len(selected),
        included_count=counts["INCLUDE"], deferred_count=counts["DEFER"], excluded_count=counts["EXCLUDE"],
        features=tuple(feature_distribution(feature_id, included, as_of=request.as_of,
                                            min_samples=request.config.min_samples) for feature_id in FEATURE_SPECS),
    )


def build_personal_profile(
    request: PersonalProfileRequest, samples: tuple[LearningSample, ...],
) -> PersonalProfileSnapshot:
    """Select an explicit owner/currency, reject incompatible replays, retain bounded state."""
    lower = request.as_of - timedelta(days=request.config.long_term_days)
    selected = {}
    for sample in samples:
        target = sample.eligibility.target
        if (target.user_id, target.currency) != (request.user_id, request.currency):
            continue
        if (sample.origin, sample.source_reference) != (request.origin, request.source_reference):
            raise ValueError("profile input provenance must match the requested source")
        if sample.eligibility.as_of != request.as_of:
            raise ValueError("profile samples require exactly the requested learning cutoff")
        if not lower <= sample.purchase_occurred_at < request.as_of:
            continue
        sample = canonical_sample(sample)
        previous = selected.setdefault(target.purchase_request_id, sample)
        if previous != sample:
            raise ValueError("conflicting purchase samples require upstream correction replay")
    retained = tuple(sorted(selected.values(), key=lambda sample: (
        sample.purchase_occurred_at, sample.eligibility.target.purchase_request_id,
    )))
    recent = _window(retained, request, request.config.recent_days)
    long_term = _window(retained, request, request.config.long_term_days)
    profile = BehaviorProfile(
        reference=request.reference, origin=request.origin, source_reference=request.source_reference,
        feature_schema_version="cold-features-v1", currency=request.currency,
        as_of=request.as_of, generated_at=request.generated_at, valid_until=request.valid_until,
        observations=tuple(feature.observation for feature in long_term.features),
    )
    return PersonalProfileSnapshot(
        profile=profile, config=request.config,
        config_sha256=content_hash(request.config.model_dump(mode="json")),
        input_sha256=content_hash([sample.model_dump(mode="json") for sample in retained]),
        recent=recent, long_term=long_term,
        state=PersonalStatisticsState(state_version=request.state_version, profile_reference=request.reference,
                                      retained_samples=retained),
    )
