"""304: demonstration values cannot silently become production credentials/grants."""

import pytest
from pydantic import SecretStr, ValidationError

from copenhagen_fds.provider_cli.config import DEMO_CREDENTIAL, DemoServer, demo_server
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.access import ResourceScope
from copenhagen_fds.contracts.provider_evaluation import ConfigurationReference


def server():
    return demo_server("EVALUATE", ConfigurationReference(configuration_id="demo", version="v1",
        content_sha256="a" * 64), ResourceScope(user_ids=frozenset({"demo-user"})))


def test_demo_server_round_trip_and_independent_grants():
    configured = DemoServer.model_validate_json(server().model_dump_json())
    boundary = configured.boundary()
    with pytest.raises(ProviderContractError, match="UNAUTHENTICATED"):
        boundary.prepare("EVALUATE", b"{}", credential=None)
    with pytest.raises(ProviderContractError, match="FORBIDDEN"):
        boundary.prepare("UPDATE_PROFILE", b"{}", credential=SecretStr(DEMO_CREDENTIAL))


@pytest.mark.parametrize("change", [dict(credential="not-an-example-secret"), dict(timeout_seconds=0),
    dict(grants=[dict(operation="UNKNOWN", user_ids=[], profile_ids=[], cohort_ids=[])]), dict(expected={})])
def test_invalid_or_unexpected_configuration_rejected(change):
    with pytest.raises(ValidationError):
        DemoServer.model_validate({**server().model_dump(), **change})
