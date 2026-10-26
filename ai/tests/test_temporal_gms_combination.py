"""Archived policy projections, without paid calls (S15P21A207-360)."""

import asyncio
from copy import deepcopy
import json
from pathlib import Path

import pytest

from copenhagen_fds.analysis.intent_analyzer import IntentAnalysisRun
from copenhagen_fds.contracts.personal_replay import PersonalReplaySettings
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.native_cli.runtime import run_request
from copenhagen_fds.native_cli.scenarios import demo_request, demo_script, demo_settings, observable_demo
from copenhagen_fds.temporal_benchmark import gms_combination as combination


SETTINGS = Path(__file__).resolve().parents[1] / "examples/personal-replay-settings.json"


@pytest.fixture(scope="module")
def native_cases():
    settings = demo_settings(PersonalReplaySettings.model_validate_json(SETTINGS.read_text(encoding="utf-8")))
    observations = observable_demo()
    async def execute():
        cases = {}
        for regime in ("low", "high"):
            request = demo_request("archived-" + regime, observations, settings,
                                   amount=10000 if regime == "low" else 1000000, burst=regime == "high")
            for signal in ("MATCH", "MISMATCH", "UNKNOWN", "FAILED", "EXTRACTION_FAILED"):
                script = (demo_script(request, failure="extraction") if signal == "EXTRACTION_FAILED" else
                          demo_script(request, failure="comparison") if signal == "FAILED" else demo_script(request, signal=signal))
                result, summary = await run_request(request, script=script)
                summary["behavior_rules"] = result.policy_result.classification.evidence.rules.model_dump(mode="json")
                row = dict(purchase_request_id=request.analysis.context.purchase_request_id,
                           user_id=request.analysis.context.user_id, evaluation_id=request.analysis.context.evaluation_id,
                           evaluated_at=request.feature_input.evaluation.evaluated_at.isoformat())
                run = IntentAnalysisRun(extraction=result.execution.extraction, comparison=result.execution.comparison)
                cases[(regime, signal)] = (request, result, summary, row, run)
        return cases
    # run_request installs its own network guard; this fixture never opens an adapter.
    return asyncio.run(execute())


@pytest.mark.parametrize("regime", ["low", "high"])
@pytest.mark.parametrize("signal", ["MATCH", "MISMATCH", "UNKNOWN", "FAILED"])
def test_archived_projection_matches_real_native_policy_without_rewriting_observed_evidence(native_cases, regime, signal):
    request, result, summary, row, run = native_cases[(regime, signal)]
    before = run.model_dump_json()
    rules = combination._rules(summary, row, request.risk_policy)
    source_stage, intent = combination._intent_source(run)
    projected = combination._project(rules, intent, original=summary)
    assert {key: projected[key] for key in ("status", "risk_level", "recommendation", "review_actions")} == result.outcome.model_dump(mode="json")
    assert projected["behavior_score"] == summary["behavior_score"]
    assert projected["behavior_rules_sha256"] == personalization_hash(rules)
    assert projected["intent_signal"] == signal
    assert source_stage == "COMPARISON"
    assert run.model_dump_json() == before


@pytest.mark.parametrize("change", ["score", "feature", "status", "user", "purchase", "evaluation", "time", "policy"])
def test_archived_rules_require_matching_scores_and_exact_historical_identity(native_cases, change):
    request, _, original, original_row, _ = native_cases[("low", "MATCH")]
    summary, row, policy = deepcopy(original), deepcopy(original_row), request.risk_policy
    if change == "score":
        summary["behavior_score"] += 1
    elif change == "feature":
        summary["features"][0]["score"] += 1
    elif change == "status":
        summary["features"][0]["status"] = "FAILED"
    elif change in {"user", "purchase", "evaluation"}:
        row[{"user": "user_id", "purchase": "purchase_request_id", "evaluation": "evaluation_id"}[change]] = "other"
    elif change == "time":
        row["evaluated_at"] = "2026-02-01T00:00:00+09:00"
    else:
        policy = policy.model_copy(update={"version": "different-version"})
    with pytest.raises(ValueError):
        combination._rules(summary, row, policy)


def test_scores_without_retained_policy_evidence_cannot_be_promoted_to_full_policy_result(native_cases):
    request, _, original, row, _ = native_cases[("low", "MATCH")]
    summary = deepcopy(original)
    del summary["behavior_rules"]
    with pytest.raises(ValueError, match="summary scores alone are insufficient"):
        combination._rules(summary, row, request.risk_policy)


def test_missing_gms_observation_is_unexecuted_not_match_or_zero(native_cases):
    request, _, summary, row, _ = native_cases[("high", "MATCH")]
    rules = combination._rules(summary, row, request.risk_policy)
    projected = combination._project(rules, None, original=summary)
    assert projected["status"] == "NOT_RUN"
    assert projected["risk_level"] == "UNKNOWN"
    assert projected["recommendation"] is None
    assert projected["behavior_score"] == summary["behavior_score"]
    assert projected["reason"] == "MISSING_GMS_OBSERVATION"


def test_extraction_failure_remains_the_first_failure_instead_of_becoming_skipped_comparison(native_cases):
    request, result, summary, row, run = native_cases[("low", "EXTRACTION_FAILED")]
    assert run.comparison.status == "NOT_RUN"
    stage, intent = combination._intent_source(run)
    projected = combination._project(combination._rules(summary, row, request.risk_policy), intent, original=summary)
    assert stage == "EXTRACTION"
    assert intent.failure == run.extraction.failure
    assert intent.metadata == run.extraction.metadata
    assert projected["status"] == "FAILED"
    assert projected["intent_signal"] == "FAILED"
    assert projected["review_actions"] == result.outcome.model_dump(mode="json")["review_actions"]


def test_model_signal_variation_and_combined_advice_variation_have_independent_counts():
    def row(pid, round_number, signal, *, model="one", advice="REQUIRE_AUTH", observed=True):
        projection = dict(status="SUCCESS", risk_level="HIGH", recommendation=advice, review_actions=[advice])
        return dict(purchase_request_id=pid, round=round_number,
                    observation_status="OBSERVED" if observed else "MISSING",
                    gms=dict(signal=signal, extraction_payload_sha256="same", response_models=dict(comparison=model)) if observed else None,
                    arms={arm: dict(projected=projection) for arm in combination.ARMS})
    rows = [row("a", 1, "MATCH"), row("a", 2, "MISMATCH"),
            row("b", 1, "MATCH"), row("b", 2, "MATCH", model="two", advice="REVIEW_ORDER"),
            row("c", 1, "MATCH"), row("c", 2, None, observed=False)]
    report = combination._variation(rows)
    assert report["n_with_two_observed_rounds"] == 2
    assert report["semantic_signal_changed"] == 1
    assert report["response_model_changed"] == 1
    assert report["extracted_payload_changed"] == 0
    assert report["combined_outcome_changed"] == dict(cold=1, personalized=1)
    assert report["n_with_missing_rounds"] == 1


@pytest.fixture
def archive_fixture(tmp_path, monkeypatch, native_cases):
    """Small loader stand-ins keep the orchestration test independent of replay cost.

    Production loaders have their own artifact/hash tests; this fixture checks
    that their returned source identity must also match the GMS plan lineage.
    """
    from types import SimpleNamespace
    from copenhagen_fds.temporal_benchmark.protocol import BenchmarkPlan
    from copenhagen_fds.synthetic.storage import digest
    request, _, summary, row, _ = native_cases[("low", "MATCH")]
    benchmark = BenchmarkPlan(settings=PersonalReplaySettings.model_validate_json(SETTINGS.read_text(encoding="utf-8")))
    pid = row["purchase_request_id"]
    source, behavior, plans = (tmp_path / name for name in ("source", "behavior", "plans"))
    for path in (source, behavior, plans):
        path.mkdir()
    (source / "manifest.json").write_text("{}", encoding="utf-8")
    (behavior / "manifest.json").write_text("{}", encoding="utf-8")
    metadata = dict(source_manifest_sha256=digest(source / "manifest.json"),
                    source_plan_sha256=personalization_hash(benchmark))
    (plans / "manifest.json").write_text(json.dumps(metadata), encoding="utf-8")
    semantic = SimpleNamespace(analysis=request.analysis, observation_sha256="a" * 64)
    prediction = {**row, "semantic_observation_sha256": "a" * 64,
                  "arms": {arm: deepcopy(summary) for arm in combination.ARMS}}
    plan = SimpleNamespace(sha256="b" * 64, case_ids=("case",), run_ids=(("case", 1),),
                           dataset=SimpleNamespace(cases=(SimpleNamespace(case_id="case", request=request.analysis),)))
    monkeypatch.setattr(combination, "read_inputs", lambda path: (benchmark, {}, {pid: semantic}, (pid,)))
    monkeypatch.setattr(combination, "read_predictions", lambda first, second: ({"predictions_sha256": "c" * 64}, [prediction]))
    monkeypatch.setattr(combination, "read_gms_plans", lambda path: (plan,))
    return source, behavior, plans, prediction


def test_archived_combination_records_missing_rounds_without_any_api_or_native_execution(archive_fixture, tmp_path):
    source, behavior, plans, _ = archive_fixture
    output = tmp_path / "combined"
    report = combination.combine_archived_gms(source, behavior, plans, [], output)
    assert report["status"] == "PROJECTED"
    assert report["planned_trials"] == report["missing_trials"] == 1
    assert report["observed_trials"] == 0
    assert report["fresh_api_calls"] == 0
    assert report["live_end_to_end"] is report["behavior_recomputed"] is False
    assert report["actual_gms_observations"] is False
    assert report["projected_status_counts"] == dict(cold={"NOT_RUN": 1}, personalized={"NOT_RUN": 1})
    verified = combination.verify_combination(output)
    assert verified["status"] == "VERIFIED"
    assert verified["provenance"] == report["provenance"]
    with pytest.raises(ValueError, match="new directory"):
        combination.combine_archived_gms(source, behavior, plans, [], output)


@pytest.mark.parametrize("change", ["source", "semantic", "identity"])
def test_archived_orchestration_rejects_cross_source_and_cross_purchase_joins(archive_fixture, tmp_path, change):
    source, behavior, plans, prediction = archive_fixture
    if change == "source":
        (source / "manifest.json").write_text("changed", encoding="utf-8")
    elif change == "semantic":
        prediction["semantic_observation_sha256"] = "0" * 64
    else:
        prediction["evaluation_id"] = "wrong-evaluation"
    with pytest.raises(ValueError):
        combination.combine_archived_gms(source, behavior, plans, [], tmp_path / "invalid")
    assert not (tmp_path / "invalid").exists()


@pytest.mark.parametrize("field", ["order_version", "order_hash", "intent_version"])
def test_same_purchase_with_changed_order_or_intent_target_cannot_reuse_archived_behavior(archive_fixture, tmp_path, field):
    source, behavior, plans, prediction = archive_fixture
    target = prediction["arms"]["cold"]["behavior_rules"]["aggregation"]["assessment"]["coverage"]["input_reference"]["target"]
    if field == "order_version":
        target["order"]["version"] = "changed-order-version"
    elif field == "order_hash":
        target["order"]["snapshot_hash"] = "0" * 64
    else:
        target["intent_version"] = "changed-intent-version"
    with pytest.raises(ValueError, match="entire order and intent evaluation target"):
        combination.combine_archived_gms(source, behavior, plans, [], tmp_path / "invalid-target")
    assert not (tmp_path / "invalid-target").exists()


def test_combination_artifact_changes_are_rejected(archive_fixture, tmp_path):
    source, behavior, plans, _ = archive_fixture
    output = tmp_path / "combined"
    combination.combine_archived_gms(source, behavior, plans, [], output)
    with (output / "rows.json").open("a", encoding="utf-8") as handle:
        handle.write(" ")
    with pytest.raises(ValueError, match="artifact changed"):
        combination.verify_combination(output)


def test_observed_archived_payloads_and_times_are_preserved_with_round_variation_separate(archive_fixture, native_cases, tmp_path, monkeypatch):
    from datetime import datetime, timezone
    from types import SimpleNamespace
    from copenhagen_fds.evaluation.experiments import ExperimentBatch, ExperimentObservation
    source, behavior, plans_dir, _ = archive_fixture
    request = native_cases[("low", "MATCH")][0]
    plans, batches = [], []
    for number, (signal, plan_hash) in enumerate((("MATCH", "b" * 64), ("MISMATCH", "d" * 64)), 1):
        plan = SimpleNamespace(sha256=plan_hash, case_ids=("case",), run_ids=(("case", 1),),
                               dataset=SimpleNamespace(cases=(SimpleNamespace(case_id="case", request=request.analysis),)))
        run = native_cases[("low", signal)][4]
        observation = ExperimentObservation(case_id="case", repetition=1, run_id="case.r1", run=run)
        at = datetime(2026, 1, 7, tzinfo=timezone.utc)
        batch = ExperimentBatch(plan_sha256=plan_hash, source="MOCK", status="COMPLETE",
                                started_at=at, completed_at=at, observations=[observation])
        plans.append(plan)
        batches.append(batch)
    originals = [batch.model_dump(mode="json") for batch in batches]
    monkeypatch.setattr(combination, "read_gms_plans", lambda path: tuple(plans))
    checked = []
    def validated(plan, batch):
        assert batch.plan_sha256 == plan.sha256
        checked.append(plan.sha256)
        return plan, batch, {}, {("case", 1): batch.observations[0]}, {}
    monkeypatch.setattr(combination, "_validate_round", validated)
    output = tmp_path / "observed-combination"
    report = combination.combine_archived_gms(source, behavior, plans_dir, list(reversed(batches)), output)
    assert report["observed_trials"] == 2 and report["missing_trials"] == 0
    assert report["observation_sources"] == ["MOCK"] and report["actual_gms_observations"] is False
    assert report["variation"]["semantic_signal_changed"] == 1
    assert report["variation"]["combined_outcome_changed"] == dict(cold=1, personalized=1)
    assert report["variation"]["response_model_changed"] == 0
    assert checked == [plan.sha256 for plan in plans]
    for number, expected in enumerate(originals, 1):
        assert json.loads((output / f"round-{number}.batch.json").read_text(encoding="utf-8")) == expected
    assert [batch.model_dump(mode="json") for batch in batches] == originals
    assert combination.verify_combination(output)["status"] == "VERIFIED"
