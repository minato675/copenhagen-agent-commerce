"""Dispatch only the chosen windows to the existing personal score calculations."""

from copenhagen_fds.contracts.personal_amount import AMOUNT_SCORE_VERSION
from copenhagen_fds.contracts.personal_components import (
    BLEND_SCALE_VERSION, PersonalComponentResult, PersonalComponentsResult, PersonalScoringPolicies,
)
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION
from copenhagen_fds.contracts.personal_readiness import READINESS_VERSION
from copenhagen_fds.contracts.personal_time import TIME_PERIODS, TIME_SCORE_VERSION
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput, personalization_hash
from copenhagen_fds.contracts.personalization_results import personal_feature_evidence
from copenhagen_fds.contracts.scoring import UNAVAILABLE_STATES
from copenhagen_fds.personal_amount import _candidate as amount_candidate
from copenhagen_fds.personal_frequency import _candidate as frequency_candidate
from copenhagen_fds.personal_time import _candidate as time_candidate
from copenhagen_fds.personal_transition import decide_personal_transition


def validate_scoring_configuration(supplied: PersonalizedScoringInput, policies: PersonalScoringPolicies) -> None:
    """Reject unknown semantics before any score execution, even on Cold-only requests."""
    if supplied.config.score_scale_version != BLEND_SCALE_VERSION or policies.scale_version != BLEND_SCALE_VERSION:
        raise ValueError("personal evaluation requires its supported score scale version")
    if supplied.config.transition_policy_version != READINESS_VERSION:
        raise ValueError("personal evaluation requires its supported transition version")
    versions = {"amount_minor": AMOUNT_SCORE_VERSION,
                **dict.fromkeys(FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION),
                **dict.fromkeys(TIME_PERIODS, TIME_SCORE_VERSION)}
    requested = supplied.cold.request.evaluation.requested_features
    if any(f.score_version != versions[f.feature_id] for f in supplied.config.features if f.feature_id in requested):
        raise ValueError("each requested personal feature requires its supported score version")


def _component(supplied, decision, policies, availability, settings):
    raw, window = decision.raw, decision.selected_window
    unavailable = UNAVAILABLE_STATES.get(raw.status)
    diagnostic = "INPUT_" + raw.status
    if unavailable is None and availability.status != "AVAILABLE":
        unavailable = ("INSUFFICIENT_DATA", "REFERENCE_UNAVAILABLE")
        diagnostic = "PROFILE_" + availability.status
    if unavailable is None and window is None:
        unavailable = ("NOT_RUN", "NOT_EXECUTED")
        diagnostic = "PERSONAL_SUPPORT_ZERO"
    if unavailable is not None:
        return PersonalComponentResult(result=dict(feature_id=raw.feature_id, status=unavailable[0],
            reasons=[unavailable[1]]), diagnostic=diagnostic)
    evidence = next(e for e in personal_feature_evidence(supplied, raw.feature_id) if e.window == window)
    minimum = max(2, settings[raw.feature_id].readiness.minimum_samples,
                  supplied.personal.state.published.config.min_samples)
    # Reuse the candidate bodies used by the public two-window score APIs. Do not
    # evaluate an unselected window or select a different window after a failure.
    scorer, policy = (amount_candidate, policies.amount) if raw.feature_id == "amount_minor" else (
        (frequency_candidate, policies.frequency) if raw.feature_id in FREQUENCY_FEATURES else (time_candidate, policies.time))
    candidate = scorer(window, evidence, raw, availability, minimum, policy)
    return PersonalComponentResult(result=candidate.result, diagnostic=candidate.diagnostic, candidate=candidate)


def evaluate_personal_components(
    supplied: PersonalizedScoringInput, policies: PersonalScoringPolicies = PersonalScoringPolicies(),
) -> PersonalComponentsResult:
    """Use published evidence, without learning, Cold calculation, I/O or a clock."""
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    policies = PersonalScoringPolicies.model_validate(policies.model_dump())
    validate_scoring_configuration(supplied, policies)
    transition = decide_personal_transition(supplied)
    availability = supplied.profile_availability()[0]
    settings = {f.feature_id: f for f in supplied.config.features}
    features = tuple(_component(supplied, decision, policies, availability, settings) for decision in transition.features)
    return PersonalComponentsResult(input_sha256=personalization_hash(supplied), policies=policies,
        policies_sha256=personalization_hash(policies), transition=transition, features=features)
