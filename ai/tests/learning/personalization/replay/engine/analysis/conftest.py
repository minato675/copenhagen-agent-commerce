"""Task 289 uses caller snapshots and synthetic completions, never a real key."""

from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from copenhagen_fds.analysis.transport_models import StructuredCompletion
from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.openai_settings import OpenAISettings


@pytest.fixture
def analysis_cases(engine_cases):
    request, row = engine_cases.inputs(profile=True)
    data = request.model_dump()
    data["analysis"]["user_request"].update(source="agent_summary", text="synthetic-private-intent")
    data["feature_input"]["evaluation"]["request"]["intent"]["source"] = "agent_summary"
    request = EngineEvaluationRequest.model_validate(data)
    extraction = dict(items=[dict(name="synthetic-extracted-product", quantity=1, brand=None, category=None)],
        budget=None, purpose=None, explicit_conditions=None, preferences=None, ambiguities=None)
    order = request.analysis.order.orders[0]
    refs = [dict(order_id=order.order_id, item_id=order.items[0].item_id)]

    def comparison(signal="MATCH"):
        code = "INTENT_MISMATCH" if signal == "MISMATCH" else "INTENT_UNCERTAIN"
        return dict(comparison=signal,
            findings=[] if signal == "MATCH" else [dict(code=code, dimension="PRODUCT", related_items=refs)],
            confirmation_requests=[] if signal == "MATCH" else [dict(code="CLARIFY_INTENT", related_items=refs)])

    class Adapter:
        def __init__(self, signal="MATCH"):
            self.settings = OpenAISettings(mode="MOCK", openai_model=request.execution.requested_model,
                openai_timeout_seconds=5.0, evaluation_timeout_seconds=10.0)
            self.outcomes = dict(extraction=extraction, comparison=comparison(signal))
            self.calls = []
            self.on_call = None

        async def complete(self, prompt, *, budget):
            self.calls.append((prompt, budget))
            if self.on_call is not None:
                self.on_call(prompt.stage)
            outcome = self.outcomes[prompt.stage]
            if isinstance(outcome, BaseException):
                raise outcome
            failed = isinstance(outcome, AnalysisFailure)
            return StructuredCompletion(stage=prompt.stage, status="FAILED" if failed else "SUCCESS",
                payload=None if failed else outcome, failure=outcome if failed else None,
                telemetry=dict(gateway="MOCK", requested_model=self.settings.openai_model,
                    request_id=f"synthetic-{prompt.stage}", response_id=f"synthetic-response-{prompt.stage}",
                    duration_ms=2.5, usage=dict(prompt_tokens=4, completion_tokens=3, total_tokens=7)),
                prompt_version=prompt.version, response_model="synthetic-resolved-model",
                completed_at=datetime.now(timezone.utc))

    return SimpleNamespace(request=request, row=row, adapter=Adapter, extracted=extraction,
        comparison=comparison, refs=refs)
