"""Read-only projection of a bound native record into the existing provider DTO."""

from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.provider_evaluation import (
    EvaluationVersions, FeatureStageSummary, ProviderEvaluationRequest, ProviderEvaluationResponse,
)
from copenhagen_fds.contracts.risk_results import PolicyReason


def project_evaluation_response(
    request: ProviderEvaluationRequest, record: NativeEvaluationResult,
) -> ProviderEvaluationResponse:
    """Validate provenance and copy recorded evidence without rerunning any evaluator.

    This trusted local utility is not an endpoint accepting precomputed results.
    Missing policy evidence stays unavailable; no expiry, reuse or storage is issued.
    """
    request = ProviderEvaluationRequest.model_validate(request.model_dump())
    record = record.validate_for_request(request.evaluation)
    execution, engine = record.execution, request.evaluation
    features = []
    if execution.behavior is not None:
        behavior = execution.behavior.evaluation
        for row, readiness in zip(behavior.result.features, behavior.components.transition.readiness.features, strict=True):
            features.append(FeatureStageSummary(feature_id=row.combined.feature_id,
                status=row.combined.status, score=row.combined.score, stage=row.stage,
                cold_weight=row.cold_weight, personal_weight=row.personal_weight, reasons=row.stage_reasons,
                readiness=[window.model_dump(exclude={"evidence"}) for window in readiness.windows]))
    decision = record.policy_result.decision if record.policy_result is not None else None
    reasons = (decision.reasons if decision is not None else
               (PolicyReason(code="EVALUATION_FAILED" if record.outcome.status in {"FAILED", "CANCELLED"}
                             else "NOT_EXECUTED"),))
    versions = EvaluationVersions(
        feature_schema=engine.feature_input.evaluation.feature_schema_version,
        scoring=engine.personalization.score_scale_version, personalization=engine.personalization.version,
        risk_policy=engine.risk_policy.version, requested_model=engine.execution.requested_model,
        extraction_prompt=engine.execution.extraction_prompt_version,
        comparison_prompt=engine.execution.comparison_prompt_version,
        extraction_model=execution.extraction.metadata.model_version if execution.extraction is not None else None,
        comparison_model=execution.comparison.metadata.model_version if execution.comparison is not None else None,
        profiles=[p.state.published.profile.reference for p in (engine.personal, engine.population) if p is not None],
    )
    return ProviderEvaluationResponse(operation="EVALUATE", call=request.call,
        request_sha256=personalization_hash(request), engine_reference=execution.reference,
        record_sha256=personalization_hash(record), completed_at=record.completed_at, outcome=record.outcome,
        behavior_score=decision.behavior_score if decision is not None else None,
        score_coverage=decision.score_coverage if decision is not None else "UNAVAILABLE",
        groups=decision.groups if decision is not None else (), features=features, reasons=reasons,
        stages=(*execution.stages, record.policy_stage), versions=versions).validate_for_request(request)
