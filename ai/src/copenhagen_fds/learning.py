"""Deterministic basic eligibility; no fitting, persistence or final FDS decision."""

from copenhagen_fds.contracts.learning import LearningDecision, LearningInput


def assess_learning_eligibility(request: LearningInput) -> LearningDecision:
    """Use only facts available at as_of; observed activity survives exclusion."""

    purchase = request.purchase
    cutoff = request.as_of
    current = purchase.order.purchase_request_id == request.current_purchase_request_id
    observed = (
        not current and purchase.occurred_at < cutoff and purchase.available_at <= cutoff
        and any(event.kind == "PURCHASE_REQUESTED" and event.available_at <= cutoff
                for event in purchase.events)
    )
    attempt_ids = tuple(sorted(
        event.attempt_id for event in purchase.events
        if observed and event.kind == "PAYMENT_ATTEMPTED"
        and event.occurred_at < cutoff and event.available_at <= cutoff
    ))
    feedback = purchase.feedback
    if not observed or feedback is not None and feedback.available_at > cutoff:
        feedback = None

    if current:
        reason = "CURRENT_PURCHASE"
    elif not observed:
        reason = "PURCHASE_NOT_OBSERVED"
    elif feedback is None or feedback.classification == "UNCONFIRMED":
        reason = "UNCONFIRMED"
    elif feedback.classification == "SUSPECTED":
        reason = "SUSPECTED"
    elif feedback.verification != "VERIFIED":
        reason = "UNVERIFIED_FEEDBACK"
    else:
        reason = feedback.classification

    status = "INCLUDE" if reason == "NORMAL_CONFIRMED" else "EXCLUDE" if reason == "FRAUD_CONFIRMED" else "DEFER"
    return LearningDecision(
        target=purchase.reference, as_of=cutoff, policy_version=request.policy_version,
        status=status, reason=reason, observed_purchase=observed,
        observed_attempt_ids=attempt_ids,
        feedback_id=None if feedback is None else feedback.feedback_id,
        learning_amount=purchase.order.total if status == "INCLUDE" else None,
    )
