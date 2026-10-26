"""Compare supplied snapshots only; never query current service state or rerun an evaluator."""

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_reuse_connection import EngineResultConnection, connection_status
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash


def _prepare_connection(record, current):
    values, reasons = [], []
    for model, supplied, code in ((NativeEvaluationResult, record, "INVALID_RESULT"),
                                 (EngineEvaluationRequest, current, "INVALID_CURRENT_REQUEST")):
        try:
            values.append(model.model_validate(supplied.model_dump() if isinstance(supplied, model) else supplied))
        except (ValueError, TypeError):
            values.append(None)
            reasons.append(code)
    return *values, reasons


def _compare_connection(record, current, reasons):
    reasons = list(reasons)
    if record is not None and current is not None:
        old = record.execution.request
        old_input, new_input = old.feature_input.evaluation.request, current.feature_input.evaluation.request
        a, b = old_input.context, new_input.context
        for name, code in (("user_id", "USER_MISMATCH"), ("purchase_request_id", "PURCHASE_MISMATCH"),
                           ("evaluation_id", "EVALUATION_ID_CHANGED")):
            if getattr(a, name) != getattr(b, name):
                reasons.append(code)
        if old_input.order != new_input.order:
            reasons.append("ORDER_CONTENT_CONFLICT" if old_input.order.reference == new_input.order.reference else "ORDER_CHANGED")
        if (old_input.intent, old.analysis.user_request) != (new_input.intent, current.analysis.user_request):
            same_version = old_input.intent.version is not None and old_input.intent.version == new_input.intent.version
            reasons.append("INTENT_CONTENT_CONFLICT" if same_version else "INTENT_CHANGED")
        comparisons = (
            (old.trace_id, current.trace_id, "TRACE_CHANGED"),
            (a, b, "CONTEXT_CHANGED"),
            (old.feature_input.evaluation.evaluated_at, current.feature_input.evaluation.evaluated_at, "BEHAVIOR_TIME_CHANGED"),
            (old.feature_input, current.feature_input, "FEATURE_INPUT_CHANGED"),
            (old.personal, current.personal, "PERSONAL_PROFILE_CHANGED"),
            (old.population, current.population, "POPULATION_PROFILE_CHANGED"),
            (old.population_baseline_request, current.population_baseline_request, "POPULATION_BASELINE_CHANGED"),
            (old.initial_policy, current.initial_policy, "COLD_POLICY_CHANGED"),
            (old.personalization, current.personalization, "PERSONALIZATION_CHANGED"),
            (old.scoring, current.scoring, "SCORING_CHANGED"),
            (old.risk_policy, current.risk_policy, "RISK_POLICY_CHANGED"),
            (old.execution.requested_model, current.execution.requested_model, "MODEL_CHANGED"),
            ((old.execution.extraction_prompt_version, old.execution.comparison_prompt_version),
             (current.execution.extraction_prompt_version, current.execution.comparison_prompt_version), "PROMPT_CHANGED"),
            (old.execution, current.execution, "EXECUTION_CONFIG_CHANGED"),
        )
        reasons.extend(code for before, after, code in comparisons if before != after)
    original_hash = record.execution.reference.request_sha256 if record is not None else None
    current_hash = personalization_hash(current) if current is not None else None
    if not reasons and original_hash != current_hash:
        # Python equality can equate distinct serialized inputs, e.g. -0.0 and 0.0.
        reasons.append("REQUEST_CONTENT_CHANGED")
    return EngineResultConnection(result_sha256=personalization_hash(record) if record is not None else None,
        original_request_sha256=original_hash, current_request_sha256=current_hash,
        status=connection_status(reasons), reasons=reasons)


def compare_engine_result(record: NativeEvaluationResult | dict, current: EngineEvaluationRequest | dict) -> EngineResultConnection:
    """MATCHED means identical supplied content, not fresh evidence or payment permission.

    Stored outcomes and current requests are validated once at this public boundary.
    Unsupported schemas/prompts or forged objects become fixed invalid-input reasons,
    without copying validation exception text or substituting missing versions.
    """
    record, current, reasons = _prepare_connection(record, current)
    return _compare_connection(record, current, reasons)
