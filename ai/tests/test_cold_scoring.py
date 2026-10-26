"""Cold state/evidence mapping, request isolation and deterministic replay."""

from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys

import pytest
from copenhagen_fds.baselines import select_cold_baselines
from copenhagen_fds.cold_scoring import score_cold_features
from copenhagen_fds.contracts.baselines import BaselineSelectionRequest
from copenhagen_fds.contracts.behavior import AnomalyScore, BehaviorEvaluationResult
from copenhagen_fds.contracts.features import FEATURE_SPECS, RawFeatureVector
from copenhagen_fds.contracts.scoring import ColdScoringInput, ColdScoringResult


AI_DIR = Path(__file__).resolve().parents[1]


@pytest.fixture
def candidates():
    return json.loads((AI_DIR / "examples/cold_baseline_input.json").read_text(encoding="utf-8"))


def supplied_for(candidates):
    request = BaselineSelectionRequest.model_validate(candidates["request"])
    features = []
    for name, value in {"amount_minor": 10000, "purchase_count_10m": 6, "kst_hour": 0}.items():
        unit, window = FEATURE_SPECS[name]
        features.append({"feature_id": name, "status": "AVAILABLE", "value": value,
                         "unit": unit, "window_seconds": window,
                         "currency": None if name == "kst_hour" else "KRW"})
    return ColdScoringInput(
        request=request, trace_id="synthetic-trace",
        raw_features=RawFeatureVector(target=request.evaluation.target,
                                     evaluated_at=request.evaluation.evaluated_at, features=features),
        selection=select_cold_baselines(request, population=candidates.get("population"),
                                        policy=candidates.get("policy")),
    )


@pytest.fixture
def supplied(candidates):
    return supplied_for(candidates)


def with_raw_state(supplied, name, state, value=None):
    payload = supplied.model_dump(mode="json")
    feature = next(f for f in payload["raw_features"]["features"] if f["feature_id"] == name)
    feature.update(status=state, value=value)
    return ColdScoringInput.model_validate(payload)


def test_representative_result_preserves_scores_reasons_versions_and_sources(supplied):
    output = score_cold_features(supplied)
    result = output.behavior
    assert result.status == "SUCCESS" and result.coverage == "COMPLETE"
    assert [(f.score.value, f.reasons) for f in result.features] == [
        (0, ("WITHIN_REFERENCE",)), (50, ("PATTERN_DEVIATION",)), (100, ("PATTERN_DEVIATION",)),
    ]
    assert result.metadata.evaluated_at == supplied.request.evaluation.evaluated_at
    assert result.metadata.trace_id == supplied.trace_id
    assert result.metadata.sources == ("FDS",)
    assert result.metadata.valid_until is None
    assert result.scoring_version == "cold-distance-linear-v1"
    assert output.selection == supplied.selection
    assert output.raw_features == supplied.raw_features
    assert result.profiles == supplied.selection.profiles
    assert result.features[0].bases[0].profile.version == "demo-v1"
    assert result.features[1].bases[0].policy_version == "demo-v1"
    assert output.personal_profile_status == "NOT_SUPPLIED"
    assert [(s.reference_support, s.population_sample_count) for s in output.sufficiency] == [
        ("POPULATION_SAMPLES", 10), ("POLICY_ONLY", None), ("POLICY_ONLY", None),
    ]
    assert all(f.validation_status == "UNVALIDATED" for f in output.selection.features)
    assert output.selection.features[0].origin == "SYNTHETIC"
    assert output.selection.features[1].origin == "INITIAL_POLICY"
    assert result.validate_for_request(supplied.evaluation_with_selected_profiles()) is result
    assert BehaviorEvaluationResult.model_validate_json(result.model_dump_json()) == result
    assert "risk_level" not in result.model_dump() and "fraud_probability" not in result.model_dump()


def test_new_user_can_receive_policy_scores_without_fabricated_population_samples(candidates):
    candidates["population"] = None
    result = score_cold_features(supplied_for(candidates))
    assert result.personal_profile_status == "NOT_SUPPLIED"
    # The policy intentionally has no amount reference; the other two still score.
    assert result.behavior.features[0].score is None
    assert [f.score.value for f in result.behavior.features[1:]] == [50, 100]
    assert result.behavior.profiles == ()
    assert all(s.population_sample_count is None for s in result.sufficiency)
    assert result.selection.features[1].issues[0].code == "MISSING"


@pytest.mark.parametrize("state,status,reason", [
    ("NOT_COLLECTED", "NOT_COLLECTED", "MISSING_INPUT"),
    ("INSUFFICIENT_HISTORY", "INSUFFICIENT_DATA", "INSUFFICIENT_HISTORY"),
    ("NOT_APPLICABLE", "NOT_APPLICABLE", "NOT_APPLICABLE"),
])
def test_missing_history_and_inapplicable_input_are_not_zero(supplied, state, status, reason):
    output = score_cold_features(with_raw_state(supplied, "purchase_count_10m", state))
    result = output.behavior
    feature = result.features[1]
    assert result.status == "SUCCESS" and result.coverage == "PARTIAL"
    assert feature.status == status and feature.reasons == (reason,)
    assert feature.score is None and feature.bases == ()
    assert result.reasons[0].code == "INSUFFICIENT_DATA"
    assert output.selection.features[1].status == "SELECTED"  # selected, but not applied
    assert output.personal_profile_status == "NOT_SUPPLIED"


def test_known_empty_history_is_available_zero_but_unknown_history_is_not(supplied):
    empty = score_cold_features(with_raw_state(supplied, "purchase_count_10m", "AVAILABLE", 0))
    unknown = score_cold_features(with_raw_state(supplied, "purchase_count_10m", "INSUFFICIENT_HISTORY"))
    assert empty.behavior.features[1].score.value == 0
    assert empty.behavior.features[1].status == "SCORED"
    assert unknown.behavior.features[1].score is None
    assert unknown.behavior.coverage == "PARTIAL"


def test_missing_current_time_input_does_not_invent_a_midnight_score(supplied):
    result = score_cold_features(with_raw_state(supplied, "kst_hour", "NOT_COLLECTED"))
    assert result.behavior.features[2].status == "NOT_COLLECTED"
    assert result.behavior.features[2].score is None
    assert result.raw_features.features[2].value is None


def test_all_inputs_missing_keeps_unavailable_coverage_and_no_applied_profiles(supplied):
    for feature in supplied.raw_features.features:
        supplied = with_raw_state(supplied, feature.feature_id, "NOT_COLLECTED")
    result = score_cold_features(supplied).behavior
    assert result.status == "SUCCESS" and result.coverage == "UNAVAILABLE"
    assert result.profiles == ()
    assert all(f.score is None and not f.bases for f in result.features)
    assert [r.code for r in result.reasons] == ["INSUFFICIENT_DATA"]


def test_missing_all_references_is_unavailable_with_diagnostics(candidates):
    candidates["population"] = candidates["policy"] = None
    result = score_cold_features(supplied_for(candidates))
    assert result.behavior.status == "SUCCESS" and result.behavior.coverage == "UNAVAILABLE"
    for feature, support in zip(result.behavior.features, result.sufficiency, strict=True):
        assert feature.status == "INSUFFICIENT_DATA"
        assert feature.reasons == ("REFERENCE_UNAVAILABLE",)
        assert feature.score is None and feature.bases == ()
        assert support.reference_support == "UNAVAILABLE"
    assert result.selection.features[0].issues[0].code == "MISSING"


def test_missing_input_has_priority_but_missing_reference_diagnostics_remain(candidates):
    candidates["population"] = candidates["policy"] = None
    supplied = with_raw_state(supplied_for(candidates), "kst_hour", "NOT_COLLECTED")
    result = score_cold_features(supplied)
    assert result.behavior.features[2].reasons == ("MISSING_INPUT",)
    assert result.selection.features[2].status == "UNAVAILABLE"


def test_unused_population_stays_in_selection_but_is_not_claimed_as_applied(supplied):
    output = score_cold_features(with_raw_state(supplied, "amount_minor", "NOT_COLLECTED"))
    assert output.behavior.profiles == ()
    assert output.selection.profiles == supplied.selection.profiles
    assert output.behavior.features[0].bases == ()


@pytest.mark.parametrize("bad_score", [None, -1, 101, float("nan"), float("inf")])
def test_calculation_failure_preserves_other_features_and_never_returns_zero(supplied, monkeypatch, bad_score):
    from copenhagen_fds.scoring import score_feature

    def injected(raw, reference):
        if raw.feature_id == "amount_minor":
            if bad_score is None:
                raise ArithmeticError("internal details must not reach the result")
            return AnomalyScore.model_construct(value=bad_score, unit="BEHAVIOR_ANOMALY_0_100")
        return score_feature(raw, reference)

    monkeypatch.setattr("copenhagen_fds.cold_scoring.score_feature", injected)
    output = score_cold_features(supplied)
    result = output.behavior
    assert result.status == "FAILED" and result.coverage == "PARTIAL"
    assert result.reasons[0].code == "EVALUATION_FAILED"
    assert result.features[0].score is None and result.features[0].reasons == ("CALCULATION_FAILED",)
    assert result.features[0].bases == (supplied.selection.features[0].basis,)
    assert [f.score.value for f in result.features[1:]] == [50, 100]
    assert result.profiles == supplied.selection.profiles
    assert result.metadata.valid_until is None
    assert "internal details" not in output.model_dump_json()
    assert ColdScoringResult.model_validate_json(output.model_dump_json()).validate_for_input(supplied) == output


def test_all_calculations_failed_is_not_normal_or_data_shortage(supplied, monkeypatch):
    def injected(*args):
        raise ArithmeticError()
    monkeypatch.setattr("copenhagen_fds.cold_scoring.score_feature", injected)
    result = score_cold_features(supplied).behavior
    assert result.status == "FAILED" and result.coverage == "UNAVAILABLE"
    assert all(f.score is None for f in result.features)
    assert [r.code for r in result.reasons] == ["EVALUATION_FAILED"]


@pytest.mark.parametrize("samples", [0, 10])
def test_personal_profiles_are_reported_but_never_used_in_cold(candidates, samples):
    profile = deepcopy(candidates["population"]["profile"])
    profile["reference"] = {"profile_id": "personal-demo", "version": "personal-v1", "scope": "PERSONAL", "user_id": "synthetic-user"}
    if not samples:
        profile["observations"] = [{"feature_id": "amount_minor", "valid_sample_count": 0}]
    candidates["request"]["evaluation"]["profiles"] = [profile]
    result = score_cold_features(supplied_for(candidates))
    assert result.personal_profile_status == "SUPPLIED_NOT_USED"
    assert [f.score.value for f in result.behavior.features] == [0, 50, 100]
    assert all(p.reference.scope == "POPULATION" for p in result.behavior.profiles)
    assert all(b.kind != "PERSONAL_PROFILE" for f in result.behavior.features for b in f.bases)


@pytest.mark.parametrize("section", ["raw_features", "selection"])
@pytest.mark.parametrize("problem", ["user", "purchase", "snapshot", "time", "schema", "feature", "currency"])
def test_rejects_mismatched_evidence_before_calculating(supplied, section, problem, monkeypatch):
    def unexpected(*args):
        pytest.fail("invalid context must be rejected before scoring")
    monkeypatch.setattr("copenhagen_fds.cold_scoring.score_feature", unexpected)
    payload = supplied.model_dump(mode="json")
    evidence = payload[section]
    if problem == "user":
        evidence["target"]["user_id"] = "other-user"
    elif problem == "purchase":
        evidence["target"]["purchase_request_id"] = "other-purchase"
    elif problem == "snapshot":
        evidence["target"]["order"]["version"] = "other-version"
    elif problem == "time":
        evidence["evaluated_at"] = "2026-09-14T00:06:00+09:00"
    elif problem == "schema":
        evidence["feature_schema_version"] = "future-schema"
    elif problem == "feature":
        evidence["features"].pop()
    elif section == "selection":
        evidence["currency"] = "USD"
    else:
        evidence["features"][0]["currency"] = "USD"
    with pytest.raises(ValueError):
        score_cold_features(ColdScoringInput.model_validate(payload))


@pytest.mark.parametrize("field", ["policy_version", "population_reference"])
def test_rejects_different_pinned_reference_version(supplied, field):
    payload = supplied.model_dump(mode="json")
    if field == "policy_version":
        payload["request"][field] = "v2"
    else:
        payload["request"][field]["version"] = "v2"
    with pytest.raises(ValueError):
        ColdScoringInput.model_validate(payload)


@pytest.mark.parametrize("problem", ["reason", "sample_count", "origin", "scoring_version", "raw_status", "profile_observation", "trace", "personal_status", "validity"])
def test_output_cannot_claim_different_evidence(supplied, problem):
    payload = score_cold_features(supplied).model_dump(mode="json")
    if problem == "reason":
        payload["behavior"]["features"][0]["reasons"] = ["PATTERN_DEVIATION"]
    elif problem == "sample_count":
        payload["sufficiency"][0]["population_sample_count"] = 1000
    elif problem == "origin":
        payload["selection"]["features"][0]["origin"] = "OBSERVED"
    elif problem == "scoring_version":
        payload["behavior"]["scoring_version"] = "unrecognized-version"
    elif problem == "raw_status":
        payload["raw_features"]["features"][0].update(status="NOT_COLLECTED", value=None)
    elif problem == "profile_observation":
        payload["behavior"]["profiles"][0]["observations"][0]["valid_sample_count"] = 1000
    elif problem == "trace":
        payload["behavior"]["metadata"]["trace_id"] = "other-trace"
    elif problem == "validity":
        payload["behavior"]["metadata"]["valid_until"] = "2026-09-14T00:10:00+09:00"
    else:
        payload["personal_profile_status"] = "SUPPLIED_NOT_USED"
    with pytest.raises(ValueError):
        ColdScoringResult.model_validate(payload).validate_for_input(supplied)


def test_same_inputs_and_versions_replay_identical_results_without_mutation(supplied):
    before = supplied.model_dump_json()
    expected = score_cold_features(supplied).model_dump_json()
    for _ in range(5):
        restored = ColdScoringInput.model_validate_json(before)
        output = score_cold_features(restored)
        assert output.model_dump_json() == expected
        assert ColdScoringResult.model_validate_json(expected).validate_for_input(restored) == output
    assert supplied.model_dump_json() == before
    assert supplied.request.evaluation.profiles == ()  # binding snapshots never mutates the caller


def test_changed_reference_parameters_require_replay_of_exact_snapshot(supplied):
    payload = supplied.model_dump(mode="json")
    payload["selection"]["features"][1]["parameter"]["scale"] = 16
    changed = ColdScoringInput.model_validate(payload)
    result = score_cold_features(changed)
    assert result.behavior.features[1].score.value == 25
    with pytest.raises(ValueError):
        result.validate_for_input(supplied)


@pytest.mark.parametrize("scenario,coverage,index,status,value", [
    ("representative", "COMPLETE", 1, "SCORED", 50),
    ("empty_history", "COMPLETE", 1, "SCORED", 0),
    ("missing_history", "PARTIAL", 1, "INSUFFICIENT_DATA", None),
    ("missing_input", "PARTIAL", 2, "NOT_COLLECTED", None),
    ("no_reference", "UNAVAILABLE", 0, "INSUFFICIENT_DATA", None),
    ("not_applicable", "PARTIAL", 1, "NOT_APPLICABLE", None),
])
def test_documented_demo_outputs_valid_replayable_json(scenario, coverage, index, status, value):
    command = [sys.executable, "examples/cold_scoring_demo.py", "--scenario", scenario]
    first = subprocess.run(command, cwd=AI_DIR, check=True, capture_output=True, text=True, timeout=15)
    second = subprocess.run(command, cwd=AI_DIR, check=True, capture_output=True, text=True, timeout=15)
    assert first.stdout == second.stdout and first.stderr == ""
    result = ColdScoringResult.model_validate_json(first.stdout).behavior
    assert result.coverage == coverage
    feature = result.features[index]
    assert feature.status == status
    assert (feature.score.value if feature.score is not None else None) == value
