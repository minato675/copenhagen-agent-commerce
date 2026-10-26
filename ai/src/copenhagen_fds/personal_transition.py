"""Per-feature window choice and Cold/transition/personal reference-use policy."""

from copenhagen_fds.contracts.personal_transition import FeatureTransition, PersonalTransitionResult
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput
from copenhagen_fds.personal_readiness import evaluate_personal_readiness


def decide_personal_transition(supplied: PersonalizedScoringInput) -> PersonalTransitionResult:
    """Choose reference support without executing scores or mixing their values.

    A missing Cold reference never turns partial personal support into 100%.
    The later evaluator must handle actual component failures separately.
    """
    supplied = PersonalizedScoringInput.model_validate(supplied.model_dump())
    readiness = evaluate_personal_readiness(supplied)
    raw = {f.feature_id: f for f in supplied.cold.raw_features.features}
    cold = {f.feature_id: f for f in supplied.cold.selection.features}
    features = []
    for support in readiness.features:
        name = support.feature_id
        recent, long_term = support.windows
        best = max(support.windows, key=lambda w: w.personal_weight)
        weight = best.personal_weight
        selected = best.window if weight > 0 else None
        reasons = ["NO_PERSONAL_WINDOW" if selected is None else (
            "RECENT_TIE" if recent.personal_weight == long_term.personal_weight else selected + "_HIGHER_SUPPORT")]
        stage, pw, cw = "UNAVAILABLE", None, None
        if raw[name].status != "AVAILABLE":
            reasons.append("INPUT_" + raw[name].status)
        elif weight == 1:
            stage, pw, cw = "PERSONALIZED", 1.0, 0.0
            reasons.append("PERSONAL_SUPPORT_FULL")
        elif cold[name].status == "SELECTED":
            stage, pw, cw = "COLD" if weight == 0 else "TRANSITION", weight, 1.0 - weight
            reasons.append("COLD_REFERENCE_FALLBACK" if weight == 0 else "BOTH_REFERENCES_REQUIRED")
        else:
            reasons.append("NO_USABLE_REFERENCE" if weight == 0 else "COLD_REQUIRED_FOR_TRANSITION")
        features.append(FeatureTransition(raw=raw[name], cold_reference=cold[name], selected_window=selected,
            preferred_personal_weight=weight, stage=stage, personal_weight=pw, cold_weight=cw, reasons=reasons))
    return PersonalTransitionResult(readiness=readiness, features=features)
