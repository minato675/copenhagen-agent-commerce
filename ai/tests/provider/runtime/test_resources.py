"""S15P21A207-300: trusted GMS configuration binding without SDK/network calls."""

import pytest
from pydantic import SecretStr

from copenhagen_fds.contracts.provider_evaluation import evaluation_configuration_hash
from copenhagen_fds.openai_settings import OpenAISettings
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.access import ServiceIdentity
from copenhagen_fds.provider_runtime.boundary import AuthorizedRequest
from copenhagen_fds.provider_runtime.resources import GmsResourceFactory


def configured(case):
    data = case.request.model_dump()
    data["evaluation"]["execution"]["gateway"] = "GMS"
    engine = type(case.request.evaluation).model_validate(data["evaluation"])
    data["configuration"]["content_sha256"] = evaluation_configuration_hash(engine)
    request = type(case.request).model_validate(data)
    execution = engine.execution
    settings = OpenAISettings(mode="LIVE", gms_key=SecretStr("offline-test-only-gms-placeholder"),
        openai_model=execution.requested_model, openai_timeout_seconds=execution.provider_timeout_seconds,
        evaluation_timeout_seconds=execution.total_timeout_seconds)
    return AuthorizedRequest(ServiceIdentity("test", ()), request), settings


def test_factory_owns_fresh_adapters_and_uses_only_trusted_settings(evaluation_case, monkeypatch):
    authorized, settings = configured(evaluation_case())
    constructed = []

    class Adapter:
        def __init__(self, supplied):
            constructed.append(supplied)

    monkeypatch.setattr("copenhagen_fds.provider_runtime.resources.OpenAIAnalysisAdapter", Adapter)
    factory = GmsResourceFactory(settings)
    assert factory(authorized) is not factory(authorized)
    assert constructed == [settings, settings]
    assert "offline-test-only-gms-placeholder" not in repr(factory)


def test_mismatched_model_is_rejected_before_adapter_construction(evaluation_case, monkeypatch):
    authorized, settings = configured(evaluation_case())
    settings = type(settings).model_validate({**settings.model_dump(), "openai_model": "different-server-model"})
    monkeypatch.setattr("copenhagen_fds.provider_runtime.resources.OpenAIAnalysisAdapter",
                        lambda _: pytest.fail("mismatch must not allocate an adapter"))
    with pytest.raises(ProviderContractError) as error:
        GmsResourceFactory(settings)(authorized)
    assert error.value.error.code == "CONFIGURATION_MISMATCH"


def test_profile_operation_allocates_no_gms_resource(profile_case, evaluation_case, monkeypatch):
    _, settings = configured(evaluation_case())
    authorized = AuthorizedRequest(ServiceIdentity("test", ()), profile_case.create().request)
    monkeypatch.setattr("copenhagen_fds.provider_runtime.resources.OpenAIAnalysisAdapter",
                        lambda _: pytest.fail("profile operation must not allocate GMS"))
    assert GmsResourceFactory(settings)(authorized) is None
