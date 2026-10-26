"""S15P21A207-299: all request/response schemas and offline example round trips."""

import json
from pathlib import Path
from uuid import uuid4

from copenhagen_fds.contracts.provider_errors import ProviderError, ProviderInputLimits
from copenhagen_fds.provider_contracts import CONTRACT_MODELS, validate_provider_json


def test_contract_examples(evaluation_case, profile_case, request, tmp_path):
    cases = {"evaluation": evaluation_case(), "insufficient": evaluation_case(missing=True),
             "create-personal": profile_case.create(), "create-population": profile_case.create(population_scope=True),
             "create-empty": profile_case.create(empty=True), "update": profile_case.update(),
             "remove": profile_case.update(remove=True)}
    limits = ProviderInputLimits(max_body_bytes=4_000_000, max_depth=64, max_nodes=200_000,
        max_collection_items=20_000, max_string_chars=20_000, max_integer_abs=10**15, max_float_abs=1e15)
    configured = request.config.getoption("--provider-examples")
    if configured:
        folder = Path(configured).resolve()
        allowed = Path(__file__).resolve().parents[3] / "results/evaluation"
        assert folder != allowed.resolve() and folder.is_relative_to(allowed.resolve()), "export must be a subdirectory of ai/results/evaluation"
    else:
        folder = tmp_path
    folder.mkdir(parents=True, exist_ok=True)
    index = {}
    for name, case in cases.items():
        for kind in ("request", "response"):
            model = getattr(case, kind)
            decoded = validate_provider_json(type(model), model.model_dump_json().encode(), limits=limits)
            assert decoded == model
            if kind == "response":
                decoded.validate_for_request(case.request)
            file = f"{name}-{kind}.json"
            (folder / file).write_text(model.model_dump_json(indent=2) + "\n", encoding="utf-8")
            index[file] = type(model).__name__
    error = ProviderError(error_id=uuid4(), code="VERSION_CONFLICT")
    assert validate_provider_json(ProviderError, error.model_dump_json().encode(), limits=limits) == error
    (folder / "error.json").write_text(error.model_dump_json(indent=2) + "\n", encoding="utf-8")
    index["error.json"] = "ProviderError"
    for name, model in CONTRACT_MODELS.items():
        (folder / f"{name}.schema.json").write_text(json.dumps(model.model_json_schema(), indent=2) + "\n", encoding="utf-8")
    (folder / "index.json").write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    assert set(index.values()) == set(CONTRACT_MODELS)
