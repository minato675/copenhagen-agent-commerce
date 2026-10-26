"""S15P21A207-292: real native records with a deterministic observable timeline."""

from datetime import timedelta
from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.engine_inputs import EngineEvaluationRequest
from copenhagen_fds.native_evaluation import evaluate_fraud
from copenhagen_fds.personal_replay import _probe_input


@pytest.fixture
def reuse_cases(final_cases, personalized, monkeypatch, run_async):
    import copenhagen_fds.engine as engine
    import copenhagen_fds.engine_stages as stages
    import copenhagen_fds.engine_finalization as finalization

    def make(*, profile=False, population=False, signal="MATCH", required=True, missing=False, intent_version="reuse-intent-v1"):
        case = final_cases.prepare(profile=profile, signal=signal, required=required, missing=missing)
        data = case.request.model_dump()
        if population:
            source = personalized.publication(scope="POPULATION")
            at = source.available_at + timedelta(seconds=1)
            feature = _probe_input("a", "KRW", 10000, at - timedelta(microseconds=1), at)
            original = feature.evaluation.request
            data.update(feature_input=feature.model_dump(), population=source,
                population_baseline_request=dict(expected_source_reference=source.expected_reference,
                    currency="KRW", available_at=source.available_at),
                analysis=dict(user_request=dict(purchase_request_id=original.context.purchase_request_id,
                    source=original.intent.source, text="합성 구매 요청"),order=original.order.model_dump(), context=original.context.model_dump()))
            data["initial_policy"].update(generated_at=at-timedelta(days=2),available_at=at-timedelta(days=2),
                valid_from=at-timedelta(days=2),valid_until=at+timedelta(days=2))
        data["feature_input"]["evaluation"]["request"]["intent"]["version"] = intent_version
        data["analysis"]["user_request"]["version"] = intent_version
        case.request = EngineEvaluationRequest.model_validate(data)
        clock = [case.request.feature_input.evaluation.evaluated_at + timedelta(seconds=1)]
        def now():
            clock[0] += timedelta(microseconds=1)
            return clock[0]
        for module in (engine, stages, finalization):
            monkeypatch.setattr(module, "utc_now", now)
        original_complete = case.adapter.complete
        async def complete(prompt, *, budget):
            result = await original_complete(prompt, budget=budget)
            return result.model_copy(update=dict(completed_at=now()))
        case.adapter.complete = complete
        case.result = run_async(evaluate_fraud(case.request, case.adapter, budget=case.budget))
        return case
    return SimpleNamespace(make=make)
