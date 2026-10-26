from datetime import timedelta, timezone

import pytest

from copenhagen_fds.cold_scoring import score_cold_features
from copenhagen_fds.contracts.personal_evaluation import PersonalizedEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.personal_evaluation import evaluate_personalized_features


@pytest.mark.parametrize("count,weight,stage", [(2, 0.0, "COLD"), (3, 0.5, "TRANSITION"), (4, 1.0, "PERSONALIZED")])
def test_hand_calculated_mixture_and_full_evidence_chain(evaluation_cases, count, weight, stage):
    profile = evaluation_cases.publication([{"days": d} for d in range(1, count + 1)])
    supplied = evaluation_cases.request(profile=profile,
        values={"purchase_count_10m": 2, "kst_hour": 1}, cold_parameters={"amount_minor": {"center": 10000.0}})
    report = evaluate_personalized_features(supplied)
    amount, frequency, hour = report.result.features[0], report.result.features[1], report.result.features[7]
    assert amount.combined.score.value == weight * 100
    assert frequency.combined.score.value == pytest.approx((1 - weight) * 100 + weight * 200 / 3)
    # Historical hour 0: current hour 1 has mass 1+n/4, peak mass 1+n.
    assert hour.combined.score.value == pytest.approx((1 - weight) * 100 + weight * 100 * (1 - (1 + count / 4) / (1 + count)))
    assert {f.stage for f in report.result.features} == {stage}
    assert all(f.personal_weight == weight and f.cold_weight == 1 - weight for f in report.result.features)
    for i, feature in enumerate(report.result.features):
        assert report.result.cold.raw_features.features[i].feature_id == feature.combined.feature_id
        assert feature.evidence[0].distribution.observation.valid_sample_count == count
        assert feature.cold.bases == (report.result.cold.selection.features[i].basis,)
        assert report.components.transition.readiness.features[i].windows[0].measures.valid_samples == count
    assert report.scale_agreements[0].rule == "UPPER_DEVIATION"
    assert report.scale_agreements[7].rule == "CIRCULAR_DISTANCE_RARITY_HEURISTIC"
    assert report.result.behavior.status == "SUCCESS" and report.result.behavior.coverage == "COMPLETE"


@pytest.mark.parametrize("kind", ["absent", "expired", "insufficient", "coverage"])
def test_unused_personal_reference_preserves_exact_cold_regression(evaluation_cases, kind):
    kwargs = {}
    if kind == "absent":
        kwargs["profile"] = False
    elif kind == "expired":
        profile = evaluation_cases.publication()
        kwargs.update(profile=profile, at=profile.state.published.profile.valid_until)
    elif kind == "insufficient":
        kwargs["profile"] = evaluation_cases.publication([{"days": 1}])
    else:
        kwargs["requested"] = ("purchase_count_10m",)
        kwargs["profile"] = evaluation_cases.publication([
            {"days": d, "purchase_coverage": {"status": "NOT_COLLECTED"}} for d in (1, 2, 3, 4)])
    supplied = evaluation_cases.request(**kwargs)
    report = evaluate_personalized_features(supplied)
    cold = score_cold_features(supplied.cold)
    assert report.result.cold == cold
    assert report.result.behavior.features == cold.behavior.features
    assert all(f.stage == "COLD" and f.personal_weight == 0 for f in report.result.features)
    assert all(f.personal.score is None for f in report.result.features)


@pytest.mark.parametrize("state,expected", [("NOT_COLLECTED", "NOT_COLLECTED"),
    ("INSUFFICIENT_HISTORY", "INSUFFICIENT_DATA"), ("NOT_APPLICABLE", "NOT_APPLICABLE")])
def test_missing_current_input_preserves_cause_without_zero_score(evaluation_cases, state, expected):
    report = evaluate_personalized_features(evaluation_cases.request(states={"purchase_count_10m": state}))
    row = report.result.features[1]
    assert row.cold.status == row.personal.status == row.combined.status == expected
    assert row.combined.score is None and row.personal_weight is None and row.cold_weight is None
    assert row.stage == "UNAVAILABLE" and report.components.features[1].candidate is None
    assert report.result.behavior.coverage == "PARTIAL" and report.result.behavior.status == "SUCCESS"
    assert report.result.features[0].combined.status == "SCORED"


@pytest.mark.parametrize("count", [0, 3, 4])
def test_no_cold_never_promotes_partial_personal_support(evaluation_cases, count):
    profile = evaluation_cases.publication([{"days": d} for d in range(1, count + 1)]) if count else False
    report = evaluate_personalized_features(evaluation_cases.request(profile=profile, cold=False))
    for row in report.result.features:
        if count == 4:
            assert row.stage == "PERSONALIZED" and row.personal_weight == 1 and row.cold_weight == 0
        else:
            assert row.stage == "UNAVAILABLE" and row.combined.status == "INSUFFICIENT_DATA"
            assert row.combined.score is None and row.personal_weight is None
    assert report.result.behavior.status == "SUCCESS"


@pytest.mark.parametrize("count", [3, 4])
def test_incompatible_numeric_direction_is_not_silently_blended(evaluation_cases, count):
    profile = evaluation_cases.publication([{"days": d} for d in range(1, count + 1)])
    report = evaluate_personalized_features(evaluation_cases.request(profile=profile,
        cold_parameters={"amount_minor": {"method": "ABSOLUTE_DISTANCE", "center": 20000.0, "scale": 20000.0}}))
    row = report.result.features[0]
    assert report.scale_agreements[0].rule == "INCOMPATIBLE_DIRECTION"
    assert row.cold.score.value == 50 and row.personal.score.value == 100
    if count == 3:
        assert row.combined.score.value == 50 and row.personal_weight == 0
        assert "INCOMPATIBLE_SCALE_COLD_FALLBACK" in row.stage_reasons
    else:
        assert row.combined.score.value == 100 and row.personal_weight == 1


def test_feature_specific_missing_history_does_not_block_other_stages(evaluation_cases):
    records = [{"days": d, "missing": {"kst_hour": "NOT_COLLECTED"}} for d in (4, 3, 2, 1)]
    records[0]["missing"]["purchase_count_10m"] = "INSUFFICIENT_HISTORY"
    report = evaluate_personalized_features(evaluation_cases.request(profile=evaluation_cases.publication(records)))
    rows = report.result.features
    assert (rows[0].stage, rows[1].stage, rows[7].stage) == ("PERSONALIZED", "TRANSITION", "COLD")
    assert rows[7].personal.score is None and rows[7].combined.score.value == 0
    assert report.result.behavior.status == "SUCCESS"


def test_population_and_initial_cold_preserve_both_provenance_chains(evaluation_cases, personalized):
    supplied = evaluation_cases.configured(personalized.request(personal=False,
        population_profile=personalized.publication(scope="POPULATION")))
    report = evaluate_personalized_features(supplied)
    assert report.result.cold == score_cold_features(supplied.cold)
    assert {f.combined.bases[0].kind for f in report.result.features} == {"POPULATION_PROFILE", "INITIAL_POLICY"}
    assert report.result.profiles[1].context.profile.reference == supplied.population.expected_reference
    assert report.result.features[0].cold.bases[0].profile == supplied.population_baseline.baseline.profile.reference
    assert report.validate_for_input(supplied) == report


def test_aging_reduces_mixture_and_expiry_restores_cold(evaluation_cases):
    profile = evaluation_cases.publication()
    rows = []
    for days in (0, 7, 8, 10, 30):
        supplied = evaluation_cases.request(profile=profile, at=evaluation_cases.cutoff + timedelta(days=days, seconds=2),
            requested=("amount_minor",), cold_parameters={"amount_minor": {"center": 10000.0}})
        rows.append(evaluate_personalized_features(supplied).result.features[0])
    assert rows[0].combined.score.value == 100 and rows[-1].combined.score.value == 0
    assert all(a.personal_weight >= b.personal_weight for a, b in zip(rows, rows[1:]))
    assert rows[-1].stage == "COLD"


@pytest.mark.parametrize("requested", [("amount_minor",), ("attempt_count_1h",), ("kst_day_of_week",), ("kst_hour", "amount_minor")])
def test_requested_subsets_are_complete_and_canonically_ordered(evaluation_cases, requested):
    report = evaluate_personalized_features(evaluation_cases.request(requested=requested))
    assert set(report.result.behavior.requested_features) == set(requested)
    assert len(report.result.features) == len(requested)
    assert report.result.behavior.coverage == "COMPLETE"
    if len(requested) > 1:
        assert report.result.features[0].combined.feature_id == "amount_minor"


def test_json_replay_utc_equivalence_and_learning_state_immutability(evaluation_cases):
    supplied = evaluation_cases.request()
    before = supplied.model_dump_json()
    report = evaluate_personalized_features(supplied)
    restored = PersonalizedEvaluationResult.model_validate_json(report.model_dump_json())
    assert restored.validate_for_input(supplied) == report
    assert evaluate_personalized_features(PersonalizedScoringInput.model_validate_json(before)) == report
    assert supplied.model_dump_json() == before
    same = evaluation_cases.request(at=supplied.cold.request.evaluation.evaluated_at.astimezone(timezone.utc))
    assert evaluate_personalized_features(same) == report


@pytest.mark.parametrize("change", ["input_hash", "policy_hash", "policy_parameter", "compatibility", "stage_reason",
    "preferred_weight", "result_score", "missing_feature", "profile_version", "user", "purchase"])
def test_detached_evaluation_or_request_is_rejected(evaluation_cases, change):
    supplied = evaluation_cases.request()
    report = evaluate_personalized_features(supplied)
    data = report.model_dump()
    if change in {"user", "purchase"}:
        request = supplied.model_dump()
        context = request["cold"]["request"]["evaluation"]["request"]["context"]
        context["user_id" if change == "user" else "purchase_request_id"] = "different"
        with pytest.raises(ValueError):
            report.validate_for_input(PersonalizedScoringInput.model_validate(request))
        return
    if change == "input_hash":
        data["result"]["input_sha256"] = "b" * 64
    elif change == "policy_hash":
        data["components"]["policies_sha256"] = "b" * 64
    elif change == "policy_parameter":
        data["components"]["policies"]["amount"]["spread_multiplier"] = 5.0
    elif change == "compatibility":
        data["scale_agreements"][0]["rule"] = "INCOMPATIBLE_DIRECTION"
    elif change == "stage_reason":
        data["result"]["features"][0]["stage_reasons"] = ["OTHER"]
    elif change == "preferred_weight":
        data["components"]["transition"]["features"][0]["preferred_personal_weight"] = 0.5
    elif change == "result_score":
        data["result"]["features"][0]["combined"]["score"]["value"] = 0.0
    elif change == "missing_feature":
        data["scale_agreements"] = data["scale_agreements"][:-1]
    else:
        data["result"]["profiles"][0]["context"]["profile"]["reference"]["version"] = "other"
    with pytest.raises(ValueError):
        PersonalizedEvaluationResult.model_validate(data).validate_for_input(supplied)


def test_global_config_error_is_not_disguised_as_partial_calculation_failure(evaluation_cases, monkeypatch):
    import copenhagen_fds.personal_evaluation as module

    def unexpected(*args):
        raise AssertionError("Cold must not execute with an unknown score scale")

    monkeypatch.setattr(module, "score_cold_features", unexpected)
    supplied = evaluation_cases.request()
    invalid = supplied.model_copy(update={"config": supplied.config.model_copy(update={"score_scale_version": "unknown"})})
    with pytest.raises(ValueError, match="score scale"):
        evaluate_personalized_features(invalid)
