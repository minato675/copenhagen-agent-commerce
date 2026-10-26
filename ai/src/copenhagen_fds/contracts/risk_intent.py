"""Lossless semantic signals, separate from behavior scores and execution failure."""

from typing import Literal, Self

from pydantic import model_validator

from copenhagen_fds.contracts.analysis_results import IntentComparisonResult
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput
from copenhagen_fds.contracts.risk_results import PolicyReason
from copenhagen_fds.contracts.values import Array, InputModel


IntentSignal = Literal["MATCH", "MISMATCH", "UNKNOWN", "FAILED", "NOT_RUN"]


def intent_signal_fields(source: IntentComparisonResult) -> dict:
    signal = source.payload.comparison if source.status == "SUCCESS" else source.status
    codes = {"MISMATCH": "INTENT_MISMATCH", "UNKNOWN": "INTENT_UNKNOWN",
             "FAILED": "INTENT_FAILED", "NOT_RUN": "INTENT_NOT_RUN"}
    return dict(signal=signal, reasons=() if signal == "MATCH" else (PolicyReason(code=codes[signal]),))


class RiskIntentSignal(InputModel):
    schema_version: Literal["risk-intent-signal-v1"] = "risk-intent-signal-v1"
    input_reference: RiskInputReference
    source: IntentComparisonResult
    signal: IntentSignal
    reasons: Array[PolicyReason]

    @model_validator(mode="after")
    def validate_signal(self) -> Self:
        reference, metadata = self.input_reference, self.source.metadata
        if reference.intent_sha256 != personalization_hash(self.source):
            raise ValueError("intent signal must preserve the entire source envelope")
        if metadata.target != reference.target or metadata.trace_id != reference.trace_id:
            raise ValueError("intent signal must preserve its evaluation target and trace")
        for field, expected in intent_signal_fields(self.source).items():
            if getattr(self, field) != expected:
                raise ValueError("intent signal must distinguish semantics from execution failure")
        return self

    def validate_for_input(self, supplied: RiskPolicyInput) -> Self:
        from copenhagen_fds.risk_intent import interpret_risk_intent

        if self != interpret_risk_intent(supplied):
            raise ValueError("intent signal must reproduce from the exact valid request and policy")
        return self
