from copy import deepcopy
from datetime import timedelta

import pytest

from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest, PersonalReplayResult
from copenhagen_fds.personal_replay import comparison_cases, dataset_cases, run_personal_replay


def test_same_probe_across_users_and_accumulation_expiry_stages(replay_cases):
    result = run_personal_replay(replay_cases.data, replay_cases.request())
    left, right = result.rows[:5], result.rows[5:]
    assert [row.evaluation.result.features[0].stage for row in left] == ["COLD", "COLD", "TRANSITION", "PERSONALIZED", "COLD"]
    assert [row.evaluation.result.features[0].combined.score.value for row in left] == [0, 0, 50, 100, 0]
    assert [row.evaluation.result.features[0].combined.score.value for row in right] == [0, 0, 0, 0, 0]
    for a, b in zip(left, right):
        assert a.input.cold.raw_features.features == b.input.cold.raw_features.features
        assert a.input.cold.request.evaluation.target.user_id != b.input.cold.request.evaluation.target.user_id
    assert left[-1].input.personal == left[-2].input.personal
    assert left[-1].evaluation.result.profiles[0].status == "EXPIRED"


def test_replay_json_roundtrip_and_input_immutability(replay_cases):
    request = replay_cases.request()
    before = deepcopy(replay_cases.data), request.model_dump_json()
    result = run_personal_replay(replay_cases.data, request)
    assert run_personal_replay(replay_cases.data, request) == result
    restored = PersonalReplayResult.model_validate_json(result.model_dump_json())
    assert restored.validate_for_request(request) == result
    assert (replay_cases.data, request.model_dump_json()) == before


@pytest.mark.parametrize("change", ["truth", "personas", "future_feedback", "future_outcome", "future_purchase", "current_feedback"])
def test_hidden_or_future_information_does_not_change_model_input_or_scores(replay_cases, change):
    request = replay_cases.request()
    expected = run_personal_replay(replay_cases.data, request)
    data = deepcopy(replay_cases.data)
    if change in {"truth", "personas"}:
        data[change] = [{"scenario": "force score to zero", "is_fraud": True, "archetype": "hidden"}]
    elif change == "future_feedback":
        data["feedback"].append({**data["feedback"][0], "classification": "FRAUD_CONFIRMED", "available_at": "2026-02-01T00:00:00+09:00"})
    elif change == "future_outcome":
        data["outcomes"].append(dict(user_id="a", outcome_id="future", purchase_request_id="a-0", attempt_id="future",
            status="SUCCEEDED", occurred_at="2026-01-02T00:00:00+09:00", available_at="2026-02-01T00:00:00+09:00"))
    elif change == "future_purchase":
        data["purchases"].append({**data["purchases"][0], "amount_minor": 999999, "available_at": "2026-02-01T00:00:00+09:00"})
    else:
        data["feedback"].append({**data["feedback"][0], "feedback_id": "current-feedback",
            "purchase_request_id": request.cases[-1].input.evaluation.target.purchase_request_id})
    assert run_personal_replay(data, request) == expected


def test_future_review_becomes_eligible_only_after_its_availability(replay_cases):
    data = deepcopy(replay_cases.data)
    for feedback in data["feedback"]:
        if feedback["user_id"] == "a" and feedback["purchase_request_id"] != "a-4":
            feedback["available_at"] = "2026-01-04T12:30:00+09:00"
    result = run_personal_replay(data, replay_cases.request())
    earlier, later = result.rows[2], result.rows[3]
    assert earlier.input.personal.state.published.long_term.included_count == 0
    assert later.input.personal.state.published.long_term.included_count == 4
    assert earlier.evaluation.result.features[0].stage == "COLD"
    assert later.evaluation.result.features[0].stage == "PERSONALIZED"


def test_actual_dataset_replay_preserves_purchase_time_feature_transform(replay_cases):
    cases = dataset_cases(replay_cases.data, profile_as_of="2026-01-04T13:00:00+09:00",
        start="2026-01-05T00:00:00+09:00", end="2026-01-06T00:00:00+09:00", limit=2)
    result = run_personal_replay(replay_cases.data, PersonalReplayRequest(settings=replay_cases.settings, cases=cases))
    assert len(result.rows) == 2 and {row.case.mode for row in result.rows} == {"DATASET"}
    for row in result.rows:
        pid = row.input.cold.request.evaluation.target.purchase_request_id
        assert all(o.sample.eligibility.target.purchase_request_id != pid for o in row.input.personal.state.observations)
        assert row.input.cold.raw_features.features[0].value in {10000, 100000}
        assert row.input.cold.raw_features.features[7].value == 12


def test_current_purchase_excluded_even_if_already_known_at_profile_cutoff(replay_cases):
    cases = dataset_cases(replay_cases.data, profile_as_of="2026-01-03T13:00:00+09:00",
        start="2026-01-04T00:00:00+09:00", end="2026-01-05T00:00:00+09:00", limit=1)
    data = cases[0].model_dump()
    data["profile_as_of"] = "2026-01-04T13:00:00+09:00"
    data["input"]["evaluation"]["evaluated_at"] = "2026-01-04T14:00:00+09:00"
    request = PersonalReplayRequest(settings=replay_cases.settings, cases=[data])
    result = run_personal_replay(replay_cases.data, request)
    row = result.rows[0]
    current = row.case.input.evaluation.target.purchase_request_id
    assert current in {p["purchase_request_id"] for p in replay_cases.data["purchases"]}
    assert current not in {o.sample.eligibility.target.purchase_request_id for o in row.input.personal.state.observations}


@pytest.mark.parametrize("change", ["cutoff", "duplicate_case", "unknown_user", "unknown_version"])
def test_invalid_replay_context_is_rejected(replay_cases, change):
    data = replay_cases.request().model_dump()
    if change == "cutoff":
        data["cases"][0]["profile_as_of"] = data["cases"][0]["input"]["evaluation"]["evaluated_at"]
    elif change == "duplicate_case":
        data["cases"][1]["case_id"] = data["cases"][0]["case_id"]
    elif change == "unknown_user":
        data["cases"][0]["input"]["evaluation"]["request"]["context"]["user_id"] = "unknown"
    else:
        data["settings"]["personalization"]["score_scale_version"] = "unknown"
    with pytest.raises(ValueError):
        run_personal_replay(replay_cases.data, PersonalReplayRequest.model_validate(data))


@pytest.mark.parametrize("kwargs", [dict(limit=0), dict(start="2026-01-01T00:00:00+09:00"), dict(user_ids=["unknown"])])
def test_dataset_selection_errors(replay_cases, kwargs):
    options = dict(profile_as_of="2026-01-04T13:00:00+09:00", start="2026-01-05T00:00:00+09:00",
                   end="2026-01-06T00:00:00+09:00", limit=2)
    with pytest.raises(ValueError):
        dataset_cases(replay_cases.data, **{**options, **kwargs})


@pytest.mark.parametrize("kwargs", [dict(cutoffs=[]), dict(user_ids=["unknown"]), dict(user_ids=["a", "a"]),
    dict(amount_minor=-1), dict(user_limit=0), dict(expired_at="2026-01-01T00:00:00+09:00")])
def test_comparison_selection_errors(replay_cases, kwargs):
    options = dict(cutoffs=replay_cases.cutoffs, expired_at=replay_cases.expired)
    with pytest.raises(ValueError):
        comparison_cases(replay_cases.data, **{**options, **kwargs})
