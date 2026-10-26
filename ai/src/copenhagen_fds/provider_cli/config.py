"""Explicit synthetic deployment configuration, independent of submitted JSON."""

from typing import Annotated, Literal

from pydantic import Field, SecretStr

from copenhagen_fds.contracts.provider_errors import ProviderInputLimits
from copenhagen_fds.contracts.provider_evaluation import ConfigurationReference
from copenhagen_fds.contracts.values import Array, Identifier, InputModel
from copenhagen_fds.provider_runtime.access import (
    OperationGrant, ResourceScope, ServiceIdentity, ServiceTokenAuthenticator, TokenBinding,
)
from copenhagen_fds.provider_runtime.boundary import ProviderBoundary, TrustedConfiguration


Operation = Literal["EVALUATE", "CREATE_PERSONAL_PROFILE", "CREATE_POPULATION_PROFILE", "UPDATE_PROFILE"]
Seconds = Annotated[float, Field(gt=0, allow_inf_nan=False)]
# Public demonstration value only; no environment lookup or actual credential export.
DEMO_CREDENTIAL = "EXAMPLE_ONLY_NOT_A_SERVICE_SECRET"


class DemoGrant(InputModel):
    operation: Operation
    user_ids: Array[Identifier]
    profile_ids: Array[Identifier]
    cohort_ids: Array[Identifier]


class DemoConfiguration(InputModel):
    operation: Operation
    reference: ConfigurationReference


class DemoServer(InputModel):
    schema_version: Literal["fds-provider-demo-server-v1"] = "fds-provider-demo-server-v1"
    credential: Literal["EXAMPLE_ONLY_NOT_A_SERVICE_SECRET"]
    service_id: Identifier
    grants: Array[DemoGrant]
    configurations: Array[DemoConfiguration]
    limits: ProviderInputLimits
    timeout_seconds: Seconds
    cleanup_timeout_seconds: Seconds
    max_response_bytes: Annotated[int, Field(gt=0)]

    def boundary(self):
        grants = tuple(OperationGrant(g.operation, ResourceScope(
            frozenset(g.user_ids), frozenset(g.profile_ids), frozenset(g.cohort_ids))) for g in self.grants)
        auth = ServiceTokenAuthenticator((TokenBinding(SecretStr(self.credential),
            ServiceIdentity(self.service_id, grants)),))
        return ProviderBoundary(authenticator=auth, limits=self.limits,
            configurations=tuple(TrustedConfiguration(c.operation, c.reference) for c in self.configurations))


def demo_server(operation, reference, scope):
    """Preparation helper, not called by the execution path to authorize requests."""
    return DemoServer(credential=DEMO_CREDENTIAL, service_id="synthetic-be",
        grants=[dict(operation=operation, **{name: sorted(getattr(scope, name))
            for name in ("user_ids", "profile_ids", "cohort_ids")})],
        configurations=[dict(operation=operation, reference=reference)],
        limits=dict(max_body_bytes=2_000_000, max_depth=64, max_nodes=100_000,
                    max_collection_items=10_000, max_string_chars=50_000,
                    max_integer_abs=10**15, max_float_abs=1e15),
        timeout_seconds=60.0, cleanup_timeout_seconds=1.0, max_response_bytes=2_000_000)
