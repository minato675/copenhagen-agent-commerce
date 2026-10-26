"""S15P21A207-303: actual offline update, no-change, removal and conflict examples."""

import json
from pathlib import Path

import pytest

from copenhagen_fds.contracts.provider_errors import ProviderError
from copenhagen_fds.contracts.provider_profiles import ProviderProfileUpdateRequest, ProviderProfileUpdateResponse


def test_executed_update_examples(update_case, profile_case, policy, run_async, request, tmp_path):
    configured = request.config.getoption("--provider-update-examples")
    folder = Path(configured).resolve() if configured else tmp_path
    if configured:
        allowed = (Path(__file__).resolve().parents[4] / "results/evaluation").resolve()
        assert folder != allowed and folder.is_relative_to(allowed)
    folder.mkdir(parents=True, exist_ok=True)
    index = {}
    for name, expected in (("updated-personal", "UPDATED"), ("updated-population", "UPDATED"),
        ("unchanged", "UNCHANGED"), ("withdrawn", "UPDATED"), ("replay-required", "REPLAY_REQUIRED"),
        ("rejected", "REJECTED"), ("stale-version", "INVALID_CONTRACT")):
        state = profile_case.create(population_scope=name == "updated-population").state
        options = dict(state=state)
        if name.startswith("updated"):
            options["rows"] = (*state.observations, policy.row("new-confirmed"))
        elif name == "unchanged":
            options["cutoff"] = state.candidate.snapshot.profile.as_of
        elif name == "withdrawn":
            options["rows"] = ()
        elif name == "replay-required":
            options["configure"] = lambda data: data["update"].update(personal_config={"long_term_days": 91})
        elif name == "rejected":
            options["cutoff"] = state.candidate.snapshot.profile.as_of
            options["configure"] = lambda data: data["update"].update(generated_at=state.candidate.snapshot.profile.as_of)
        case = update_case(**options)
        if name == "stale-version":
            invalid = case.request.model_dump(mode="json")
            invalid["update"]["expected_policy_state_version"] = "stale-version"
            case.bound.raw = json.dumps(invalid).encode()
        raw_response = run_async(case.execute())
        if name == "stale-version":
            response = ProviderError.model_validate_json(raw_response)
            assert response.code == expected
            with pytest.raises(ValueError):
                ProviderProfileUpdateRequest.model_validate_json(case.bound.raw)
            details = dict(code=response.code)
        else:
            response = ProviderProfileUpdateResponse.model_validate_json(raw_response).validate_for_request(case.request)
            assert response.result.status == expected and response.storage_status == "NOT_PERSISTED"
            details = dict(status=response.result.status, issue=response.result.issue,
                transitions=[t.model_dump() for t in response.result.transitions],
                simulated_completed_at=response.completed_at.isoformat(), storage_status=response.storage_status)
        (folder / f"{name}-request.json").write_text(json.dumps(json.loads(case.bound.raw), indent=2) + "\n", encoding="utf-8")
        (folder / f"{name}-response.json").write_text(response.model_dump_json(indent=2) + "\n", encoding="utf-8")
        index[name] = dict(request=f"{name}-request.json", response=f"{name}-response.json", **details)
    (folder / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
