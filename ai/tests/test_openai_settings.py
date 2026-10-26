"""OpenAI-only configuration never needs Sift credentials or a risk policy."""

from pathlib import Path
import traceback

import pytest

from copenhagen_fds.openai_settings import load_openai_settings
from copenhagen_fds.settings import SettingsError, load_settings


@pytest.fixture
def openai_environment():
    return {
        "FDS_MODE": "LIVE", "GMS_KEY": "synthetic-gms-key",
        "FDS_OPENAI_TIMEOUT_SECONDS": "2", "FDS_EVALUATION_TIMEOUT_SECONDS": "5",
    }


def test_live_openai_without_dummy_sift_or_policy(openai_environment):
    settings = load_openai_settings(openai_environment)
    assert settings.openai_model == "gpt-5.4-mini"
    assert settings.openai_timeout_seconds == 2
    assert settings.evaluation_timeout_seconds == 5
    assert settings.openai_base_url == "https://gms.ssafy.io/gmsapi/api.openai.com/v1"
    assert settings.gms_key.get_secret_value() == "synthetic-gms-key"
    assert "synthetic-gms-key" not in repr(settings) + settings.model_dump_json()
    assert load_settings(openai_environment).policy_version is None


def test_mock_still_requires_time_limits_but_no_key(openai_environment):
    openai_environment.update(FDS_MODE="MOCK", GMS_KEY="")
    assert load_openai_settings(openai_environment).gms_key is None
    with pytest.raises(SettingsError):
        load_openai_settings({})


@pytest.mark.parametrize("name", ["GMS_KEY", "FDS_OPENAI_TIMEOUT_SECONDS", "FDS_EVALUATION_TIMEOUT_SECONDS"])
def test_live_missing_required_connection_fields(openai_environment, name):
    del openai_environment[name]
    with pytest.raises(SettingsError, match=name):
        load_openai_settings(openai_environment)


@pytest.mark.parametrize("value", ["0", "-1", "nan", "inf", "wrong", "", True, None])
@pytest.mark.parametrize("name", ["FDS_OPENAI_TIMEOUT_SECONDS", "FDS_EVALUATION_TIMEOUT_SECONDS"])
def test_invalid_time_limits_are_redacted(openai_environment, name, value):
    openai_environment[name] = value
    with pytest.raises(SettingsError) as caught:
        load_openai_settings(openai_environment)
    assert caught.value.__context__ is None
    assert caught.value.__cause__ is None


def test_provider_timeout_cannot_exceed_total(openai_environment):
    openai_environment["FDS_OPENAI_TIMEOUT_SECONDS"] = "6"
    with pytest.raises(SettingsError, match="provider timeout"):
        load_openai_settings(openai_environment)


@pytest.mark.parametrize("change", [
    {"GMS_KEY": "private key value"}, {"gms_key": "private-value"},
    {"FDS_OPENAI_API_KEY": "private-value"}, {"FDS_SECRET_TYPO": "private-value"},
    {"FDS_OPENAI_MODEL": ""},
])
def test_shared_validation_and_safe_exception_chain(openai_environment, change):
    with pytest.raises(SettingsError) as caught:
        load_openai_settings({**openai_environment, **change})
    assert "private" not in "".join(traceback.format_exception(caught.value))
    assert caught.value.__context__ is None


def test_policy_metadata_is_outside_openai_scope(openai_environment):
    settings = load_openai_settings({
        **openai_environment, "FDS_POLICY_VERSION": "test-policy-v1",
    })
    assert settings.openai_model == "gpt-5.4-mini"
    assert "policy_version" not in settings.model_dump()


@pytest.mark.parametrize("value", ["", "private-legacy-key"])
def test_removed_sift_settings_are_not_silently_ignored_by_openai_loader(openai_environment, value):
    with pytest.raises(SettingsError, match="remove legacy Sift") as caught:
        load_openai_settings({**openai_environment, "FDS_SIFT_API_KEY": value})
    assert caught.value.fields == ("FDS_SIFT_API_KEY",)
    assert "private-legacy-key" not in "".join(traceback.format_exception(caught.value))
    assert caught.value.__context__ is None


def test_removed_sift_settings_in_explicit_dotenv_require_removal(openai_environment, tmp_path):
    path = tmp_path / ".env"
    path.write_text("FDS_SIFT_API_KEY=private-legacy-key\n", encoding="utf-8")
    with pytest.raises(SettingsError, match="remove legacy Sift") as caught:
        load_openai_settings(openai_environment, env_file=path)
    assert "private-legacy-key" not in "".join(traceback.format_exception(caught.value))


def test_dotenv_is_explicit_with_mapping_precedence_and_no_interpolation(tmp_path, monkeypatch):
    path = tmp_path / ".env"
    path.write_text(
        'GMS_KEY="synthetic-${UNRELATED_SECRET}"\nFDS_MODE=LIVE\n'
        'FDS_OPENAI_TIMEOUT_SECONDS=2\nFDS_EVALUATION_TIMEOUT_SECONDS=5\n', encoding="utf-8-sig",
    )
    monkeypatch.setenv("UNRELATED_SECRET", "private-process-value")
    monkeypatch.setenv("GMS_KEY", "private-process-key")
    settings = load_openai_settings({}, env_file=path)
    assert settings.gms_key.get_secret_value() == "synthetic-${UNRELATED_SECRET}"
    assert load_openai_settings({"FDS_OPENAI_MODEL": "explicit-model"}, env_file=path).openai_model == "explicit-model"
    assert load_openai_settings(env_file=path).gms_key.get_secret_value() == "private-process-key"
    assert "private-process-value" not in path.read_text(encoding="utf-8-sig")


def test_loader_does_not_search_files(openai_environment, monkeypatch):
    def unexpected_file(*args, **kwargs):
        pytest.fail("no environment file was requested")
    monkeypatch.setattr(Path, "open", unexpected_file)
    assert load_openai_settings(openai_environment).mode == "LIVE"


def test_explicit_missing_file_fails_without_path_or_exception_chain(tmp_path):
    with pytest.raises(SettingsError) as caught:
        load_openai_settings({}, env_file=tmp_path / "private-filename")
    assert "private-filename" not in str(caught.value)
    assert caught.value.__context__ is None
