"""Sequential historical execution of the existing native FDS, without labels."""

from datetime import timedelta

from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.native_cli.runtime import MockScript, run_request
from copenhagen_fds.synthetic.learning import _visible_unique
from copenhagen_fds.synthetic.replay import ReplayIndex, instant
from .profiles import ProfileTimeline
from .semantic_cases import bind_semantic_observation


def feature_at_arrival(observations, purchase_id):
    candidates = [row for row in observations["purchases"] if row["purchase_request_id"] == purchase_id]
    if not candidates:
        raise ValueError("selected purchase is absent")
    cutoff = min(instant(row["available_at"]) for row in candidates)
    visible = dict(observations)
    visible["purchases"] = _visible_unique(observations["purchases"], "purchase_request_id", cutoff)
    # ReplayIndex evaluates one microsecond after purchase arrival. Validate
    # available identities, but retain exact duplicate deliveries as input
    # evidence: the prepared semantic observation hashes that raw history.
    event_cutoff = cutoff + timedelta(microseconds=1)
    _visible_unique(observations["events"], "event_id", event_cutoff)
    visible["events"] = [row for row in observations["events"] if instant(row["available_at"]) <= event_cutoff]
    return ReplayIndex(visible).feature_input(purchase_id)


def constant_mock(request):
    """An intentionally constant provider, not a semantic classifier or oracle.

    Behavioral scores remain real; GPT quality must be measured separately with
    actual GMS responses. Missing source text still prevents provider execution.
    """
    return MockScript(clock_start=request.feature_input.evaluation.evaluated_at,
        extraction=dict(items=[dict(name=item.name, quantity=item.quantity, brand=None, category=None)
            for order in request.analysis.order.orders for item in order.items],
            budget=None, purpose=None, explicit_conditions=None, preferences=None, ambiguities=None),
        comparison=dict(comparison="MATCH", findings=[], confirmation_requests=[]))


def build_request(plan, original, semantic, publications=None):
    feature, analysis = bind_semantic_observation(original, semantic)
    return EngineEvaluationRequest(
        trace_id="temporal-" + feature.evaluation.target.evaluation_id,
        feature_input=feature, analysis=analysis,
        personal=None if publications is None else publications.personal,
        population=None if publications is None else publications.population,
        population_baseline_request=None if publications is None else publications.population_baseline_request,
        initial_policy=plan.settings.cold_policy, personalization=plan.settings.personalization,
        scoring=plan.settings.scoring, risk_policy=plan.risk_policy,
        execution=dict(gateway="MOCK", requested_model="gpt-5.4-mini",
            extraction_prompt_version=EXTRACTION_PROMPT_VERSION, comparison_prompt_version=COMPARISON_PROMPT_VERSION,
            provider_timeout_seconds=10.0, total_timeout_seconds=60.0))


async def replay(plan, observations, semantic, selected):
    """Yield paired purchase rows, never receiving evaluator labels or personas."""
    # Future observation hashes must not change an earlier profile identity.
    namespace = "temporal-observations-" + personalization_hash({
        "simulation": plan.simulation, "suite": plan.suite, "variant": plan.distribution_variant})
    timeline = ProfileTimeline(observations, plan.settings, namespace)
    previous = None
    for pid in selected:
        original = feature_at_arrival(observations, pid)
        at = original.evaluation.evaluated_at
        if previous is not None and at < previous:
            raise ValueError("evaluation order must preserve arrival chronology")
        publications = timeline.before(original)
        arms = {}
        for arm, supplied_profiles in (("cold", None), ("personalized", publications)):
            request = build_request(plan, original, semantic[pid], supplied_profiles)
            result, summary = await run_request(request, script=constant_mock(request))
            summary["behavior_rules"] = (None if result.policy_result is None else
                result.policy_result.classification.evidence.rules.model_dump(mode="json"))
            behavior_stage = next(stage for stage in result.execution.stages if stage.stage == "BEHAVIOR")
            summary["behavior_status"] = ("SUCCESS" if summary["behavior_score"] is not None
                and behavior_stage.status == "SUCCESS" else "FAILED" if behavior_stage.status == "FAILED" else "INCOMPLETE")
            summary["mock_policy"] = "CONSTANT_MATCH_NOT_SEMANTIC_MODEL"
            summary["profile_references"] = {
                name: None if getattr(request, name) is None else
                    getattr(request, name).expected_reference.model_dump(mode="json")
                for name in ("personal", "population")}
            arms[arm] = summary
        yield dict(purchase_request_id=pid, user_id=original.evaluation.target.user_id,
            evaluation_id=original.evaluation.target.evaluation_id, evaluated_at=at.isoformat(),
            current_occurred_at=original.current_occurred_at.isoformat(),
            original_input_sha256=personalization_hash(original),
            semantic_observation_sha256=semantic[pid].observation_sha256,
            arms=arms, profile_audit=publications.audit,
            primary_unit="FIRST_EVALUATION_PER_PURCHASE", retry_evaluation="NOT_RUN_AUXILIARY_ONLY")
        previous = at
