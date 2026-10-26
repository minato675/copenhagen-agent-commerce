import json

import pytest

from copenhagen_fds.api.service import load_service
from copenhagen_fds.api.settings import HttpSettingsError, load_http_settings


def test_absent_configuration_disables_evaluation():
    assert load_service({}) is None


@pytest.mark.parametrize("token", ["", "short", "x" * 40 + " "])
def test_bad_token_fails_without_reflecting_secrets(api_case, tmp_path, token):
    path = tmp_path / "server.json"
    path.write_text(api_case.settings.model_dump_json(), encoding="utf-8")
    with pytest.raises(HttpSettingsError) as error:
        load_http_settings({"AI_HTTP_CONFIG": str(path), "AI_HTTP_SERVICE_TOKEN": token})
    assert str(path) not in str(error.value)
    if token:
        assert token not in str(error.value)
    assert error.value.__cause__ is None


def test_config_cannot_embed_credentials(api_case, tmp_path):
    data = api_case.settings.model_dump(mode="json")
    data["token"] = "private-value"
    path = tmp_path / "server.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(HttpSettingsError):
        load_service({"AI_HTTP_CONFIG": str(path), "AI_HTTP_SERVICE_TOKEN": api_case.token})


def test_mock_mode_does_not_load_gms_environment(api_case, tmp_path):
    path = tmp_path / "server.json"
    path.write_text(api_case.settings.model_dump_json(), encoding="utf-8")
    service = load_service({"AI_HTTP_CONFIG": str(path), "AI_HTTP_SERVICE_TOKEN": api_case.token,
                           "GMS_KEY": "", "FDS_MODE": "INVALID"})
    assert service.settings.mode == "MOCK"


def test_gms_requires_explicit_live_settings(api_case, tmp_path):
    data = api_case.settings.model_dump(mode="json")
    data.update(mode="GMS", mock=None)
    path = tmp_path / "server.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(HttpSettingsError, match="runtime or GMS"):
        load_service({"AI_HTTP_CONFIG": str(path), "AI_HTTP_SERVICE_TOKEN": api_case.token})


def test_duplicate_trusted_configurations_fail_at_startup(api_case, tmp_path):
    data = api_case.settings.model_dump(mode="json")
    data["configurations"] *= 2
    path = tmp_path / "server.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(HttpSettingsError):
        load_service({"AI_HTTP_CONFIG": str(path), "AI_HTTP_SERVICE_TOKEN": api_case.token})
