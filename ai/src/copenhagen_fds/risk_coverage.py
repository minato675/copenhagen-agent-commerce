"""Expand observed results to the full configured scope without computing scores."""

from copenhagen_fds.contracts.behavior import FeatureScoreResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_coverage import RiskCoverageResult, RiskFeatureAvailability, coverage_fields
from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput


def evaluate_risk_coverage(supplied: RiskPolicyInput) -> RiskCoverageResult:
    """Pure evaluation of the pinned request; no scorers, GPT calls or state updates."""
    supplied = RiskPolicyInput.model_validate(supplied.model_dump())
    requested = set(supplied.scoring_input.cold.request.evaluation.requested_features)
    raw = {row.feature_id: row for row in supplied.scoring_input.cold.raw_features.features}
    results = {row.feature_id: row for row in supplied.behavior.result.behavior.features} if supplied.behavior else {}
    features = []
    for group in supplied.policy.groups:
        for setting in group.features:
            name = setting.feature_id
            source = "NOT_REQUESTED" if name not in requested else (
                "BEHAVIOR_NOT_RUN" if supplied.behavior is None else "RESULT")
            result = results.get(name)
            if source == "RESULT" and result is None:
                raise ValueError("evaluated result is missing a requested feature")
            if result is None:
                result = FeatureScoreResult(feature_id=name, status="NOT_RUN", reasons=["NOT_EXECUTED"])
            features.append(RiskFeatureAvailability(group_id=group.group_id, required=setting.required,
                requested=name in requested, source=source, raw=raw.get(name), feature=result))
    features = tuple(features)
    return RiskCoverageResult(input_reference=RiskInputReference.from_input(supplied), policy=supplied.policy,
        policy_sha256=personalization_hash(supplied.policy), features=features, **coverage_fields(features))
