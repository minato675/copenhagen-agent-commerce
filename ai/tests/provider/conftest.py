"""S15P21A207-299: reuse established offline engine cases without duplicating a dataset."""

from types import SimpleNamespace

import pytest

from tests.learning.personalization.replay.conftest import replay_cases
from tests.learning.personalization.replay.engine.conftest import engine_cases
from tests.learning.personalization.replay.engine.analysis.conftest import analysis_cases
from tests.learning.personalization.replay.engine.analysis.finalization.conftest import final_cases

from copenhagen_fds.contracts.provider_evaluation import (
    ProviderEvaluationRequest, ProviderEvaluationResponse, evaluation_configuration_hash,
)
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.engine_finalization import finalize_engine_execution


@pytest.fixture
def evaluation_case(final_cases):
    def make(**options):
        case = final_cases.execute(**options)
        native = finalize_engine_execution(case.execution, budget=case.budget)
        engine = case.request
        request = ProviderEvaluationRequest(operation="EVALUATE",
            call=dict(request_id="provider-request", trace_id=engine.trace_id,
                      sent_at=engine.feature_input.evaluation.evaluated_at),
            configuration=dict(configuration_id="synthetic-config", version="v1",
                               content_sha256=evaluation_configuration_hash(engine)), evaluation=engine)
        behavior = native.execution.behavior.evaluation
        decision = native.policy_result.decision
        features = []
        for row, readiness in zip(behavior.result.features, behavior.components.transition.readiness.features):
            features.append(dict(feature_id=row.combined.feature_id, status=row.combined.status, score=row.combined.score, stage=row.stage,
                cold_weight=row.cold_weight, personal_weight=row.personal_weight, reasons=row.stage_reasons,
                readiness=[window.model_dump(exclude={"evidence"}) for window in readiness.windows]))
        versions = dict(feature_schema=engine.feature_input.evaluation.feature_schema_version,
            scoring=engine.personalization.score_scale_version, personalization=engine.personalization.version,
            risk_policy=engine.risk_policy.version, requested_model=engine.execution.requested_model,
            extraction_prompt=engine.execution.extraction_prompt_version,
            comparison_prompt=engine.execution.comparison_prompt_version,
            extraction_model=native.execution.extraction.metadata.model_version,
            comparison_model=native.execution.comparison.metadata.model_version,
            profiles=[p.state.published.profile.reference for p in (engine.personal, engine.population) if p is not None])
        response = ProviderEvaluationResponse(operation="EVALUATE", call=request.call,
            request_sha256=personalization_hash(request), engine_reference=native.execution.reference,
            record_sha256=personalization_hash(native), completed_at=native.completed_at, outcome=native.outcome,
            behavior_score=decision.behavior_score, score_coverage=decision.score_coverage, groups=decision.groups,
            features=features, reasons=decision.reasons, stages=(*native.execution.stages, native.policy_stage), versions=versions)
        response.validate_for_request(request)
        return SimpleNamespace(request=request, response=response, native=native)
    return make
