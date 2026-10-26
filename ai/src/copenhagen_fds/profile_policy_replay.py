"""Attach the actual selected review to the existing observable learning replay."""

from copenhagen_fds.contracts.profile_policy import PolicyObservation
from copenhagen_fds.learning_replay import replay_learning_samples


def policy_observations_from_replay(inputs, *, origin, source_reference):
    inputs = tuple(inputs)
    samples = replay_learning_samples(inputs, origin=origin, source_reference=source_reference)
    purchases = {row.learning_input.purchase.reference.purchase_request_id: row.learning_input.purchase for row in inputs}
    return tuple(PolicyObservation(sample=sample, feedback=purchases[sample.eligibility.target.purchase_request_id].feedback)
                 for sample in samples)
