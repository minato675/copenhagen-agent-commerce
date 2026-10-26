"""S15P21A207-302: inspectable request/response examples from actual offline execution."""

import json
from pathlib import Path

from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest, ProviderProfileCreateResponse


def test_executed_profile_creation_examples(creation_case, policy, run_async, request, tmp_path):
    configured = request.config.getoption("--provider-profile-examples")
    folder = Path(configured).resolve() if configured else tmp_path
    if configured:
        allowed = (Path(__file__).resolve().parents[4] / "results/evaluation").resolve()
        assert folder != allowed and folder.is_relative_to(allowed)
    folder.mkdir(parents=True, exist_ok=True)
    index = {}
    for name, options, status, included in (
        ("personal", {"statistics": {"min_samples": 2}}, "READY", 2),
        ("population", {"population_scope": True}, "READY", 2),
        ("sparse", {"rows": (policy.row("sparse"),)}, "INSUFFICIENT_SAMPLES", 1),
        ("empty", {"rows": ()}, "EMPTY", 0),
        ("held", {"rows": (*policy.baseline(), policy.row("abrupt", amount=1_000_000))}, "INSUFFICIENT_SAMPLES", 3),
    ):
        case = creation_case(**options)
        response = ProviderProfileCreateResponse.model_validate_json(run_async(case.execute())).validate_for_request(case.request)
        assert response.state.published.long_term.status == status
        assert response.state.published.long_term.included_count == included
        assert response.storage_status == "NOT_PERSISTED"
        encoded = case.request.model_dump_json(indent=2)
        assert ProviderProfileCreateRequest.model_validate_json(encoded) == case.request
        (folder / f"{name}-request.json").write_text(encoded + "\n", encoding="utf-8")
        (folder / f"{name}-response.json").write_text(response.model_dump_json(indent=2) + "\n", encoding="utf-8")
        index[name] = dict(request=f"{name}-request.json", response=f"{name}-response.json",
            published_status=status, included_count=included, simulated_completed_at=response.completed_at.isoformat(),
            storage_status=response.storage_status, gms_calls=0)
    (folder / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
