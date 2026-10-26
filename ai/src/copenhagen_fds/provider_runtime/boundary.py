"""Authorize bounded, validated requests before invoking any business handler."""

from dataclasses import dataclass, field
from types import MappingProxyType

from pydantic import SecretStr

from copenhagen_fds.contracts.provider_errors import ProviderInputLimits
from copenhagen_fds.contracts.provider_evaluation import ConfigurationReference, ProviderEvaluationRequest
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest, ProviderProfileUpdateRequest
from copenhagen_fds.provider_contracts import ProviderContractError, validate_provider_json
from copenhagen_fds.provider_runtime.access import OPERATIONS, ResourceScope, ServiceIdentity, ServiceTokenAuthenticator


REQUEST_MODELS = MappingProxyType({
    "EVALUATE": ProviderEvaluationRequest,
    "CREATE_PERSONAL_PROFILE": ProviderProfileCreateRequest,
    "CREATE_POPULATION_PROFILE": ProviderProfileCreateRequest,
    "UPDATE_PROFILE": ProviderProfileUpdateRequest,
})
ProviderRequest = ProviderEvaluationRequest | ProviderProfileCreateRequest | ProviderProfileUpdateRequest


def request_scope(request: ProviderRequest) -> ResourceScope:
    """Include nested previous states, publications, members and replay subjects.

    Called only after typed validation. Free text is never interpreted as a scope.
    This checks permission, not the truth of supplied state or DB membership.
    """
    users, profiles, cohorts = set(), set(), set()
    names = {"user_id": users, "profile_id": profiles, "cohort_id": cohorts}
    stack = [request.model_dump(mode="python")]
    while stack:
        item = stack.pop()
        if isinstance(item, dict):
            for key, value in item.items():
                if key in names and isinstance(value, str):
                    names[key].add(value)
                if key == "member_user_ids":
                    users.update(value)
                stack.append(value)
        elif isinstance(item, (tuple, list)):
            stack.extend(item)
    return ResourceScope(frozenset(users), frozenset(profiles), frozenset(cohorts))


@dataclass(frozen=True)
class TrustedConfiguration:
    operation: str
    reference: ConfigurationReference

    def __post_init__(self):
        if self.operation not in OPERATIONS:
            raise ValueError("configuration requires a supported operation")
        object.__setattr__(self, "reference", ConfigurationReference.model_validate(self.reference.model_dump()))


@dataclass(frozen=True)
class AuthorizedRequest:
    identity: ServiceIdentity
    request: ProviderRequest = field(repr=False)


class ProviderBoundary:
    def __init__(self, *, authenticator: ServiceTokenAuthenticator, limits: ProviderInputLimits,
                 configurations: tuple[TrustedConfiguration, ...]):
        self._authenticator = authenticator
        self._limits = ProviderInputLimits.model_validate(limits.model_dump())
        configured = {}
        for item in configurations:
            reference = ConfigurationReference.model_validate(item.reference.model_dump())
            key = (item.operation, reference.configuration_id, reference.version)
            if key in configured:
                raise ValueError("configuration references must be unambiguous")
            configured[key] = reference
        self._configurations = MappingProxyType(configured)

    def prepare(self, operation: str, raw: bytes, *, credential: SecretStr | None) -> AuthorizedRequest:
        identity = self._authenticator.authenticate(credential)
        if operation not in REQUEST_MODELS:
            raise ProviderContractError("INVALID_CONTRACT")
        if not any(grant.operation == operation for grant in identity.grants):
            raise ProviderContractError("FORBIDDEN")
        request = validate_provider_json(REQUEST_MODELS[operation], raw, limits=self._limits)
        if request.operation != operation:
            raise ProviderContractError("INVALID_CONTRACT")
        if not identity.allows(operation, request_scope(request)):
            raise ProviderContractError("FORBIDDEN")
        claim = request.configuration
        trusted = self._configurations.get((operation, claim.configuration_id, claim.version))
        if trusted is None:
            raise ProviderContractError("CONFIGURATION_UNAVAILABLE")
        if trusted != claim:
            raise ProviderContractError("CONFIGURATION_MISMATCH")
        return AuthorizedRequest(identity=identity, request=request)
