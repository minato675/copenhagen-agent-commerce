from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest
from copenhagen_fds.contracts.profile_updates import ProfileUpdateEvent, ProfileUpdateRequest
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.profile_revision_log import initialize_profile_update
from copenhagen_fds.profile_update_records import sample_content_hash


@pytest.fixture
def updates(population):
    def state(*samples, population_scope=False, **config):
        if population_scope:
            snapshot = population.fit(*samples, config={"min_samples": 2, "min_users": 2, **config})
        else:
            context = population.request().model_dump(mode="python")
            context.pop("cohort")
            context.update(user_id="a", currency="KRW", config={"min_samples": 2, **config},
                           reference={"profile_id": "personal-a", "version": "v1", "scope": "PERSONAL", "user_id": "a"})
            snapshot = build_personal_profile(PersonalProfileRequest.model_validate(context), samples)
        return initialize_profile_update(snapshot)

    def request(base, *events, **changes):
        cutoff = changes.get("as_of", base.snapshot.profile.as_of)
        data = dict(expected_state_version=base.state_version,
                    expected_profile_reference=base.snapshot.profile.reference,
                    as_of=cutoff, generated_at=cutoff + timedelta(seconds=1), valid_until=cutoff + timedelta(days=1),
                    changes_complete_from=base.snapshot.profile.as_of, changes_complete_until=cutoff, events=events)
        data.update(changes)
        return ProfileUpdateRequest.model_validate(data)

    def event(record, *, before=None, revision=1, **changes):
        data = dict(event_id=f"{record.eligibility.target.purchase_request_id}-r{revision}",
                    target=record.eligibility.target, revision=revision,
                    previous_content_sha256=sample_content_hash(before) if before is not None else None,
                    available_at=record.eligibility.as_of, operation="UPSERT", reason="CORRECTION", sample=record)
        data.update(changes)
        return ProfileUpdateEvent.model_validate(data)

    return SimpleNamespace(state=state, request=request, event=event, sample=population.sample,
                           cutoff=population.cutoff, feature=population.feature)
