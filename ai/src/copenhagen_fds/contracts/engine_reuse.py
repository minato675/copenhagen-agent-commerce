"""Explicit validity evidence and a read-only, non-authorizing reuse assessment."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.engine_reuse_connection import EngineResultConnection
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, Identifier, InputModel, Sha256


class EngineResultValidity(InputModel):
    """Trusted caller-owned deadlines, pinned to the saved result and its risk policy.

    Missing information remains absent. This record neither authenticates its issuer
    nor permits a checker to extend previously assigned deadlines.
    """
    schema_version: Literal["engine-result-validity-v1"] = "engine-result-validity-v1"
    result_sha256: Sha256 | None = None
    result_valid_until: Timestamp | None = None
    risk_policy_sha256: Sha256 | None = None
    policy_valid_from: Timestamp | None = None
    policy_valid_until: Timestamp | None = None


class EngineReuseContext(InputModel):
    checked_at: Timestamp
    validity: EngineResultValidity | None = None
    # Pin the currently expected actual response model, not a mutable requested alias.
    expected_extraction_model_version: Identifier | None = None
    expected_comparison_model_version: Identifier | None = None


class EngineReuseIssue(InputModel):
    component: Literal["CONTEXT", "RESULT", "RISK_POLICY", "INTENT", "EXTRACTION", "COMPARISON",
                       "PERSONAL_PROFILE", "POPULATION_PROFILE", "POPULATION_BASELINE", "COLD_POLICY"]
    code: Literal["INVALID_CONTEXT", "WRONG_BINDING", "CHECK_BEFORE_RESULT", "INVALID_VALIDITY_INTERVAL",
                  "MISSING_VALIDITY", "MISSING_BINDING", "MISSING_EXPIRY", "MISSING_START",
                  "NOT_YET_VALID", "EXPIRED", "MISSING_MODEL_VERSION", "MODEL_VERSION_CHANGED",
                  "MISSING_INTENT_VERSION", "RESULT_NOT_SUCCESSFUL", "RESULT_INTERRUPTED",
                  "INCOMPLETE_STAGES", "MISSING_POLICY_RESULT", "REFERENCE_UNAVAILABLE"]
    boundary: Timestamp | None = None


INVALID_REUSE = frozenset(("INVALID_CONTEXT", "WRONG_BINDING", "CHECK_BEFORE_RESULT", "INVALID_VALIDITY_INTERVAL"))


def reuse_status(connection, issues):
    if connection.status == "INVALID_LINK" or any(i.code in INVALID_REUSE for i in issues):
        return "INVALID_LINK"
    return "REEVALUATION_REQUIRED" if connection.status != "MATCHED" or issues else "VALID"


class EngineReuseAssessment(InputModel):
    schema_version: Literal["engine-reuse-assessment-v1"] = "engine-reuse-assessment-v1"
    connection: EngineResultConnection
    context_sha256: Sha256 | None
    checked_at: Timestamp | None
    status: Literal["VALID", "REEVALUATION_REQUIRED", "INVALID_LINK"]
    reusable: bool
    valid_until: Timestamp | None
    issues: Array[EngineReuseIssue]

    @model_validator(mode="after")
    def validate_assessment(self) -> Self:
        if len({(i.component, i.code) for i in self.issues}) != len(self.issues):
            raise ValueError("reuse issues must be distinct by component and reason")
        invalid_context = any(i.code == "INVALID_CONTEXT" for i in self.issues)
        if invalid_context != (self.context_sha256 is None) or invalid_context != (self.checked_at is None):
            raise ValueError("invalid inspection context cannot claim validated time or provenance")
        if self.status != reuse_status(self.connection, self.issues) or self.reusable != (self.status == "VALID"):
            raise ValueError("reuse disposition must preserve connection and temporal failures")
        if self.reusable:
            if self.valid_until is None or self.checked_at is None or self.checked_at >= self.valid_until:
                raise ValueError("reusable evidence requires a known exclusive deadline")
        elif self.valid_until is not None:
            raise ValueError("rejected evidence must not claim a reuse window")
        return self
