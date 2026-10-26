"""Stable purchase content identities across learning cutoffs and wire timezones."""

from datetime import datetime, timezone

from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.personal_profiles import canonical_sample, content_hash
from copenhagen_fds.validation_memo import memoized_validation


def normalized_data(value):
    if hasattr(value, "model_dump"):
        value = value.model_dump(mode="python")
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat()
    if isinstance(value, dict):
        return {key: normalized_data(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [normalized_data(item) for item in value]
    return value


def sample_content_hash(sample: LearningSample) -> str:
    def calculate():
        data = canonical_sample(sample).model_dump(mode="json")
        # Carrying already-known facts forward is not a new purchase revision.
        data["eligibility"].pop("as_of")
        return content_hash(data)

    # The memo key still includes as_of so invalid chronology is never hidden.
    return memoized_validation("sample-content-hash-v1", sample, calculate)


def sample_at_cutoff(sample: LearningSample, as_of: datetime) -> LearningSample:
    if sample.eligibility.as_of > as_of:
        raise ValueError("a learning sample cannot be moved to an earlier information cutoff")
    data = canonical_sample(sample).model_dump(mode="python")
    data["eligibility"]["as_of"] = as_of.astimezone(timezone.utc)
    return LearningSample.model_validate(data)
