"""Canonical MAX groups over existing feature scores, without rescoring."""

import math
from collections.abc import Sequence

from copenhagen_fds.contracts.behavior import AnomalyScore, FeatureScoreResult
from copenhagen_fds.contracts.risk_policy import RiskGroupSetting
from copenhagen_fds.contracts.risk_results import FeatureContribution, GroupContribution


AGGREGATION_VERSION = "risk-group-max-first-v1"


def aggregate_risk_group(
    setting: RiskGroupSetting, features: Sequence[FeatureScoreResult],
) -> GroupContribution:
    """Use the first canonical maximum; retain missing scores without partial weights.

    Input row order is immaterial. The configured member set must be present exactly
    once, including explicit unavailable results supplied by the coverage stage.
    """
    setting = RiskGroupSetting.model_validate(setting.model_dump())
    features = tuple(FeatureScoreResult.model_validate(row.model_dump()) for row in features)
    indexed = {row.feature_id: row for row in features}
    names = tuple(row.feature_id for row in setting.features)
    if len(indexed) != len(features) or set(indexed) != set(names):
        raise ValueError("group input requires each configured feature exactly once")
    ordered = tuple(indexed[name] for name in names)
    if any(row.score is None for row in ordered):
        return GroupContribution(group_id=setting.group_id, weight=setting.weight,
            features=tuple(FeatureContribution(feature=row, weighted_points=None) for row in ordered),
            score=None, weighted_points=None)

    maximum = max(row.score.value for row in ordered)
    winner = next(row.feature_id for row in ordered if row.score.value == maximum)
    points = maximum * setting.weight
    # Match the existing result contract: positive deviations must not become zero.
    if maximum > 0 and points == 0:
        points = math.nextafter(0.0, 1.0)
    return GroupContribution(group_id=setting.group_id, weight=setting.weight,
        features=tuple(FeatureContribution(feature=row,
            weighted_points=points if row.feature_id == winner else 0.0) for row in ordered),
        score=AnomalyScore(value=maximum, unit="BEHAVIOR_ANOMALY_0_100"), weighted_points=points)
