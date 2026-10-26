"""Paired synthetic-purchase reports, without user text or profile snapshots.

Fraud labels evaluate behavior scores only. Semantic reference answers evaluate
comparison signals separately; neither a mismatch nor a review recommendation
is silently relabeled as fraud. Strata use the personalized arm's amount stage
and the same purchases in both arms, not different arm-specific populations.
"""

from collections import Counter, defaultdict

from copenhagen_fds.contracts.native_evaluation import NativeEvaluationOutcome
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.synthetic.replay import instant
from .metrics import binary_metrics, semantic_confusion
from .protocol import BenchmarkPlan


ARMS = ("cold", "personalized")
DELTA_METRICS = ("precision", "recall", "false_positive_rate", "average_precision", "coverage", "labeled_coverage")
AMOUNT_STAGES = ("COLD", "TRANSITION", "PERSONALIZED", "UNAVAILABLE", "NOT_PRODUCED")


def _identity(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty identity")
    return value


def _index(rows, field, label):
    if not isinstance(rows, list):
        raise ValueError(f"{label} must be a list")
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError(f"{label} entries must be objects")
        identity = _identity(row.get(field), label)
        if identity in result:
            raise ValueError(f"duplicate {label} identity")
        result[identity] = row
    return result


def _deltas(cold, personal):
    return {name: None if cold[name] is None or personal[name] is None else personal[name] - cold[name]
            for name in DELTA_METRICS}


def _behavior(entries, threshold):
    metrics = {arm: binary_metrics([entry[arm] for entry in entries], threshold=threshold) for arm in ARMS}
    paired = [entry for entry in entries if all(
        entry[arm]["status"] == "SUCCESS" and entry[arm]["score"] is not None for arm in ARMS)]
    paired_metrics = {arm: binary_metrics([entry[arm] for entry in paired], threshold=threshold) for arm in ARMS}
    return {**metrics, "deltas": _deltas(metrics["cold"], metrics["personalized"]),
            "delta_direction": "PERSONALIZED_MINUS_COLD",
            "paired_scored": {"n_pairs": len(paired), **paired_metrics,
                              "deltas": _deltas(paired_metrics["cold"], paired_metrics["personalized"])}}


def _amount_stage(summary):
    features = summary.get("features")
    if not isinstance(features, list):
        raise ValueError("arm summary requires a feature list")
    seen = set()
    stage = "NOT_PRODUCED"
    for feature in features:
        if not isinstance(feature, dict):
            raise ValueError("feature summary must be an object")
        name = _identity(feature.get("feature_id"), "feature")
        if name in seen:
            raise ValueError("duplicate feature in arm summary")
        seen.add(name)
        if feature.get("stage") not in AMOUNT_STAGES[:-1]:
            raise ValueError("unsupported feature stage")
        if name == "amount_minor":
            stage = feature["stage"]
    return stage


def _history_bin(profile_audit):
    if not isinstance(profile_audit, dict) or not isinstance(profile_audit.get("personal"), dict):
        raise ValueError("prediction requires a personal profile audit")
    count = profile_audit["personal"].get("included_count")
    if count is None:
        return "UNKNOWN"
    if type(count) is not int or count < 0:
        raise ValueError("included history count must be a nonnegative integer or absent")
    return "0" if count == 0 else "1_4" if count < 5 else "5_29" if count < 30 else "30_PLUS"


def _semantic_actual(summary):
    analysis = summary.get("analysis")
    if not isinstance(analysis, dict) or "comparison" not in analysis:
        raise ValueError("arm summary requires a comparison envelope")
    comparison = analysis["comparison"]
    actual = "NOT_RUN"
    if comparison is not None:
        if (not isinstance(comparison, dict) or not isinstance(comparison.get("status"), str)
                or comparison["status"] not in {"SUCCESS", "FAILED", "NOT_RUN"}):
            raise ValueError("unsupported comparison execution status")
        signal = comparison.get("signal")
        if comparison["status"] == "SUCCESS":
            if not isinstance(signal, str) or signal not in {"MATCH", "MISMATCH", "UNKNOWN"}:
                raise ValueError("successful comparison requires its explicit signal")
            actual = signal
        else:
            if signal is not None:
                raise ValueError("failed or unexecuted comparison cannot supply a successful signal")
            actual = comparison["status"]
    # Legacy summary-only callers may omit extraction. When its envelope is
    # present, a skipped downstream stage must not erase the first failure.
    extraction = analysis.get("extraction")
    if extraction is not None:
        if (not isinstance(extraction, dict) or not isinstance(extraction.get("status"), str)
                or extraction["status"] not in {"SUCCESS", "FAILED", "NOT_RUN"}):
            raise ValueError("unsupported extraction execution status")
        if extraction["status"] != "SUCCESS":
            if actual != "NOT_RUN":
                raise ValueError("comparison execution requires successful extraction")
            return extraction["status"]
    return actual


def _outcomes(entries, arm):
    outcomes = [entry["outcomes"][arm] for entry in entries]
    statuses = Counter({key: 0 for key in ("SUCCESS", "INCOMPLETE", "FAILED", "CANCELLED")})
    risks = Counter({key: 0 for key in ("LOW", "MEDIUM", "HIGH", "UNKNOWN")})
    recommendations, actions = Counter(), Counter()
    for outcome in outcomes:
        statuses[outcome.status] += 1
        risks[outcome.risk_level] += 1
        recommendations[outcome.recommendation or "NOT_PROVIDED"] += 1
        actions.update(outcome.review_actions)
    return dict(n_total=len(outcomes), status_counts=dict(statuses), risk_level_counts=dict(risks),
                recommendation_counts=dict(sorted(recommendations.items())),
                review_action_counts=dict(sorted(actions.items())),
                n_with_review_actions=sum(bool(row.review_actions) for row in outcomes),
                review_counting="EACH_ACTION_PER_PURCHASE; MULTIPLE_ACTIONS_MAY_EXCEED_PURCHASE_COUNT")


def summarize_predictions(rows: list[dict], targets: dict, plan: BenchmarkPlan) -> dict:
    """Join exact evaluator coverage after execution, then report paired metrics.

    ``truth`` and semantic targets must cover exactly the evaluated purchases.
    Personas may cover the full generated population, including unevaluated
    users. Missing/duplicate identities are rejected rather than silently joined.
    Only counts, group names and aggregate metrics leave this function.
    """
    plan = BenchmarkPlan.model_validate(plan.model_dump())
    predictions = _index(rows, "purchase_request_id", "prediction purchase")
    if not isinstance(targets, dict):
        raise ValueError("evaluator targets must be an object")
    truth = _index(targets.get("truth"), "purchase_request_id", "truth purchase")
    personas = _index(targets.get("personas"), "user_id", "persona user")
    semantic = targets.get("semantic")
    if set(predictions) != set(truth) or not isinstance(semantic, dict) or set(predictions) != set(semantic):
        raise ValueError("prediction, truth and semantic purchase coverage must agree exactly")
    selected = targets.get("selected_purchase_ids")
    if selected is not None and (not isinstance(selected, list) or len(set(selected)) != len(selected)
                                 or set(selected) != set(predictions)):
        raise ValueError("selected purchase coverage must agree exactly with predictions")
    evaluation_ids, entries, times, modes = set(), [], [], set()
    for pid, row in predictions.items():
        user = _identity(row.get("user_id"), "prediction user")
        if user not in personas:
            raise ValueError("persona missing for evaluated user")
        evaluation_id = _identity(row.get("evaluation_id"), "evaluation")
        if evaluation_id in evaluation_ids:
            raise ValueError("duplicate evaluation identity")
        evaluation_ids.add(evaluation_id)
        evaluated_at = row.get("evaluated_at")
        if not isinstance(evaluated_at, str):
            raise ValueError("evaluation time requires an explicit timestamp")
        times.append(instant(evaluated_at))
        arms = row.get("arms")
        if not isinstance(arms, dict) or set(arms) != set(ARMS):
            raise ValueError("every prediction requires exactly cold and personalized arms")
        if not isinstance(semantic[pid], dict) or "comparison" not in semantic[pid]:
            raise ValueError("semantic target requires a reference comparison field")
        entry = dict(scenario=_identity(truth[pid].get("scenario"), "scenario"),
                     archetype=_identity(personas[user].get("archetype"), "archetype"),
                     history_samples=_history_bin(row.get("profile_audit")), semantic={}, outcomes={})
        if "is_fraud" not in truth[pid]:
            raise ValueError("truth requires an explicit fraud label")
        for arm in ARMS:
            summary = arms[arm]
            if not isinstance(summary, dict) or not {"behavior_score", "behavior_status"} <= set(summary):
                raise ValueError("arm summary requires independent behavior score and status")
            entry[arm] = dict(label=truth[pid]["is_fraud"], score=summary["behavior_score"],
                              status=summary["behavior_status"])
            stage = _amount_stage(summary)
            if arm == "personalized":
                entry["amount_stage"] = stage
            entry["semantic"][arm] = dict(expected=semantic[pid]["comparison"], actual=_semantic_actual(summary))
            outcome = NativeEvaluationOutcome.model_validate(summary.get("outcome"))
            if len(set(outcome.review_actions)) != len(outcome.review_actions):
                raise ValueError("review actions must be unique within one purchase")
            entry["outcomes"][arm] = outcome
            mode = summary.get("mode", "UNSPECIFIED")
            if mode not in {"MOCK", "GMS", "UNSPECIFIED"}:
                raise ValueError("unsupported recorded execution mode")
            modes.add(mode)
        entries.append(entry)
    threshold = plan.risk_policy.thresholds.medium_from
    behavior = _behavior(entries, threshold)
    strata = {}
    for dimension in ("amount_stage", "history_samples", "archetype", "scenario"):
        groups = defaultdict(list)
        for entry in entries:
            groups[entry[dimension]].append(entry)
        strata[dimension] = {key: dict(n_purchases=len(group), behavior=_behavior(group, threshold))
                             for key, group in sorted(groups.items())}
    return dict(
        schema_version="temporal-benchmark-report-v1",
        scope=dict(origin="SYNTHETIC", suite=plan.suite, data_role=plan.data_role, split=plan.split,
                   n_purchases=len(entries), n_users=len({row["user_id"] for row in rows}),
                   plan_sha256=personalization_hash(plan), threshold=threshold,
                   threshold_tuning=plan.threshold_tuning, primary_unit=plan.primary_unit,
                   first_evaluation_at=None if not times else min(times).isoformat(),
                   last_evaluation_at=None if not times else max(times).isoformat(),
                   observed_modes=sorted(modes)),
        behavior=behavior, strata=strata,
        strata_definitions=dict(amount_stage="PERSONALIZED_ARM_AMOUNT_MINOR_STAGE; SAME_PURCHASES_IN_BOTH_ARMS",
                                history_samples="PERSONAL_PROFILE_INCLUDED_COUNT: 0, 1-4, 5-29, 30+; DESCRIPTIVE_NOT_POLICY",
                                unknown_history="MISSING_COUNT_IS_UNKNOWN_NOT_ZERO"),
        semantic={arm: semantic_confusion([entry["semantic"][arm] for entry in entries]) for arm in ARMS},
        outcomes={arm: _outcomes(entries, arm) for arm in ARMS},
        limitations=[
            "Synthetic generator labels and authored semantic references are not real payment ground truth.",
            "Stream and enriched challenge prevalence differ; their precision must not be pooled or compared as equivalent.",
            "Candidate holdout status does not prove that data were uninspected or that deployment generalization is established.",
            "Scripted Mock answers validate contracts, not measured GMS language-model quality or latency.",
            "Behavior score metrics do not measure semantic mismatch or final review-policy effectiveness.",
            "Per-arm deltas can include different scored subsets; paired_scored restricts both arms to the same eligible purchases.",
            "Stratum sizes are reported without claims of statistical significance or guaranteed personalization improvement.",
            "This report does not establish Core/Pay integration or production fraud-detection performance.",
        ],
    )
