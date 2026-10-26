"""Weight existing group evidence without classifying risk or invoking external I/O."""

from copenhagen_fds.contracts.risk_aggregation import RiskAggregationResult, aggregation_fields
from copenhagen_fds.contracts.risk_inputs import RiskPolicyInput
from copenhagen_fds.risk_assessment import assess_risk_behavior


def aggregate_risk_behavior(supplied: RiskPolicyInput) -> RiskAggregationResult:
    """Return a total only for complete score coverage; preserve Cold fallback failures."""
    assessment = assess_risk_behavior(supplied)
    return RiskAggregationResult(assessment=assessment, **aggregation_fields(assessment))
