from pathlib import Path

import pytest

from copenhagen_fds.api.demo import prepare
from copenhagen_fds.api.settings import HttpSettings
from copenhagen_fds.contracts.provider_evaluation import ProviderEvaluationRequest


def test_demo_is_valid_secret_free_and_never_overwrites(tmp_path):
    output = tmp_path / "demo"
    source = Path(__file__).parents[2] / "examples/personal-replay-settings.json"
    prepare(source, output)
    settings = HttpSettings.model_validate_json((output / "server.json").read_bytes())
    request = ProviderEvaluationRequest.model_validate_json((output / "request.json").read_bytes())
    assert request.configuration in settings.configurations
    assert settings.mode == "MOCK"
    assert set(p.name for p in output.iterdir()) == {"server.json", "request.json"}
    assert "token" not in (output / "server.json").read_text(encoding="utf-8")
    before = (output / "request.json").read_bytes()
    with pytest.raises(ValueError):
        prepare(source, output)
    assert (output / "request.json").read_bytes() == before
