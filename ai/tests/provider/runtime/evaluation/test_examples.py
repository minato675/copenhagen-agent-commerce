"""S15P21A207-301: user-inspectable JSON from executed requests, never canned results."""

import json
from pathlib import Path

from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest, ProviderEvaluationResponse


def test_executed_evaluation_examples(provider_case, run_async, request, tmp_path):
    configured = request.config.getoption("--provider-evaluation-examples")
    folder = Path(configured).resolve() if configured else tmp_path
    if configured:
        allowed = Path(__file__).resolve().parents[4] / "results/evaluation"
        assert folder != allowed.resolve() and folder.is_relative_to(allowed.resolve())
    folder.mkdir(parents=True, exist_ok=True)
    index = {}
    for name, options, status in (
        ("normal", {}, "SUCCESS"), ("insufficient", {"missing": True}, "INCOMPLETE"),
        ("gpt-failed", {"signal": "EXTRACTION_FAILED"}, "FAILED"),
    ):
        case = provider_case(**options)
        response = ProviderEvaluationResponse.model_validate_json(run_async(case.execute()))
        response.validate_for_request(case.request)
        assert response.outcome.status == status and case.adapter.closed == 1
        encoded = case.request.model_dump_json(indent=2)
        assert ProviderEvaluationRequest.model_validate_json(encoded) == case.request
        (folder / f"{name}-request.json").write_text(encoded + "\n", encoding="utf-8")
        (folder / f"{name}-response.json").write_text(response.model_dump_json(indent=2) + "\n", encoding="utf-8")
        index[name] = dict(request=f"{name}-request.json", response=f"{name}-response.json",
                           observed_status=response.outcome.status, completion_calls=len(case.adapter.calls))
    (folder / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
