"""S15P21A207-357: observable profile chronology and explicit update failures."""

from copy import deepcopy
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyUpdateResult
from copenhagen_fds.native_cli.scenarios import observable_demo
from copenhagen_fds.personal_replay import _probe_input
from copenhagen_fds.synthetic.replay import ReplayIndex
from copenhagen_fds.temporal_benchmark import profiles
from copenhagen_fds.temporal_benchmark.profiles import ProfileTimeline


@pytest.fixture
def timeline_case():
    settings = PersonalReplaySettings.model_validate_json(
        (Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json").read_text(encoding="utf-8"))
    return observable_demo(), settings


def probe(at, *, user="demo-user"):
    when = datetime.fromisoformat(at)
    return _probe_input(user, "KRW", 10000, when - timedelta(microseconds=1), when)


def observations(publication):
    return {row.sample.eligibility.target.purchase_request_id: row for row in publication.state.observations}


def test_initial_purchase_has_empty_profiles_and_prior_publication_cutoff(timeline_case):
    data, settings = timeline_case
    feature = ReplayIndex(data).feature_input("demo-prior-0")
    result = ProfileTimeline(data, settings, "timeline-dataset-v1").before(feature)
    for publication in (result.personal, result.population):
        assert not publication.state.observations
        assert publication.state.published.long_term.included_count == 0
        assert publication.state.published.profile.as_of == feature.evaluation.evaluated_at - timedelta(microseconds=1)
        assert publication.available_at < feature.evaluation.evaluated_at
    assert result.audit["personal"]["status"] == "CREATED"
    assert result.population_baseline_request.expected_source_reference == result.population.expected_reference


def test_purchase_sequence_updates_prior_state_and_never_learns_current(timeline_case):
    data, settings = timeline_case
    original = deepcopy(data)
    timeline = ProfileTimeline(data, settings, "timeline-dataset-v1")
    index = ReplayIndex(data)
    prior = None
    for day in range(5):
        pid = f"demo-prior-{day}"
        result = timeline.before(index.feature_input(pid))
        for publication in (result.personal, result.population):
            assert pid not in observations(publication)
            assert len(publication.state.observations) == day
        if prior is not None:
            assert result.audit["personal"]["previous_state_version"] == prior.personal.state.state_version
            assert result.audit["personal"]["status"] == "UPDATED"
        prior = result
    assert data == original
    assert prior.personal.state.published.long_term.included_count == 4


def test_feedback_enters_only_after_available_time_and_audits_admission(timeline_case):
    data, settings = timeline_case
    for row in data["feedback"]:
        row["available_at"] = "2026-01-06T12:00:00+09:00"
    timeline = ProfileTimeline(data, settings, "timeline-dataset-v1")
    before = timeline.before(probe("2026-01-06T11:00:00+09:00"))
    after = timeline.before(probe("2026-01-06T13:00:00+09:00"))
    assert before.personal.state.published.long_term.included_count == 0
    assert after.personal.state.published.long_term.included_count > 0
    assert any(row["action"] == "ADMITTED" for row in after.audit["personal"]["transitions"])
    assert before.personal.state.state_version != after.personal.state.state_version


@pytest.mark.parametrize("change", ["future_feedback", "future_purchase", "future_user", "truth"])
def test_future_and_hidden_records_do_not_change_past_hashes(timeline_case, change):
    data, settings = timeline_case
    altered = deepcopy(data)
    if change == "future_feedback":
        altered["feedback"].append({**data["feedback"][0], "classification": "FRAUD_CONFIRMED",
                                   "available_at": "2026-03-01T12:00:00+09:00"})
    elif change == "future_purchase":
        altered["purchases"].append({**data["purchases"][0], "amount_minor": 12345,
                                    "available_at": "2026-03-01T12:00:00+09:00"})
    elif change == "future_user":
        altered["users"].append({**data["users"][0], "user_id": "future-user",
                                "registered_at": "2026-03-01T12:00:00+09:00"})
    else:
        altered["truth"] = [{"is_fraud": True, "scenario": "force classification"}]
        altered["personas"] = [{"user_id": "demo-user", "pattern": "hidden"}]
    feature = probe("2026-01-06T13:00:00+09:00")
    expected = ProfileTimeline(data, settings, "timeline-dataset-v1").before(feature)
    actual = ProfileTimeline(altered, settings, "timeline-dataset-v1").before(feature)
    assert actual == expected


def test_permutation_and_duplicate_delivery_preserve_publications(timeline_case):
    data, settings = timeline_case
    altered = {name: list(reversed(rows)) for name, rows in data.items()}
    altered["events"].append(deepcopy(data["events"][0]))
    altered["feedback"].append(deepcopy(data["feedback"][0]))
    feature = probe("2026-01-06T13:00:00+09:00")
    assert ProfileTimeline(altered, settings, "timeline-dataset-v1").before(feature) == (
        ProfileTimeline(data, settings, "timeline-dataset-v1").before(feature))


def test_registration_change_rebuilds_only_population_with_explicit_reason(timeline_case):
    data, settings = timeline_case
    data["users"].append({**data["users"][0], "user_id": "new-user",
                          "registered_at": "2026-01-06T12:00:00+09:00"})
    timeline = ProfileTimeline(data, settings, "timeline-dataset-v1")
    before = timeline.before(probe("2026-01-06T11:00:00+09:00"))
    after = timeline.before(probe("2026-01-06T13:00:00+09:00"))
    assert before.population.state.published.cohort.member_user_ids == ("demo-user",)
    assert after.population.state.published.cohort.member_user_ids == ("demo-user", "new-user")
    assert after.audit["population"]["status"] == "REBUILT"
    assert after.audit["population"]["rebuild_reasons"] == ["COHORT_MEMBERSHIP_CHANGED"]
    assert after.audit["personal"]["status"] == "UPDATED"


def test_already_retained_current_purchase_is_rebuilt_without_it(timeline_case):
    data, settings = timeline_case
    timeline = ProfileTimeline(data, settings, "timeline-dataset-v1")
    before = timeline.before(probe("2026-01-06T11:00:00+09:00"))
    current = "demo-prior-4"
    feature = ReplayIndex(data).feature_input(current).model_dump()
    feature["evaluation"]["evaluated_at"] = datetime.fromisoformat("2026-01-06T13:00:00+09:00")
    from copenhagen_fds.contracts.features import FeatureTransformInput

    assert current in observations(before.personal)
    after = timeline.before(FeatureTransformInput.model_validate(feature))
    for scope in ("personal", "population"):
        assert current not in observations(getattr(after, scope))
        assert after.audit[scope]["status"] == "REBUILT"
        assert after.audit[scope]["rebuild_reasons"] == ["CURRENT_PURCHASE_EXCLUSION"]


@pytest.mark.parametrize("status,issue", [("REJECTED", "POLICY_VERSION_MISMATCH"),
                                          ("REPLAY_REQUIRED", "CHANGE_HISTORY_GAP")])
def test_failed_updates_are_visible_without_implicit_rebuild(timeline_case, monkeypatch, status, issue):
    data, settings = timeline_case
    timeline = ProfileTimeline(data, settings, "timeline-dataset-v1")
    before = timeline.before(probe("2026-01-06T11:00:00+09:00"))

    def failure(state, request, rows):
        return ProfilePolicyUpdateResult(status=status, issue=issue, previous_state_version=state.state_version,
                                         previous_published_reference=state.published.profile.reference)

    monkeypatch.setattr(profiles, "update_profile_policy", failure)
    after = timeline.before(probe("2026-01-06T13:00:00+09:00"))
    assert after.personal is None and after.population is None and after.population_baseline_request is None
    for scope in ("personal", "population"):
        assert after.audit[scope]["status"] == status
        assert after.audit[scope]["issue"] == issue
        assert after.audit[scope]["published"] is False
        assert after.audit[scope]["state_version"] == getattr(before, scope).state.state_version


def test_time_regression_and_unregistered_user_are_rejected(timeline_case):
    data, settings = timeline_case
    timeline = ProfileTimeline(data, settings, "timeline-dataset-v1")
    timeline.before(probe("2026-01-06T13:00:00+09:00"))
    with pytest.raises(ValueError, match="chronological"):
        timeline.before(probe("2026-01-06T11:00:00+09:00"))
    with pytest.raises(ValueError, match="observable user"):
        timeline.before(probe("2026-01-06T14:00:00+09:00", user="absent"))
