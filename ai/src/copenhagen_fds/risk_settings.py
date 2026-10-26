"""Versioned project policy; empirical fraud calibration remains unvalidated."""

from copenhagen_fds.contracts.risk_policy import GROUP_FEATURES, RiskPolicyConfig


DEFAULT_RISK_POLICY_VERSION = "fds-risk-policy-v2"


def default_risk_policy() -> RiskPolicyConfig:
    """Give any complete final feature at 100 a MEDIUM floor, preserving totals.

    MAX groups make each group threshold equivalent to any member reaching 100.
    HIGH requires the weighted total to reach 70; no single-group HIGH rule is
    installed. Missing evidence and failures retain the classifier's precedence.
    """
    return RiskPolicyConfig(
        version=DEFAULT_RISK_POLICY_VERSION,
        groups=[dict(group_id=name, weight=weight,
                     features=[dict(feature_id=feature, required=True) for feature in members])
                for (name, members), weight in zip(GROUP_FEATURES.items(), (0.40, 0.35, 0.25))],
        thresholds=dict(medium_from=30.0, high_from=70.0),
        intent_required=True,
        intent_max_age_seconds=300,
        floor_rules=[dict(rule_id=f"maximum-{name.lower()}",
                         conditions=[dict(group_id=name, minimum_score=100.0)], minimum_risk="MEDIUM")
                     for name in GROUP_FEATURES],
    )
