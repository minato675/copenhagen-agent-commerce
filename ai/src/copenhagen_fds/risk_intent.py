"""Interpret already computed GPT evidence without invoking a model or scorer."""

from copenhagen_fds.contracts.risk_inputs import RiskInputReference, RiskPolicyInput
from copenhagen_fds.contracts.risk_intent import RiskIntentSignal, intent_signal_fields


def interpret_risk_intent(supplied: RiskPolicyInput) -> RiskIntentSignal:
    # Revalidate even unchecked model_copy/model_construct values at this boundary.
    supplied = RiskPolicyInput.model_validate(supplied.model_dump())
    return RiskIntentSignal(input_reference=RiskInputReference.from_input(supplied),
                            source=supplied.intent, **intent_signal_fields(supplied.intent))
