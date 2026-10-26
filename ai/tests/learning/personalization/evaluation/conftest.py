from types import SimpleNamespace

import pytest

from copenhagen_fds.contracts.personal_amount import AMOUNT_SCORE_VERSION
from copenhagen_fds.contracts.personal_components import BLEND_SCALE_VERSION
from copenhagen_fds.contracts.personal_frequency import FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION
from copenhagen_fds.contracts.personal_readiness import READINESS_VERSION
from copenhagen_fds.contracts.personal_time import TIME_PERIODS, TIME_SCORE_VERSION
from copenhagen_fds.contracts.personalization_inputs import PersonalizedScoringInput


@pytest.fixture
def evaluation_cases(readiness_cases):
    def configured(supplied):
        data = supplied.model_dump()
        data["config"].update(score_scale_version=BLEND_SCALE_VERSION, transition_policy_version=READINESS_VERSION)
        versions = {"amount_minor": AMOUNT_SCORE_VERSION,
                    **dict.fromkeys(FREQUENCY_FEATURES, FREQUENCY_SCORE_VERSION),
                    **dict.fromkeys(TIME_PERIODS, TIME_SCORE_VERSION)}
        for feature in data["config"]["features"]:
            feature["score_version"] = versions[feature["feature_id"]]
        return PersonalizedScoringInput.model_validate(data)

    def request(*, values=None, cold_parameters=None, **kwargs):
        data = configured(readiness_cases.request(**kwargs)).model_dump()
        for raw in data["cold"]["raw_features"]["features"]:
            if raw["feature_id"] in (values or {}):
                raw["value"] = values[raw["feature_id"]]
        for reference in data["cold"]["selection"]["features"]:
            if reference["feature_id"] in (cold_parameters or {}):
                reference["parameter"].update(cold_parameters[reference["feature_id"]])
        return PersonalizedScoringInput.model_validate(data)

    return SimpleNamespace(request=request, configured=configured, publication=readiness_cases.publication,
                           cutoff=readiness_cases.cutoff)
