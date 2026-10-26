"""Typed synthetic completions; no model inference or network access."""

from datetime import datetime, timezone
from copenhagen_fds.analysis import StructuredCompletion
from copenhagen_fds.analysis.comparison_prompt import COMPARISON_PROMPT_VERSION
from copenhagen_fds.analysis.extraction_prompt import EXTRACTION_PROMPT_VERSION
from copenhagen_fds.contracts import AnalysisTelemetry, IntentComparisonOutput, IntentExtractionOutput


def ref(order="order-1", item="line-1"):
    return {"order_id": order, "item_id": item}


def output(comparison="MATCH", code=None, dimension="CONDITION", refs=None, confirmation=None):
    return {
        "comparison": comparison,
        "findings": [] if code is None else [{"code": code, "dimension": dimension, "related_items": refs or []}],
        "confirmation_requests": [] if confirmation is None else [{"code": confirmation, "related_items": refs or []}],
    }


def completion(stage, payload=None, failure=None, **overrides):
    model = IntentExtractionOutput if stage == "extraction" else IntentComparisonOutput
    values = dict(
        stage=stage, status="FAILED" if failure else "SUCCESS",
        payload=None if failure else model.model_validate(payload), failure=failure,
        telemetry=AnalysisTelemetry(
            gateway="MOCK", requested_model="gpt-5.4-mini", response_id=f"synthetic-{stage}",
            request_id=f"comparison_request-{stage}", duration_ms=12.5,
            usage={"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
        ),
        response_model="gpt-5.4-mini-synthetic",
        prompt_version=EXTRACTION_PROMPT_VERSION if stage == "extraction" else COMPARISON_PROMPT_VERSION,
        completed_at=datetime(2026, 9, 9, 12, tzinfo=timezone.utc),
    )
    return StructuredCompletion(**{**values, **overrides})


class StubAdapter:
    def __init__(self, settings, outcomes):
        self.settings, self.outcomes, self.calls = settings, outcomes, []
        self.on_call = None

    async def complete(self, prompt, *, budget):
        self.calls.append((prompt, budget, budget.remaining_seconds))
        if self.on_call:
            self.on_call(prompt.stage)
        result = self.outcomes[prompt.stage]
        if isinstance(result, BaseException):
            raise result
        return result
