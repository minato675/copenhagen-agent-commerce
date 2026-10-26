"""Explicit service credentials and operation-specific grants from trusted settings."""

from dataclasses import dataclass, field
from hmac import compare_digest

from pydantic import SecretStr

from copenhagen_fds.provider_contracts import ProviderContractError


OPERATIONS = frozenset({"EVALUATE", "CREATE_PERSONAL_PROFILE", "CREATE_POPULATION_PROFILE", "UPDATE_PROFILE"})


@dataclass(frozen=True)
class ResourceScope:
    user_ids: frozenset[str] = frozenset()
    profile_ids: frozenset[str] = frozenset()
    cohort_ids: frozenset[str] = frozenset()

    def __post_init__(self):
        for name in ("user_ids", "profile_ids", "cohort_ids"):
            values = getattr(self, name)
            if isinstance(values, str):
                raise ValueError("scope requires explicit nonempty identifiers")
            values = frozenset(values)
            if any(type(v) is not str or not v.strip() for v in values):
                raise ValueError("scope requires explicit nonempty identifiers")
            object.__setattr__(self, name, values)

    def covers(self, required: "ResourceScope") -> bool:
        return all(getattr(required, name) <= getattr(self, name)
                   for name in ("user_ids", "profile_ids", "cohort_ids"))


@dataclass(frozen=True)
class OperationGrant:
    operation: str
    scope: ResourceScope = field(repr=False)

    def __post_init__(self):
        if self.operation not in OPERATIONS or not isinstance(self.scope, ResourceScope):
            raise ValueError("grant requires a supported operation and explicit scope")


@dataclass(frozen=True)
class ServiceIdentity:
    service_id: str
    grants: tuple[OperationGrant, ...] = field(repr=False)

    def __post_init__(self):
        if type(self.service_id) is not str or not self.service_id.strip():
            raise ValueError("service identity is required")
        object.__setattr__(self, "grants", tuple(self.grants))
        if any(not isinstance(grant, OperationGrant) for grant in self.grants):
            raise ValueError("identity requires operation grants")

    def allows(self, operation: str, scope: ResourceScope) -> bool:
        # Do not combine partial grants into a broader cross-resource permission.
        return any(g.operation == operation and g.scope.covers(scope) for g in self.grants)


@dataclass(frozen=True)
class TokenBinding:
    token: SecretStr = field(repr=False)
    identity: ServiceIdentity

    def __post_init__(self):
        if not isinstance(self.token, SecretStr) or not self.token.get_secret_value().strip():
            raise ValueError("service token is required")
        if not isinstance(self.identity, ServiceIdentity):
            raise ValueError("verified service grants are required")


class ServiceTokenAuthenticator:
    """A deployment-supplied token registry, never built from request JSON.

    No environment lookup, default token or wildcard grant. Replace the registry
    on credential rotation; token issuance/transport remain deployment decisions.
    """

    def __init__(self, bindings: tuple[TokenBinding, ...]):
        self._bindings = tuple(bindings)
        if any(not isinstance(binding, TokenBinding) for binding in self._bindings):
            raise ValueError("invalid credential configuration")
        secrets = [b.token.get_secret_value() for b in self._bindings]
        if len(secrets) != len(set(secrets)):
            raise ValueError("service credentials must be unambiguous")

    def authenticate(self, token: SecretStr | None) -> ServiceIdentity:
        if not isinstance(token, SecretStr):
            raise ProviderContractError("UNAUTHENTICATED")
        try:
            supplied = token.get_secret_value().encode("utf-8")
        except UnicodeError:
            raise ProviderContractError("UNAUTHENTICATED") from None
        identity = None
        for binding in self._bindings:
            if compare_digest(supplied, binding.token.get_secret_value().encode("utf-8")):
                identity = binding.identity
        if identity is None:
            raise ProviderContractError("UNAUTHENTICATED")
        return identity
