"""S15P21A207-300: reject unauthenticated, over-scoped or reconfigured inputs."""

from dataclasses import replace
import json

import pytest
from pydantic import SecretStr

from copenhagen_fds.contracts.provider_evaluation import evaluation_configuration_hash
from copenhagen_fds.provider_contracts import ProviderContractError
from copenhagen_fds.provider_runtime.access import ResourceScope, ServiceIdentity, OperationGrant, ServiceTokenAuthenticator, TokenBinding
from copenhagen_fds.provider_runtime.boundary import request_scope


def prepare(case, **kwargs):
    return case.boundary.prepare(case.case.request.operation, case.raw,
                                credential=kwargs.pop("credential", case.credential), **kwargs)


@pytest.mark.parametrize("kind", ["evaluation", "personal", "population", "update", "population_update"])
def test_supported_operations_preserve_authorized_input(kind, evaluation_case, profile_case, runtime_case):
    case = (evaluation_case() if kind == "evaluation" else
            profile_case.update(population_scope=kind == "population_update") if "update" in kind else
            profile_case.create(population_scope=kind == "population"))
    fixture = runtime_case(case)
    result = prepare(fixture)
    assert result.request == case.request
    assert result.identity == fixture.identity
    assert case.request.call.trace_id not in repr(result)


@pytest.mark.parametrize("credential", [None, "test-service", SecretStr("wrong-test-only-token"), SecretStr("\ud800")])
def test_body_or_identity_string_cannot_authenticate(credential, evaluation_case, runtime_case):
    fixture = runtime_case(evaluation_case())
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture, credential=credential)
    assert error.value.error.code == "UNAUTHENTICATED"
    assert "wrong-test-only-token" not in str(error.value)


def test_operation_permission_precedes_body_validation(evaluation_case, runtime_case):
    fixture = runtime_case(evaluation_case(), operation="UPDATE_PROFILE")
    fixture.raw = b"not-json-with-private-input"
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "FORBIDDEN"


@pytest.mark.parametrize("dimension", ["user_ids", "profile_ids", "cohort_ids"])
def test_population_permission_covers_every_member_and_resource(dimension, profile_case, runtime_case):
    case = profile_case.create(population_scope=True)
    expected = case.request.profile
    scope = request_scope(case.request)
    assert set(expected.cohort.member_user_ids) <= scope.user_ids
    assert expected.reference.profile_id in scope.profile_ids
    assert expected.reference.cohort_id in scope.cohort_ids
    values = getattr(scope, dimension)
    denied = replace(scope, **{dimension: frozenset(sorted(values)[1:])})
    fixture = runtime_case(case, scope=denied)
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "FORBIDDEN"


def test_empty_replay_still_requires_previous_state_subjects(profile_case, runtime_case):
    case = profile_case.update(remove=True, population_scope=True)
    assert not case.request.replay.observations
    previous = case.request.state.candidate.snapshot
    scope = request_scope(case.request)
    assert set(previous.cohort.member_user_ids) <= scope.user_ids
    fixture = runtime_case(case, scope=replace(scope, user_ids=frozenset()))
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "FORBIDDEN"


def test_partial_grants_do_not_combine_or_cross_operations():
    first = ResourceScope(user_ids=frozenset({"a"}))
    second = ResourceScope(user_ids=frozenset({"b"}))
    identity = ServiceIdentity("test", (OperationGrant("EVALUATE", first),
        OperationGrant("EVALUATE", second), OperationGrant("UPDATE_PROFILE", first)))
    assert identity.allows("EVALUATE", first)
    assert not identity.allows("EVALUATE", ResourceScope(user_ids=frozenset({"a", "b"})))
    assert not identity.allows("UPDATE_PROFILE", second)


def test_configuration_claim_is_not_its_own_authority(evaluation_case, runtime_case):
    fixture = runtime_case(evaluation_case())
    data = fixture.case.request.model_dump()
    data["evaluation"]["execution"]["requested_model"] = "unapproved-model"
    engine = type(fixture.case.request.evaluation).model_validate(data["evaluation"])
    data["configuration"]["content_sha256"] = evaluation_configuration_hash(engine)
    forged = type(fixture.case.request).model_validate(data)
    fixture.raw = forged.model_dump_json().encode()
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "CONFIGURATION_MISMATCH"


def test_absent_trusted_configuration_is_not_a_default(evaluation_case, runtime_case):
    fixture = runtime_case(evaluation_case(), configurations=())
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "CONFIGURATION_UNAVAILABLE"


def test_input_size_is_checked_before_dto_construction(evaluation_case, runtime_case):
    fixture = runtime_case(evaluation_case(), max_body_bytes=8)
    fixture.raw = b"private-input-that-exceeds-the-configured-size"
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "INPUT_LIMIT_EXCEEDED"
    assert "private-input" not in error.value.error.model_dump_json()


def test_credential_cannot_be_added_to_contract(evaluation_case, runtime_case):
    fixture = runtime_case(evaluation_case())
    data = json.loads(fixture.raw)
    data["gms_key"] = "not-a-real-key-private-sentinel"
    fixture.raw = json.dumps(data).encode()
    with pytest.raises(ProviderContractError) as error:
        prepare(fixture)
    assert error.value.error.code == "INVALID_CONTRACT"
    assert "private-sentinel" not in str(error.value)


def test_scopes_snapshot_mutable_deployment_collections():
    users = {"a"}
    scope = ResourceScope(user_ids=users)
    users.add("b")
    assert scope.user_ids == frozenset({"a"})


def test_ambiguous_tokens_are_rejected_and_secrets_have_no_repr():
    binding = TokenBinding(SecretStr("offline-token-not-for-deployment"), ServiceIdentity("test", ()))
    assert "offline-token" not in repr(binding)
    with pytest.raises(ValueError, match="unambiguous"):
        ServiceTokenAuthenticator((binding, binding))
