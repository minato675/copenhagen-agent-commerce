"""Offline provider runtime fixtures; credentials and limits are test-only."""

from types import SimpleNamespace

import pytest
from pydantic import SecretStr

from tests.provider.profiles.conftest import policy, population, profile_case
from tests.provider.test_errors import limits

from copenhagen_fds.provider_runtime.access import (
    OperationGrant, ServiceIdentity, ServiceTokenAuthenticator, TokenBinding,
)
from copenhagen_fds.provider_runtime.boundary import ProviderBoundary, TrustedConfiguration, request_scope


@pytest.fixture
def runtime_case(limits):
    def make(case, *, scope=None, operation=None, configurations=None, **limit_changes):
        request = case.request
        credential = SecretStr("offline-test-only-provider-credential")
        identity = ServiceIdentity("test-service", (OperationGrant(operation or request.operation,
            request_scope(request) if scope is None else scope),))
        binding = TokenBinding(credential, identity)
        boundary = ProviderBoundary(authenticator=ServiceTokenAuthenticator((binding,)),
            limits=type(limits).model_validate({**limits.model_dump(), **limit_changes}),
            configurations=((TrustedConfiguration(request.operation, request.configuration),)
                            if configurations is None else configurations))
        return SimpleNamespace(boundary=boundary, credential=credential, identity=identity,
                               case=case, raw=request.model_dump_json().encode())
    return make
