"""Counterfactual policy projection from archived behavior and observed GMS.

This module never rewrites an observed payload, timestamp or trace to make it
fit a historical LIVE request. Stored behavior rules and GMS observations keep
separate provenance. Only the existing pure policy rules are projected across
those archives; no profile, behavior model, provider or payment is executed.
"""

from collections import Counter, defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

from copenhagen_fds.contracts.analysis_results import IntentComparisonResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_classification import classification_fields
from copenhagen_fds.contracts.risk_intent import intent_signal_fields
from copenhagen_fds.contracts.risk_intent_policy import intent_review_fields
from copenhagen_fds.contracts.risk_rules import RiskRuleResult
from copenhagen_fds.evaluation.experiments import ExperimentBatch
from copenhagen_fds.evaluation.offline import deny_network
from copenhagen_fds.native_cli.runtime import fingerprint
from copenhagen_fds.native_cli.storage import check_new_output, write_artifacts
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.synthetic.replay import instant
from copenhagen_fds.synthetic.storage import digest
from .execution import read_predictions
from .gms import _validate_round, read_gms_plans
from .preparation import read_inputs


SCHEMA = "temporal-gms-combination-v1"
INTERPRETATION = "ARCHIVED_COUNTERFACTUAL_POLICY_PROJECTION"
ARMS = ("cold", "personalized")


@dataclass(frozen=True)
class _IntentView:
    """Minimal pure-rule arguments, deliberately not a RiskIntentSignal."""
    source: IntentComparisonResult
    signal: str


@dataclass(frozen=True)
class _ClassificationView:
    """Archived rules plus intent status; not a live RiskPolicyInput."""
    rules: RiskRuleResult
    status: str


def _intent_source(run):
    if run.extraction.status == "SUCCESS":
        return "COMPARISON", run.comparison
    first = run.extraction
    return "EXTRACTION", IntentComparisonResult(status=first.status, metadata=first.metadata,
        reasons=first.reasons, telemetry=first.telemetry, failure=first.failure)


def _rules(summary, row, policy):
    if "behavior_rules" not in summary:
        raise ValueError("archived prediction must retain behavior_rules; summary scores alone are insufficient")
    if summary["behavior_rules"] is None:
        if summary["behavior_score"] is not None:
            raise ValueError("a saved behavior score requires its retained policy evidence")
        return None
    rules = RiskRuleResult.model_validate(summary["behavior_rules"])
    aggregation = rules.aggregation
    coverage = aggregation.assessment.coverage
    reference = coverage.input_reference
    if (coverage.policy != policy or reference.target.purchase_request_id != row["purchase_request_id"]
            or reference.target.user_id != row["user_id"] or reference.target.evaluation_id != row["evaluation_id"]
            or reference.evaluated_at != instant(row["evaluated_at"])):
        raise ValueError("archived behavior rules differ from the purchase, policy or historical time")
    score = None if aggregation.behavior_score is None else aggregation.behavior_score.value
    if (summary["behavior_score"] is not None and type(summary["behavior_score"]) not in (int, float)
            or score != summary["behavior_score"]):
        raise ValueError("archived summary score differs from its validated aggregation")
    features = {item["feature_id"]: item for item in summary["features"]}
    if len(features) != len(summary["features"]) or set(features) != {item.feature.feature_id for item in coverage.features}:
        raise ValueError("archived feature summary differs from retained behavior evidence")
    for item in coverage.features:
        observed = features[item.feature.feature_id]
        expected_score = None if item.feature.score is None else item.feature.score.value
        if (observed["score"] is not None and type(observed["score"]) not in (int, float)
                or observed["score"] != expected_score or observed["status"] != item.feature.status):
            raise ValueError("archived feature score or state changed")
    return rules


def _project(rules, intent, *, original):
    """Reuse pure intent and classification rules without fabricating live input.

    The final advice ordering follows assemble_risk_decision. Its full native
    constructor is intentionally not called: these two archives have different
    observation times and cannot claim one live input_reference.
    """
    if rules is None or intent is None:
        return dict(status="NOT_RUN", risk_level="UNKNOWN", recommendation=None,
                    review_actions=["RETRY_EVALUATION"], behavior_score=original["behavior_score"],
                    reason="MISSING_ARCHIVED_BEHAVIOR_RULES" if rules is None else "MISSING_GMS_OBSERVATION")
    signal = intent_signal_fields(intent)["signal"]
    coverage = rules.aggregation.assessment.coverage
    review = intent_review_fields(_IntentView(intent, signal), required=coverage.policy.intent_required)
    classification = classification_fields(_ClassificationView(rules, review["status"]))
    actions = set(review["review_actions"])
    assessment = rules.aggregation.assessment
    if assessment.status in {"FAILED", "NOT_RUN"}:
        actions.add("RETRY_EVALUATION")
    if any(item.feature.score is None for item in coverage.features):
        actions.add("CHECK_INPUT")
    recommendation = None
    if classification["status"] == "SUCCESS":
        risk = classification["risk_level"]
        recommendation = ("REQUIRE_AUTH" if risk == "HIGH" else
                          "REVIEW_ORDER" if risk == "MEDIUM" or signal == "MISMATCH" else
                          "VERIFY_USER_INTENT" if signal in {"UNKNOWN", "NOT_RUN"} else "NO_ADDITIONAL_REVIEW")
        if recommendation != "NO_ADDITIONAL_REVIEW":
            actions.add(recommendation)
    score = rules.aggregation.behavior_score
    return dict(**classification, recommendation=recommendation,
                review_actions=[action for action in (
                    "CHECK_INPUT", "RETRY_EVALUATION", "REVIEW_ORDER", "VERIFY_USER_INTENT", "REQUIRE_AUTH") if action in actions],
                behavior_score=None if score is None else score.value, minimum_rule_risk=rules.minimum_risk,
                decisive_rule_ids=list(rules.decisive_rule_ids), behavior_rules_sha256=personalization_hash(rules),
                intent_signal=str(signal))


def _variation(rows):
    by_purchase = defaultdict(list)
    for row in rows:
        by_purchase[row["purchase_request_id"]].append(row)
    comparable = [values for values in by_purchase.values() if sum(row["observation_status"] == "OBSERVED" for row in values) >= 2]
    def changes(getter):
        return sum(len({personalization_hash(getter(row)) for row in values if row["observation_status"] == "OBSERVED"}) > 1
                   for values in comparable)
    return dict(n_purchases=len(by_purchase), n_with_two_observed_rounds=len(comparable),
                n_with_missing_rounds=sum(any(row["observation_status"] == "MISSING" for row in values) for values in by_purchase.values()),
                semantic_signal_changed=changes(lambda row: row["gms"]["signal"]),
                extracted_payload_changed=changes(lambda row: row["gms"]["extraction_payload_sha256"]),
                response_model_changed=changes(lambda row: row["gms"]["response_models"]),
                combined_outcome_changed={arm: changes(lambda row: {
                    name: row["arms"][arm]["projected"][name]
                    for name in ("status", "risk_level", "recommendation", "review_actions")}) for arm in ARMS})


def combine_archived_gms(source: Path, behavior_run: Path, gms_plan_dir: Path,
                         batches: Sequence[ExperimentBatch], output: Path) -> dict:
    """Join <=12 purchases × <=2 archived rounds without a fresh GMS call.

    MOCK batches are supported only as explicitly labeled wiring tests. Sources
    may not be mixed. Missing rounds/trials remain unexecuted, not MATCH or zero.
    Hashes bind the saved artifacts; they do not independently attest who made
    an imported API call. The source observations and provider payloads are not
    substituted, time-shifted, or modified.
    """
    source, behavior_run, gms_plan_dir, output = map(Path, (source, behavior_run, gms_plan_dir, output))
    check_new_output(output, source, behavior_run, gms_plan_dir)
    with deny_network():
        benchmark, _, semantic, selected = read_inputs(source)
        run_manifest, predictions = read_predictions(source, behavior_run)
        if len({row["purchase_request_id"] for row in predictions}) != len(predictions):
            raise ValueError("duplicate archived purchase prediction")
        indexed = {row["purchase_request_id"]: row for row in predictions}
        plans = read_gms_plans(gms_plan_dir)
        gms_manifest = read_json(gms_plan_dir / "manifest.json")
        if (gms_manifest["source_manifest_sha256"] != digest(source / "manifest.json")
                or gms_manifest["source_plan_sha256"] != personalization_hash(benchmark)):
            raise ValueError("GMS plan references a different temporal source")
        if len(batches) > len(plans):
            raise ValueError("too many archived GMS rounds")
        saved = {}
        for supplied in batches:
            batch = ExperimentBatch.model_validate(supplied.model_dump())
            if batch.plan_sha256 in saved:
                raise ValueError("duplicate archived GMS round")
            saved[batch.plan_sha256] = batch
        if not set(saved) <= {plan.sha256 for plan in plans}:
            raise ValueError("archived GMS batch references another plan")
        sources = {batch.source for batch in saved.values()}
        if len(sources) > 1:
            raise ValueError("Mock and GMS observations must not be mixed")
        rows = []
        for round_number, plan in enumerate(plans, 1):
            batch = saved.get(plan.sha256)
            cases = {case.case_id: case for case in plan.dataset.cases}
            observed = {} if batch is None else _validate_round(plan, batch)[3]
            for case_id, repetition in plan.run_ids:
                case = cases[case_id]
                pid = case.request.context.purchase_request_id
                if pid not in selected or pid not in indexed or case.request != semantic[pid].analysis:
                    raise ValueError("GMS request differs from the archived purchase semantic input")
                prediction = indexed[pid]
                if (prediction["semantic_observation_sha256"] != semantic[pid].observation_sha256
                        or prediction["evaluation_id"] != case.request.context.evaluation_id
                        or prediction["user_id"] != case.request.context.user_id):
                    raise ValueError("GMS and behavior evidence refer to different evaluation identities")
                if set(prediction["arms"]) != set(ARMS):
                    raise ValueError("archived prediction requires both behavior arms")
                observation = observed.get((case_id, repetition))
                run = None if observation is None else observation.run
                source_stage, intent = (None, None) if run is None else _intent_source(run)
                gms = None if run is None else dict(
                    observation_sha256=personalization_hash(observation), run_sha256=personalization_hash(run),
                    source_stage=source_stage, signal=str(intent_signal_fields(intent)["signal"]),
                    extraction_payload_sha256=None if run.extraction.payload is None else personalization_hash(run.extraction.payload),
                    comparison_payload_sha256=None if run.comparison.payload is None else personalization_hash(run.comparison.payload),
                    response_models={stage: getattr(run, stage).metadata.model_version for stage in ("extraction", "comparison")},
                    observed_at={stage: None if getattr(run, stage).metadata.evaluated_at is None else
                        getattr(run, stage).metadata.evaluated_at.isoformat() for stage in ("extraction", "comparison")})
                arms = {}
                for arm in ARMS:
                    summary = prediction["arms"][arm]
                    rules = _rules(summary, prediction, benchmark.risk_policy)
                    if rules is not None and rules.aggregation.assessment.coverage.input_reference.target != case.request.target:
                        raise ValueError("archived behavior and GMS must preserve the entire order and intent evaluation target")
                    arms[arm] = dict(original_mock_outcome=summary["outcome"],
                                     original_behavior_score=summary["behavior_score"],
                                     projected=_project(rules, intent, original=summary))
                rows.append(dict(round=round_number, plan_sha256=plan.sha256, case_id=case_id,
                                 purchase_request_id=pid, historical_behavior_at=prediction["evaluated_at"],
                                 observation_status="MISSING" if run is None else "OBSERVED",
                                 observation_source=None if batch is None else batch.source, gms=gms, arms=arms))
        provenance = dict(source_manifest_sha256=digest(source / "manifest.json"),
                          source_plan_sha256=personalization_hash(benchmark),
                          behavior_run_manifest_sha256=digest(behavior_run / "manifest.json"),
                          predictions_sha256=run_manifest["predictions_sha256"],
                          gms_plan_manifest_sha256=digest(gms_plan_dir / "manifest.json"),
                          batch_sha256={key: personalization_hash(value) for key, value in saved.items()})
        observed_count = sum(row["observation_status"] == "OBSERVED" for row in rows)
        summary = dict(schema_version=SCHEMA, status="PROJECTED", interpretation=INTERPRETATION,
                       planned_trials=len(rows), observed_trials=observed_count, missing_trials=len(rows) - observed_count,
                       pipeline_rounds=len(plans), sampled_purchases=len(plans[0].case_ids),
                       observation_sources=sorted(sources), actual_gms_observations=observed_count > 0 and sources == {"GMS_IMPORTED"},
                       fresh_api_calls=0, behavior_recomputed=False, live_end_to_end=False,
                       provenance=provenance, variation=_variation(rows),
                       projected_status_counts={arm: dict(Counter(row["arms"][arm]["projected"]["status"] for row in rows)) for arm in ARMS},
                       projected_recommendation_counts={arm: dict(Counter(row["arms"][arm]["projected"]["recommendation"] or "NOT_PROVIDED" for row in rows)) for arm in ARMS},
                       limitations=[
                           "Archived GMS and historical behavior have different observation times; this is not a live end-to-end evaluation.",
                           "Original GMS payloads, targets and timestamps are preserved; no clock or trace is rewritten.",
                           "The source profile is not rebuilt and behavior features are not rescored.",
                           "Mock batches demonstrate wiring only; GMS_IMPORTED records retain imported provenance without independent attestation.",
                           "Projected review advice is not a payment authorization, production decision, or calibrated fraud probability.",
                       ])
        artifacts = {"summary.json": summary, "rows.json": rows}
        for number, plan in enumerate(plans, 1):
            if plan.sha256 in saved:
                artifacts[f"round-{number}.batch.json"] = saved[plan.sha256].model_dump(mode="json")
        output.mkdir(parents=True, exist_ok=False)
        write_artifacts(output, artifacts, dict(schema_version=SCHEMA, interpretation=INTERPRETATION,
                        provenance=provenance, code=fingerprint(), fresh_api_calls=0,
                        summary_sha256=personalization_hash(summary)))
        return summary


def verify_combination(output: Path) -> dict:
    """Audit the recorded projection; no archived or live execution is repeated."""
    output = Path(output)
    manifest = read_json(output / "manifest.json")
    if (manifest.get("schema_version") != SCHEMA or manifest.get("interpretation") != INTERPRETATION
            or manifest.get("fresh_api_calls") != 0):
        raise ValueError("unsupported archived combination manifest")
    inventory = manifest["artifact_sha256"]
    allowed = {"summary.json", "rows.json", "round-1.batch.json", "round-2.batch.json"}
    if not {"summary.json", "rows.json"} <= set(inventory) <= allowed:
        raise ValueError("unexpected archived combination artifact inventory")
    if {path.name for path in output.iterdir()} != {*inventory, "manifest.json"}:
        raise ValueError("archived combination inventory changed")
    for name, expected in inventory.items():
        path = output / name
        if path.is_symlink() or digest(path) != expected:
            raise ValueError("archived combination artifact changed")
    summary, rows = read_json(output / "summary.json"), read_json(output / "rows.json")
    if (personalization_hash(summary) != manifest["summary_sha256"] or summary["provenance"] != manifest["provenance"]
            or summary["planned_trials"] != len(rows) or summary["variation"] != _variation(rows)):
        raise ValueError("archived combination summary changed")
    observed = sum(row["observation_status"] == "OBSERVED" for row in rows)
    if (summary["observed_trials"] != observed or summary["missing_trials"] != len(rows) - observed
            or any(row["observation_status"] not in {"OBSERVED", "MISSING"} for row in rows)
            or summary["fresh_api_calls"] != 0 or summary["live_end_to_end"] is not False
            or summary["behavior_recomputed"] is not False
            or summary["actual_gms_observations"] != (observed > 0 and summary["observation_sources"] == ["GMS_IMPORTED"])):
        raise ValueError("archived observation counts or execution scope changed")
    for arm in ARMS:
        if (summary["projected_status_counts"][arm] != dict(Counter(row["arms"][arm]["projected"]["status"] for row in rows))
                or summary["projected_recommendation_counts"][arm] != dict(Counter(
                    row["arms"][arm]["projected"]["recommendation"] or "NOT_PROVIDED" for row in rows))):
            raise ValueError("archived projected outcome counts changed")
    for name in set(inventory) - {"summary.json", "rows.json"}:
        batch = ExperimentBatch.model_validate_json((output / name).read_text(encoding="utf-8"))
        if summary["provenance"]["batch_sha256"].get(batch.plan_sha256) != personalization_hash(batch):
            raise ValueError("archived GMS payload hash changed")
    return dict(status="VERIFIED", verification="RECORDED_ARTIFACTS_ONLY", fresh_api_calls=0,
                live_end_to_end=False, behavior_recomputed=False, provenance=summary["provenance"],
                manifest_sha256=digest(output / "manifest.json"))
