"""Explicit wire values; uncertainty is separate from execution failure."""

from enum import StrEnum


class EvaluationStatus(StrEnum):
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    NOT_RUN = "NOT_RUN"


class DecisionStatus(StrEnum):
    SUCCESS = "SUCCESS"
    INCOMPLETE = "INCOMPLETE"
    FAILED = "FAILED"
    NOT_RUN = "NOT_RUN"


class IntentMatch(StrEnum):
    MATCH = "MATCH"
    MISMATCH = "MISMATCH"
    UNKNOWN = "UNKNOWN"


class RiskLevel(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    UNKNOWN = "UNKNOWN"


class DataSource(StrEnum):
    BE = "BE"
    OPENAI = "OPENAI"
    SIFT = "SIFT"
    RULE_ENGINE = "RULE_ENGINE"
    FDS = "FDS"
    MOCK = "MOCK"
    UNKNOWN = "UNKNOWN"


class ReasonCode(StrEnum):
    INTENT_MISMATCH = "INTENT_MISMATCH"
    INTENT_UNCERTAIN = "INTENT_UNCERTAIN"
    SIFT_RISK = "SIFT_RISK"
    RULE_TRIGGERED = "RULE_TRIGGERED"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    EVALUATION_FAILED = "EVALUATION_FAILED"
    TIMEOUT = "TIMEOUT"
    INVALID_RESPONSE = "INVALID_RESPONSE"
    NOT_EXECUTED = "NOT_EXECUTED"
    REQUIRED_EVALUATION_UNAVAILABLE = "REQUIRED_EVALUATION_UNAVAILABLE"


class EvaluationComponent(StrEnum):
    INTENT = "INTENT"
    SIFT = "SIFT"
    RULES = "RULES"


class FdsRecommendation(StrEnum):
    """FDS advice only; never evidence of approval or execution authority."""

    ALLOW = "ALLOW"
    REQUIRE_AUTH = "REQUIRE_AUTH"
    DENY = "DENY"


class ReviewAction(StrEnum):
    CHECK_INPUT = "CHECK_INPUT"
    RETRY_EVALUATION = "RETRY_EVALUATION"
    REVIEW_ORDER = "REVIEW_ORDER"
    VERIFY_USER_INTENT = "VERIFY_USER_INTENT"
