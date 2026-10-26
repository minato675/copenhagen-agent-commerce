"""S15P21A207-302: reuse the native candidate calculation without changing inputs."""

import pytest
from pydantic import ValidationError

from copenhagen_fds.provider_runtime.profile_candidate import build_profile_candidate


@pytest.mark.parametrize("population_scope,empty", [(False, False), (False, True), (True, False), (True, True)])
def test_candidate_matches_existing_statistics_once(profile_case, monkeypatch, population_scope, empty):
    import copenhagen_fds.provider_runtime.profile_candidate as module

    case = profile_case.create(population_scope=population_scope, empty=empty)
    before, calls = case.request.model_dump_json(), []
    name = "build_population_profile" if population_scope else "build_personal_profile"
    builder = getattr(module, name)

    def observe(request, samples):
        calls.append((request, samples))
        return builder(request, samples)

    monkeypatch.setattr(module, name, observe)
    candidate = build_profile_candidate(case.request)
    assert candidate == case.state.candidate.snapshot
    assert calls == [(case.request.profile, tuple(row.sample for row in case.request.replay.observations))]
    assert candidate.profile.reference != case.state.published.profile.reference
    assert case.request.model_dump_json() == before


def test_unvalidated_foreign_user_is_rejected_instead_of_silently_filtered(profile_case):
    case = profile_case.create()
    foreign = profile_case.create(population_scope=True).request.replay.observations[-1]
    replay = case.request.replay.model_copy(update={"observations": (foreign,)})
    forged = case.request.model_copy(update={"replay": replay})
    with pytest.raises(ValidationError, match="another user's observation"):
        build_profile_candidate(forged)
