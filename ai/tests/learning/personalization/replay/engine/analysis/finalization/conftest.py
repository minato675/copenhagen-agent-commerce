"""S15P21A207-291: real behavior/policy with explicit synthetic intent and one fake clock."""

from types import SimpleNamespace

import pytest

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.contracts.analysis_results import AnalysisFailure
from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.engine import execute_engine


@pytest.fixture
def final_cases(engine_cases, analysis_cases, run_async):
    def prepare(*, signal="MATCH", amount=10000, missing=False, profile=False, required=True):
        request, _ = engine_cases.inputs(amount=amount, missing=missing, profile=profile)
        data = request.model_dump()
        data["risk_policy"]["intent_required"] = required
        if signal == "NOT_RUN":
            data["analysis"]["user_request"]["text"] = None
        request = EngineEvaluationRequest.model_validate(data)
        adapter = analysis_cases.adapter(signal if signal in {"MATCH", "MISMATCH", "UNKNOWN"} else "MATCH")
        order = request.analysis.order.orders[0]
        refs = [dict(order_id=order.order_id, item_id=order.items[0].item_id)]
        for finding in (*adapter.outcomes["comparison"]["findings"], *adapter.outcomes["comparison"]["confirmation_requests"]):
            finding["related_items"] = refs
        if signal in {"EXTRACTION_FAILED", "COMPARISON_FAILED"}:
            adapter.outcomes[signal.split("_")[0].lower()] = AnalysisFailure(kind="API_ERROR", http_status=429,
                api_error="RATE_LIMIT", retryable=True)
        now = [0.0]
        budget = AnalysisBudget.start(10, clock=lambda: now[0])
        return SimpleNamespace(request=request, adapter=adapter, now=now, budget=budget)
    def execute(**kwargs):
        case = prepare(**kwargs)
        case.execution = run_async(execute_engine(case.request, case.adapter, budget=case.budget))
        return case
    return SimpleNamespace(prepare=prepare, execute=execute)
