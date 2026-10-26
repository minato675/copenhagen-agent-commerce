"""End-to-end Cold fixtures retain event identity, knowledge time and evidence."""

from copy import deepcopy
from datetime import datetime, timedelta
import json
from pathlib import Path

import pytest

from copenhagen_fds.cold import evaluate_cold
from copenhagen_fds.contracts.cold import ColdEvaluationInput
from copenhagen_fds.contracts.features import FEATURE_SPECS
from copenhagen_fds.contracts.scoring import ColdScoringResult


EXAMPLE = Path(__file__).resolve().parents[1] / "examples/cold_evaluation_input.json"
NOW = datetime.fromisoformat("2026-09-14T00:05:00+09:00")


@pytest.fixture
def bundle():
    return json.loads(EXAMPLE.read_text(encoding="utf-8"))


def run(bundle):
    return evaluate_cold(ColdEvaluationInput.model_validate(bundle["input"]),
                         population=bundle["population"], policy=bundle["policy"])


def raw(result):
    return {f.feature_id: f for f in result.raw_features.features}


def scores(result):
    return {f.feature_id: f for f in result.behavior.features}


def history(bundle):
    return bundle["input"]["feature_input"]["history"]


def event(bundle, identity="extra", **changes):
    item = deepcopy(history(bundle)["events"][0])
    item.update(event_id=identity, **changes)
    if item["attempt_id"] is not None:
        item["kind"] = "PAYMENT_ATTEMPTED"
    return item


def test_complete_path_transforms_all_nine_features_and_retains_shared_contract(bundle):
    result = run(bundle)
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "COMPLETE"
    assert tuple(raw(result)) == tuple(FEATURE_SPECS)
    assert [f.value for f in result.raw_features.features] == [10000, 1, 1, 1, 2, 2, 2, 0, 0]
    assert [f.score.value for f in result.behavior.features] == [0, 0, 0, 0, 25, 25, 25, 100, 0]
    assert result.behavior.metadata.target == result.raw_features.target == result.selection.target
    assert result.behavior.metadata.evaluated_at == NOW
    assert result.behavior.metadata.trace_id == bundle["input"]["trace_id"]
    assert result.behavior.metadata.sources == ("FDS",)
    assert result.personal_profile_status == "NOT_SUPPLIED"
    assert ColdScoringResult.model_validate_json(result.model_dump_json()) == result
    assert "risk_level" not in result.behavior.model_dump()


def test_new_user_with_known_empty_windows_can_use_policy_without_personal_history(bundle):
    bundle["population"] = None
    history(bundle)["events"] = []
    result = run(bundle)
    assert result.behavior.coverage == "COMPLETE"
    assert result.behavior.profiles == () and result.personal_profile_status == "NOT_SUPPLIED"
    assert all(f.value == 0 for name, f in raw(result).items() if "count" in name)
    assert all(f.score.value == 0 for name, f in scores(result).items() if "count" in name)
    assert all(f.bases[0].kind == "INITIAL_POLICY" for f in result.behavior.features)
    assert all(s.reference_support == "POLICY_ONLY" and s.population_sample_count is None for s in result.sufficiency)
    assert all(f.issues[0].code == "MISSING" for f in result.selection.features)


@pytest.mark.parametrize("missing", ["history", "current_time", "both"])
def test_missing_inputs_remain_null_through_the_full_path(bundle, missing):
    source = bundle["input"]["feature_input"]
    if missing in {"history", "both"}:
        source["history"] = None
    if missing in {"current_time", "both"}:
        source["current_occurred_at"] = source["current_available_at"] = None
    result = run(bundle)
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "PARTIAL"
    unavailable = [f for f in result.behavior.features if f.score is None]
    assert len(unavailable) == {"history": 6, "current_time": 2, "both": 8}[missing]
    assert all(f.status == "NOT_COLLECTED" and f.reasons == ("MISSING_INPUT",) for f in unavailable)
    assert all(not f.bases for f in unavailable)
    assert scores(result)["amount_minor"].score.value == 0
    assert result.behavior.reasons[0].code == "INSUFFICIENT_DATA"


def test_short_history_covers_ten_minutes_but_not_longer_windows(bundle):
    for name in ("purchases", "attempts"):
        history(bundle)[name]["complete_from"] = (NOW - timedelta(minutes=10)).isoformat()
    result = run(bundle)
    assert result.behavior.coverage == "PARTIAL"
    for name in ("purchase_count_10m", "attempt_count_10m"):
        assert scores(result)[name].status == "SCORED"
    for name in ("purchase_count_1h", "purchase_count_24h", "attempt_count_1h", "attempt_count_24h"):
        assert raw(result)[name].status == "INSUFFICIENT_HISTORY"
        assert scores(result)[name].score is None
        assert scores(result)[name].reasons == ("INSUFFICIENT_HISTORY",)


def test_inapplicable_attempt_stream_is_not_a_zero_or_execution_failure(bundle):
    history(bundle)["events"] = [e for e in history(bundle)["events"] if e["kind"] == "PURCHASE_REQUESTED"]
    history(bundle)["attempts"] = {"status": "NOT_APPLICABLE"}
    result = run(bundle)
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "PARTIAL"
    for name in ("attempt_count_10m", "attempt_count_1h", "attempt_count_24h"):
        assert scores(result)[name].status == "NOT_APPLICABLE"
        assert scores(result)[name].score is None


def test_no_references_keeps_raw_values_but_has_no_scores(bundle):
    bundle["population"] = bundle["policy"] = None
    result = run(bundle)
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "UNAVAILABLE"
    assert raw(result)["amount_minor"].value == 10000
    assert all(f.score is None and f.reasons == ("REFERENCE_UNAVAILABLE",) for f in result.behavior.features)
    assert all(s.reference_support == "UNAVAILABLE" for s in result.sufficiency)


@pytest.mark.parametrize("problem,code", [
    ("expired", "EXPIRED"), ("future", "FUTURE"), ("version", "VERSION_MISMATCH"),
    ("unit", "UNIT_MISMATCH"), ("currency", "CURRENCY_MISMATCH"),
    ("json", "INVALID_FORMAT"), ("scale", "INVALID_CONFIG"),
])
def test_invalid_population_diagnostics_survive_and_do_not_silently_fall_back(bundle, problem, code):
    population = bundle["population"]
    if problem == "expired":
        population["profile"]["valid_until"] = NOW.isoformat()
    elif problem == "future":
        population["available_at"] = (NOW + timedelta(microseconds=1)).isoformat()
    elif problem == "version":
        population["profile"]["reference"]["version"] = "different-version"
    elif problem == "unit":
        population["parameters"][0]["unit"] = "PURCHASE_COUNT"
    elif problem == "currency":
        population["profile"]["currency"] = "USD"
    elif problem == "json":
        bundle["population"] = "{invalid-json"
    else:
        population["parameters"][0]["scale"] = 0
    result = run(bundle)
    assert result.behavior.coverage == "UNAVAILABLE"
    assert all(f.issues[0].code == code for f in result.selection.features)
    assert all(f.score is None for f in result.behavior.features)
    assert raw(result)["purchase_count_10m"].value == 1


def test_expired_fallback_policy_preserves_valid_population_score(bundle):
    bundle["policy"]["valid_until"] = NOW.isoformat()
    result = run(bundle)
    assert result.behavior.coverage == "PARTIAL"
    assert scores(result)["amount_minor"].score.value == 0
    assert result.selection.features[1].issues[-1].code == "EXPIRED"
    assert all(f.score is None for f in result.behavior.features[1:])


@pytest.mark.parametrize("origin", ["SYNTHETIC", "OBSERVED"])
def test_provenance_versions_and_sample_limits_are_preserved(bundle, origin):
    bundle["population"]["profile"]["origin"] = origin
    result = run(bundle)
    basis = result.selection.features[0]
    assert basis.origin == origin and basis.validation_status == "UNVALIDATED"
    assert basis.source_reference == bundle["population"]["profile"]["source_reference"]
    assert basis.basis.profile.version == bundle["input"]["population_reference"]["version"]
    assert result.selection.selection_version == "cold-basis-selection-v1"
    assert result.behavior.scoring_version == "cold-distance-linear-v1"
    assert result.sufficiency[0].population_sample_count == 10
    for feature in result.selection.features[1:]:
        assert feature.policy_id == bundle["input"]["policy_id"]
        assert feature.basis.policy_version == bundle["input"]["policy_version"]
        assert feature.origin == "INITIAL_POLICY" and feature.validation_status == "UNVALIDATED"


def test_multi_merchant_order_and_purchase_event_copies_do_not_double_the_amount_or_count(bundle):
    order = bundle["input"]["feature_input"]["evaluation"]["request"]["order"]
    first = order["orders"][0]
    for money in (first["total"], first["items"][0]["unit_price"], first["items"][0]["line_total"]):
        money["amount_minor"] = 5000
    second = deepcopy(first)
    second.update(order_id="second-order", merchant_id="second-merchant")
    second["items"][0]["item_id"] = "second-item"
    order["orders"].append(second)
    result = run(bundle)
    assert raw(result)["amount_minor"].value == 10000
    assert raw(result)["purchase_count_10m"].value == 1  # duplicate and merchant copy already in fixture
    assert scores(result)["amount_minor"].score.value == 0


def test_retries_count_once_each_and_current_attempt_is_excluded(bundle):
    events = history(bundle)["events"]
    previous = deepcopy(events[-1])
    previous.update(event_id="retry-2", attempt_id="retry-2")
    current = deepcopy(previous)
    current.update(event_id="current", attempt_id=bundle["input"]["feature_input"]["current_attempt_id"])
    events.extend([previous, deepcopy(previous), current])
    result = run(bundle)
    assert raw(result)["attempt_count_10m"].value == 3
    assert scores(result)["attempt_count_10m"].score.value == 50
    assert raw(result)["purchase_count_10m"].value == 1


@pytest.mark.parametrize("seconds_ago,expected", [
    (-1, (0, 0, 0)), (0, (0, 0, 0)), (1, (1, 1, 1)),
    (599, (1, 1, 1)), (600, (1, 1, 1)), (601, (0, 1, 1)),
    (3599, (0, 1, 1)), (3600, (0, 1, 1)), (3601, (0, 0, 1)),
    (86399, (0, 0, 1)), (86400, (0, 0, 1)), (86401, (0, 0, 0)),
])
def test_time_window_boundaries_reach_the_final_scores(bundle, seconds_ago, expected):
    at = (NOW - timedelta(seconds=seconds_ago)).isoformat()
    history(bundle)["events"] = [event(bundle, occurred_at=at, available_at=at)]
    bundle["policy"]["version"] = bundle["input"]["policy_version"] = "boundary-v1"
    names = ("purchase_count_10m", "purchase_count_1h", "purchase_count_24h")
    for parameter in bundle["policy"]["parameters"]:
        if parameter["feature_id"] in names:
            parameter.update(center=0, scale=2)
    result = run(bundle)
    assert tuple(raw(result)[name].value for name in names) == expected
    assert tuple(scores(result)[name].score.value for name in names) == tuple(50 * n for n in expected)


@pytest.mark.parametrize("kind", ["late_event", "late_conflict", "other_currency", "current_purchase"])
def test_excluded_evidence_cannot_change_the_full_result(bundle, kind):
    expected = run(bundle)
    if kind == "late_event":
        extra = event(bundle, purchase_request_id="late-purchase", available_at=(NOW + timedelta(seconds=1)).isoformat())
    elif kind == "late_conflict":
        extra = event(bundle, identity="event-1", currency="USD", available_at=(NOW + timedelta(seconds=1)).isoformat())
    elif kind == "other_currency":
        extra = event(bundle, purchase_request_id="usd-purchase", currency="USD")
    else:
        source = bundle["input"]["feature_input"]
        extra = event(bundle, purchase_request_id="current-purchase", occurred_at=source["current_occurred_at"],
                      available_at=source["current_available_at"])
    history(bundle)["events"].append(extra)
    assert run(bundle) == expected


@pytest.mark.parametrize("problem", ["user", "snapshot", "future_history", "future_current", "missing_attempt", "conflict", "policy_pair", "personal_reference", "format"])
def test_invalid_context_is_rejected_before_score_execution(bundle, monkeypatch, problem):
    def unexpected(*args):
        pytest.fail("invalid request must not reach score calculation")
    monkeypatch.setattr("copenhagen_fds.cold_scoring.score_feature", unexpected)
    source = bundle["input"]["feature_input"]
    if problem == "user":
        history(bundle)["events"][0]["user_id"] = "another-user"
    elif problem == "snapshot":
        source["evaluation"]["request"]["order"]["purchase_request_id"] = "another-purchase"
    elif problem == "future_history":
        history(bundle)["as_of"] = (NOW + timedelta(seconds=1)).isoformat()
    elif problem == "future_current":
        source["current_available_at"] = (NOW + timedelta(seconds=1)).isoformat()
    elif problem == "missing_attempt":
        del source["current_attempt_id"]
    elif problem == "conflict":
        history(bundle)["events"].append(event(bundle, identity="event-1", currency="USD"))
    elif problem == "policy_pair":
        bundle["input"]["policy_version"] = None
    elif problem == "personal_reference":
        bundle["input"]["population_reference"] = {"profile_id": "personal", "version": "v1", "scope": "PERSONAL", "user_id": "synthetic-user"}
    else:
        bundle["input"]["format_version"] = "unrecognized-format"
    with pytest.raises(ValueError):
        run(bundle)


@pytest.mark.parametrize("missing_history", [False, True])
def test_calculation_failure_retains_other_scores_and_data_shortage_through_pipeline(bundle, monkeypatch, missing_history):
    from copenhagen_fds.scoring import score_feature

    def fail_amount(raw, reference):
        if raw.feature_id == "amount_minor":
            raise ArithmeticError("private-internal-detail")
        return score_feature(raw, reference)

    monkeypatch.setattr("copenhagen_fds.cold_scoring.score_feature", fail_amount)
    if missing_history:
        bundle["input"]["feature_input"]["history"] = None
    result = run(bundle)
    assert result.behavior.status == "FAILED" and result.behavior.coverage == "PARTIAL"
    assert scores(result)["amount_minor"].reasons == ("CALCULATION_FAILED",)
    assert scores(result)["amount_minor"].score is None
    assert scores(result)["kst_hour"].score.value == 100
    assert result.behavior.profiles[0].reference == result.selection.features[0].basis.profile
    assert {r.code for r in result.behavior.reasons} == ({"EVALUATION_FAILED", "INSUFFICIENT_DATA"} if missing_history else {"EVALUATION_FAILED"})
    assert "private-internal-detail" not in result.model_dump_json()
    assert ColdScoringResult.model_validate_json(result.model_dump_json()) == result


def test_pipeline_does_not_load_environment_or_read_files_for_evaluation(bundle, monkeypatch):
    supplied = ColdEvaluationInput.model_validate(bundle["input"])
    def unexpected(*args, **kwargs):
        pytest.fail("Cold evaluation must not load files or connection settings")
    monkeypatch.setattr(Path, "read_text", unexpected)
    monkeypatch.setattr("copenhagen_fds.settings.load_settings", unexpected)
    monkeypatch.setenv("FDS_MODE", "LIVE")  # unrelated GMS mode must not activate any provider
    result = evaluate_cold(supplied, population=bundle["population"], policy=bundle["policy"])
    assert result.behavior.coverage == "COMPLETE"


def test_same_snapshot_replays_without_mutation_and_event_order_does_not_change_result(bundle):
    before = deepcopy(bundle)
    expected = run(bundle).model_dump_json()
    for _ in range(3):
        assert run(json.loads(json.dumps(bundle))).model_dump_json() == expected
    assert bundle == before
    history(bundle)["events"].reverse()
    assert run(bundle).model_dump_json() == expected
    source = bundle["input"]["feature_input"]
    source["current_occurred_at"] = "2026-09-13T15:00:00+00:00"
    source["current_available_at"] = "2026-09-13T15:00:00+00:00"
    assert run(bundle).model_dump_json() == expected


def test_requested_subset_does_not_expand_during_pipeline(bundle):
    bundle["input"]["feature_input"]["evaluation"]["requested_features"] = ["kst_hour", "amount_minor"]
    result = run(bundle)
    assert result.behavior.requested_features == ("amount_minor", "kst_hour")
    assert [f.feature_id for f in result.selection.features] == ["amount_minor", "kst_hour"]
    assert len(result.sufficiency) == 2 and result.behavior.coverage == "COMPLETE"
