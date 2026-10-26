"""Build a candidate from a validated full replay without admitting or storing it."""

from copenhagen_fds.contracts.personal_profiles import PersonalProfileRequest, PersonalProfileSnapshot
from copenhagen_fds.contracts.population_profiles import PopulationProfileSnapshot
from copenhagen_fds.contracts.provider_profiles import ProviderProfileCreateRequest
from copenhagen_fds.personal_profiles import build_personal_profile
from copenhagen_fds.population_profiles import build_population_profile


def build_profile_candidate(
    request: ProviderProfileCreateRequest,
) -> PersonalProfileSnapshot | PopulationProfileSnapshot:
    """Use existing statistics after rejecting rows the standalone builders may filter.

    This local calculation requires ProviderBoundary authorization at service entry.
    Eligibility and review evidence must already come from the learning replay.
    """
    request = ProviderProfileCreateRequest.model_validate(request.model_dump())
    samples = tuple(row.sample for row in request.replay.observations)
    builder = build_personal_profile if isinstance(request.profile, PersonalProfileRequest) else build_population_profile
    return builder(request.profile, samples)
