"""Compact, non-authorizing provenance comparison for an existing native result."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.values import Array, InputModel, Sha256


ConnectionReason = Literal[
    "INVALID_RESULT", "INVALID_CURRENT_REQUEST", "USER_MISMATCH", "PURCHASE_MISMATCH",
    "ORDER_CONTENT_CONFLICT", "INTENT_CONTENT_CONFLICT", "EVALUATION_ID_CHANGED", "TRACE_CHANGED",
    "ORDER_CHANGED", "INTENT_CHANGED", "CONTEXT_CHANGED", "BEHAVIOR_TIME_CHANGED", "FEATURE_INPUT_CHANGED",
    "PERSONAL_PROFILE_CHANGED", "POPULATION_PROFILE_CHANGED", "POPULATION_BASELINE_CHANGED",
    "COLD_POLICY_CHANGED", "PERSONALIZATION_CHANGED", "SCORING_CHANGED", "RISK_POLICY_CHANGED",
    "MODEL_CHANGED", "PROMPT_CHANGED", "EXECUTION_CONFIG_CHANGED", "REQUEST_CONTENT_CHANGED",
]
INVALID_CONNECTION = frozenset(("INVALID_RESULT", "INVALID_CURRENT_REQUEST", "USER_MISMATCH", "PURCHASE_MISMATCH",
                                "ORDER_CONTENT_CONFLICT", "INTENT_CONTENT_CONFLICT"))


def connection_status(reasons):
    return "INVALID_LINK" if INVALID_CONNECTION.intersection(reasons) else "REEVALUATION_REQUIRED" if reasons else "MATCHED"


class EngineResultConnection(InputModel):
    schema_version: Literal["engine-result-connection-v1"] = "engine-result-connection-v1"
    result_sha256: Sha256 | None
    original_request_sha256: Sha256 | None
    current_request_sha256: Sha256 | None
    status: Literal["MATCHED", "REEVALUATION_REQUIRED", "INVALID_LINK"]
    reasons: Array[ConnectionReason]

    @model_validator(mode="after")
    def validate_comparison(self) -> Self:
        if len(set(self.reasons)) != len(self.reasons) or self.status != connection_status(self.reasons):
            raise ValueError("connection status must preserve distinct mismatch reasons")
        invalid_result = "INVALID_RESULT" in self.reasons
        if invalid_result != (self.result_sha256 is None) or invalid_result != (self.original_request_sha256 is None):
            raise ValueError("invalid result cannot claim validated result provenance")
        if ("INVALID_CURRENT_REQUEST" in self.reasons) != (self.current_request_sha256 is None):
            raise ValueError("invalid request cannot claim validated current provenance")
        if not invalid_result and "INVALID_CURRENT_REQUEST" not in self.reasons:
            same = self.original_request_sha256 == self.current_request_sha256
            if same != (self.status == "MATCHED"):
                raise ValueError("a matched connection requires exact request content")
        return self
