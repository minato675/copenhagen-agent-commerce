"""Check caller-supplied result validity without changing evidence or refreshing its clock."""

from datetime import timedelta

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.engine_reuse import EngineReuseAssessment, EngineReuseContext, EngineReuseIssue, reuse_status
from copenhagen_fds.contracts.native_evaluation import NativeEvaluationResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.engine_reuse_connection import _compare_connection, _prepare_connection


def _temporal_issues(record, connection, context):
    issues, deadlines = [], []
    at, request = context.checked_at, record.execution.request

    def issue(component, code, boundary=None):
        value = EngineReuseIssue(component=component, code=code, boundary=boundary)
        if not any((i.component, i.code) == (component, code) for i in issues):
            issues.append(value)

    def window(component, start, end):
        if start is None: issue(component, "MISSING_START")
        if end is None: issue(component, "MISSING_EXPIRY")
        if start is None or end is None: return
        if start >= end:
            issue(component, "INVALID_VALIDITY_INTERVAL", end)
            return
        deadlines.append(end)
        if at < start: issue(component, "NOT_YET_VALID", start)
        if at >= end: issue(component, "EXPIRED", end)

    if at < record.completed_at:
        issue("RESULT", "CHECK_BEFORE_RESULT", record.completed_at)
    if record.termination != "COMPLETED": issue("RESULT", "RESULT_INTERRUPTED")
    if record.outcome.status != "SUCCESS": issue("RESULT", "RESULT_NOT_SUCCESSFUL")
    if record.execution.behavior is None or record.policy_stage.status != "SUCCESS":
        issue("RESULT", "INCOMPLETE_STAGES")
    if record.policy_result is None: issue("RESULT", "MISSING_POLICY_RESULT")
    if request.analysis.user_request.version is None: issue("INTENT", "MISSING_INTENT_VERSION")

    validity = context.validity
    if validity is None:
        issue("RESULT", "MISSING_VALIDITY")
    else:
        for component, provided, expected in (("RESULT", validity.result_sha256, connection.result_sha256),
                ("RISK_POLICY", validity.risk_policy_sha256, personalization_hash(request.risk_policy))):
            if provided is None: issue(component, "MISSING_BINDING")
            elif provided != expected: issue(component, "WRONG_BINDING")
        window("RESULT", record.completed_at, validity.result_valid_until)
        window("RISK_POLICY", validity.policy_valid_from, validity.policy_valid_until)
        if validity.policy_valid_from is not None and record.policy_stage.started_at is not None:
            if record.policy_stage.started_at < validity.policy_valid_from:
                issue("RISK_POLICY", "INVALID_VALIDITY_INTERVAL", validity.policy_valid_from)

    for name in ("personal", "population"):
        supplied = getattr(request, name)
        if supplied is not None:
            profile = supplied.state.published.profile
            window(name.upper() + "_PROFILE", max(profile.generated_at, supplied.available_at), profile.valid_until)
    if request.population is not None and record.execution.behavior is not None:
        derived = record.execution.behavior.scoring_input.population_baseline
        if derived is None or derived.baseline is None:
            issue("POPULATION_BASELINE", "REFERENCE_UNAVAILABLE")
        else:
            baseline = derived.baseline
            window("POPULATION_BASELINE", baseline.available_at, baseline.profile.valid_until)
    if request.initial_policy is not None:
        policy = request.initial_policy
        window("COLD_POLICY", max(policy.generated_at, policy.available_at, policy.valid_from), policy.valid_until)

    for name in ("extraction", "comparison"):
        result = getattr(record.execution, name)
        component = name.upper()
        if result is None:
            issue(component, "INCOMPLETE_STAGES")
            continue
        if result.status == "NOT_RUN" and request.analysis.user_request.text is None:
            # Explicitly absent text has no model response to pin or invent.
            continue
        if result.status != "SUCCESS":
            issue(component, "INCOMPLETE_STAGES")
            continue
        metadata = result.metadata
        expected_model = getattr(context, "expected_" + name + "_model_version")
        if expected_model is None or metadata.model_version is None:
            issue(component, "MISSING_MODEL_VERSION")
        elif expected_model != metadata.model_version:
            issue(component, "MODEL_VERSION_CHANGED")
        try:
            # This limit is already explicit in the original/current risk policy.
            deadline = metadata.evaluated_at + timedelta(seconds=request.risk_policy.intent_max_age_seconds)
        except (TypeError, OverflowError):
            issue(component, "INVALID_VALIDITY_INTERVAL")
            continue
        if metadata.valid_until is not None: deadline = min(deadline, metadata.valid_until)
        window(component, metadata.evaluated_at, deadline)
    return issues, deadlines


def check_engine_result_reuse(
    record: NativeEvaluationResult | dict, current: EngineEvaluationRequest | dict, *, context: EngineReuseContext | dict,
) -> EngineReuseAssessment:
    """VALID permits reuse only for the exact supplied historical evaluation snapshot.

    Current state, deadline issuance and resolved model expectations belong to the
    trusted caller. No cache, I/O, clock sampling, score recalculation or payment
    action occurs here. The inspection time never replaces behavior_as_of.
    """
    record, current, reasons = _prepare_connection(record, current)
    connection = _compare_connection(record, current, reasons)
    issues, deadlines = [], []
    try:
        context = EngineReuseContext.model_validate(context.model_dump() if isinstance(context, EngineReuseContext) else context)
    except (ValueError, TypeError):
        context = None
        issues.append(EngineReuseIssue(component="CONTEXT", code="INVALID_CONTEXT"))
    if record is not None and context is not None:
        issues, deadlines = _temporal_issues(record, connection, context)
    status = reuse_status(connection, issues)
    return EngineReuseAssessment(connection=connection, context_sha256=personalization_hash(context) if context is not None else None,
        checked_at=context.checked_at if context is not None else None, status=status, reusable=status == "VALID",
        valid_until=min(deadlines) if status == "VALID" else None, issues=issues)
