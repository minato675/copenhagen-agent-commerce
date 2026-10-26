"""Replay all observed activity first; choose normal learning samples separately."""

from collections.abc import Iterable

from copenhagen_fds.contracts.features import StreamCoverage
from copenhagen_fds.contracts.learning_replay import LearningReplayInput, LearningSample
from copenhagen_fds.contracts.profiles import OriginValue
from copenhagen_fds.contracts.values import Identifier, InputModel
from copenhagen_fds.features import transform_features
from copenhagen_fds.learning import assess_learning_eligibility


class _ReplaySource(InputModel):
    origin: OriginValue
    source_reference: Identifier


def replay_learning_samples(
    inputs: Iterable[LearningReplayInput], *, origin: OriginValue, source_reference: Identifier,
) -> tuple[LearningSample, ...]:
    """One sample per whole purchase, in deterministic historical evaluation order.

    Callers supply unfiltered historical activity to feature_input, including
    purchases later excluded from normal learning. All learning cutoffs agree.
    """

    source = _ReplaySource(origin=origin, source_reference=source_reference)
    by_purchase = {}
    cutoff = None
    for item in inputs:
        learning = item.learning_input
        if cutoff is None:
            cutoff = learning.as_of
        elif cutoff != learning.as_of:
            raise ValueError("a replay batch must use one learning information cutoff")
        if learning.purchase.order.purchase_request_id == learning.current_purchase_request_id:
            continue
        key = (learning.user_id, learning.purchase.order.purchase_request_id)
        previous = by_purchase.setdefault(key, item)
        if previous != item:
            raise ValueError("one purchase has conflicting snapshots or replay contexts")
    samples = []
    ordered = sorted(by_purchase.values(), key=lambda item: (
        item.feature_input.evaluation.evaluated_at,
        item.learning_input.user_id, item.learning_input.purchase.order.purchase_request_id,
    ))
    for item in ordered:
        feature_input, learning = item.feature_input, item.learning_input
        # Never remove DEFER/EXCLUDE purchases from the feature history.
        vector = transform_features(feature_input)
        eligibility = assess_learning_eligibility(learning)
        history = feature_input.history
        samples.append(LearningSample(
            origin=source.origin, source_reference=source.source_reference,
            purchase_occurred_at=learning.purchase.occurred_at,
            purchase_available_at=learning.purchase.available_at,
            raw_features=vector, eligibility=eligibility,
            purchase_coverage=StreamCoverage() if history is None else history.purchases,
            attempt_coverage=StreamCoverage() if history is None else history.attempts,
        ))
    return tuple(samples)
