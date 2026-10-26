"""Read explicit candidate values and select Cold references without fitting or I/O."""

import json

from pydantic import ValidationError

from copenhagen_fds.contracts.baselines import (
    BaselineIssue, BaselineSelection, BaselineSelectionRequest,
    InitialPolicy, PopulationBaseline, SelectedFeatureBasis, parameter_error,
)
from copenhagen_fds.contracts.behavior import ScoringBasis
from copenhagen_fds.contracts.features import FEATURE_SPECS


def _unique_json(pairs):
    data = {}
    for key, value in pairs:
        if key in data:
            raise ValueError("duplicate JSON key")
        data[key] = value
    return data


def _parse(candidate, model):
    if candidate is None:
        return None, "MISSING"
    if isinstance(candidate, str):
        try:
            candidate = json.loads(candidate, object_pairs_hook=_unique_json)
        except (ValueError, RecursionError):
            return None, "INVALID_FORMAT"
    if isinstance(candidate, model):
        candidate = candidate.model_dump(mode="json")
    if isinstance(candidate, dict) and isinstance(candidate.get("format_version"), str):
        if candidate["format_version"] != "cold-reference-v1":
            return None, "VERSION_MISMATCH"
    try:
        return model.model_validate(candidate), None
    except ValidationError:
        return None, "INVALID_CONFIG"


def _population_error(population: PopulationBaseline, request: BaselineSelectionRequest):
    profile = population.profile
    context = request.evaluation
    if profile.reference.scope != "POPULATION":
        return "REFERENCE_MISMATCH"
    if profile.feature_schema_version != context.feature_schema_version:
        return "VERSION_MISMATCH"
    if profile.currency != context.request.order.total.currency:
        return "CURRENCY_MISMATCH"
    expected = request.population_reference
    if expected is None:
        return "REFERENCE_MISMATCH"
    if profile.reference.version != expected.version:
        return "VERSION_MISMATCH"
    if profile.reference != expected:
        return "REFERENCE_MISMATCH"
    if max(profile.as_of, profile.generated_at, population.available_at) > context.evaluated_at:
        return "FUTURE"
    if profile.valid_until <= context.evaluated_at:
        return "EXPIRED"
    supplied = {p.reference.profile_id: p for p in context.profiles}
    if profile.reference.profile_id in supplied and supplied[profile.reference.profile_id] != profile:
        return "REFERENCE_MISMATCH"
    error = parameter_error(population.parameters)
    if error:
        return error
    observed = {o.feature_id: o for o in profile.observations}
    for parameter in population.parameters:
        observation = observed.get(parameter.feature_id)
        if observation is None or observation.valid_sample_count == 0:
            return "INSUFFICIENT_SAMPLES"
    return None


def _policy_error(policy: InitialPolicy, request: BaselineSelectionRequest):
    context = request.evaluation
    if policy.feature_schema_version != context.feature_schema_version or policy.version != request.policy_version:
        return "VERSION_MISMATCH"
    if policy.policy_id != request.policy_id:
        return "REFERENCE_MISMATCH"
    if policy.currency != context.request.order.total.currency:
        return "CURRENCY_MISMATCH"
    if max(policy.generated_at, policy.available_at, policy.valid_from) > context.evaluated_at:
        return "FUTURE"
    if policy.valid_until <= context.evaluated_at:
        return "EXPIRED"
    return parameter_error(policy.parameters)


def select_cold_baselines(
    request: BaselineSelectionRequest, *, population=None, policy=None,
) -> BaselineSelection:
    """Select per feature: valid population, then explicitly configured policy.

    An invalid supplied population blocks fallback so configuration errors cannot
    silently become plausible results. Missing population/features allow fallback.
    """
    population, population_error = _parse(population, PopulationBaseline)
    policy, policy_error = _parse(policy, InitialPolicy)
    if population_error is None:
        population_error = _population_error(population, request)
    if policy_error is None:
        policy_error = _policy_error(policy, request)
    population_parameters = {} if population is None else {p.feature_id: p for p in population.parameters}
    policy_parameters = {} if policy is None else {p.feature_id: p for p in policy.parameters}
    features = []
    used_population = False
    for feature_id in FEATURE_SPECS:
        if feature_id not in request.evaluation.requested_features:
            continue
        issues = []
        if population_error is None and feature_id in population_parameters:
            profile = population.profile
            features.append(SelectedFeatureBasis(
                feature_id=feature_id, status="SELECTED", parameter=population_parameters[feature_id],
                basis=ScoringBasis(kind="POPULATION_PROFILE", profile=profile.reference),
                origin=profile.origin.value, source_reference=profile.source_reference,
                validation_status=population.validation_status, generated_at=profile.generated_at,
                available_at=population.available_at, valid_from=profile.generated_at,
                valid_until=profile.valid_until,
            ))
            used_population = True
            continue
        issues.append(BaselineIssue(source="POPULATION", code=population_error or "FEATURE_MISSING"))
        if population_error not in (None, "MISSING"):
            features.append(SelectedFeatureBasis(feature_id=feature_id, status="UNAVAILABLE", issues=issues))
            continue
        if policy_error is None and feature_id in policy_parameters:
            features.append(SelectedFeatureBasis(
                feature_id=feature_id, status="SELECTED", parameter=policy_parameters[feature_id],
                basis=ScoringBasis(kind="INITIAL_POLICY", policy_version=policy.version),
                origin=policy.origin, source_reference=policy.source_reference,
                validation_status=policy.validation_status, policy_id=policy.policy_id,
                generated_at=policy.generated_at, available_at=policy.available_at,
                valid_from=policy.valid_from, valid_until=policy.valid_until, issues=issues,
            ))
            continue
        issues.append(BaselineIssue(source="INITIAL_POLICY", code=policy_error or "FEATURE_MISSING"))
        features.append(SelectedFeatureBasis(feature_id=feature_id, status="UNAVAILABLE", issues=issues))
    return BaselineSelection(
        target=request.evaluation.target, evaluated_at=request.evaluation.evaluated_at,
        currency=request.evaluation.request.order.total.currency,
        features=features, profiles=[population.profile] if used_population else [],
    ).validate_for_request(request)
