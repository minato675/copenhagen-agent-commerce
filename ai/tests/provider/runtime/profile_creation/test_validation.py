"""S15P21A207-302: malformed or unauthorized creation stops before fitting."""

import json
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.provider_runtime.access import ResourceScope


@pytest.mark.parametrize("mutation", ["owner", "currency", "cohort", "member", "future_membership",
    "future_review", "future_features", "incomplete_range", "wrong_cutoff", "duplicate", "truth", "persona"])
def test_invalid_creation_never_reaches_statistics(creation_case, run_async, monkeypatch, mutation):
    case = creation_case(population_scope=mutation in {"cohort", "member", "future_membership"})
    data = case.request.model_dump(mode="json")
    profile, replay = data["profile"], data["replay"]
    if mutation == "owner":
        profile["user_id"] = profile["reference"]["user_id"] = "foreign-user"
    elif mutation == "currency":
        profile["currency"] = "USD"
    elif mutation == "cohort":
        profile["reference"]["cohort_id"] = "other-cohort"
    elif mutation == "member":
        profile["cohort"]["member_user_ids"] = ["a"]
    elif mutation == "future_membership":
        profile["cohort"]["available_at"] = profile["valid_until"]
    elif mutation == "future_review":
        replay["observations"][0]["feedback"]["available_at"] = profile["valid_until"]
    elif mutation == "future_features":
        replay["observations"][0]["sample"]["raw_features"]["evaluated_at"] = profile["valid_until"]
    elif mutation == "incomplete_range":
        replay["complete_from"] = (case.request.replay.complete_from + timedelta(days=1)).isoformat()
    elif mutation == "wrong_cutoff":
        replay["observations"][0]["sample"]["eligibility"]["as_of"] = profile["generated_at"]
    elif mutation == "duplicate":
        replay["observations"].append(replay["observations"][0])
    else:
        replay["observations"][0][mutation] = "synthetic-answer-must-not-train"
    case.bound.raw = json.dumps(data).encode()
    monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_creation.build_profile_candidate",
                        lambda *_: pytest.fail("invalid data must be rejected before statistics"))
    encoded = run_async(case.execute())
    assert ProviderError.model_validate_json(encoded).code == "INVALID_CONTRACT"
    assert b"foreign-user" not in encoded and b"synthetic-answer" not in encoded


@pytest.mark.parametrize("missing_scope", ["user_ids", "profile_ids", "cohort_ids", "empty_member"])
def test_population_creation_requires_each_authorized_resource(creation_case, run_async, monkeypatch, missing_scope):
    fields = dict(user_ids={"a", "b", "empty"}, profile_ids={"population-cohort"}, cohort_ids={"cohort"})
    if missing_scope == "empty_member":
        fields["user_ids"].remove("empty")
    else:
        fields[missing_scope] = set()
    case = creation_case(population_scope=True, scope=ResourceScope(**fields))
    monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_creation.build_profile_candidate",
                        lambda *_: pytest.fail("unauthorized data must be rejected before statistics"))
    assert ProviderError.model_validate_json(run_async(case.execute())).code == "FORBIDDEN"


def test_no_service_credential_cannot_create_profile(creation_case, run_async, monkeypatch):
    case = creation_case()
    case.bound.credential = None
    monkeypatch.setattr("copenhagen_fds.provider_runtime.profile_creation.build_profile_candidate",
                        lambda *_: pytest.fail("authentication must precede statistics"))
    assert ProviderError.model_validate_json(run_async(case.execute())).code == "UNAUTHENTICATED"
