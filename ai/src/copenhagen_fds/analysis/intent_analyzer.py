"""An AI-only extraction/comparison entry point, without payment or risk policy."""

from typing import Self

from pydantic import model_validator, ValidationError

from copenhagen_fds.analysis.budget import AnalysisBudget
from copenhagen_fds.analysis.intent_comparator import IntentComparator, _comparison_without_call
from copenhagen_fds.analysis.intent_extractor import CompletionClient, IntentExtractor
from copenhagen_fds.contracts import (
    AnalysisFailure, IntentAnalysisRequest, IntentAnalysisResult, IntentComparisonResult, IntentExtractionResult,
)
from copenhagen_fds.contracts.values import InputModel


class IntentAnalysisRun(InputModel):
    """Keep both stages even when the common projection reports just one failure."""

    extraction: IntentExtractionResult
    comparison: IntentComparisonResult

    @model_validator(mode="after")
    def validate_stages(self) -> Self:
        first, second = self.extraction.metadata, self.comparison.metadata
        if first.target != second.target or first.trace_id != second.trace_id:
            raise ValueError("analysis stages must share target and trace")
        if self.extraction.telemetry.gateway != self.comparison.telemetry.gateway:
            raise ValueError("analysis stages must share their execution gateway")
        if self.extraction.status != "SUCCESS" and self.comparison.status != "NOT_RUN":
            raise ValueError("comparison requires successful extraction")
        if self.extraction.status == "SUCCESS" and self.comparison.status == "NOT_RUN":
            raise ValueError("successful extraction requires a comparison outcome")
        return self

    def validate_for_request(self, request: IntentAnalysisRequest) -> Self:
        self.extraction.validate_for_request(request)
        if self.extraction.status == "SUCCESS":
            mapped = None
            try:
                mapped = self.extraction.to_evaluation_request(request)
            except ValidationError:
                pass
            if mapped is None:
                if self.comparison.failure is None or self.comparison.failure.kind != "INVALID_INPUT":
                    raise ValueError("unmappable intent requires a comparison input failure")
            else:
                self.comparison.validate_for_request(mapped)
                if self.comparison.failure is not None and self.comparison.failure.kind == "INVALID_INPUT":
                    raise ValueError("valid comparison input cannot claim mapping failure")
        return self

    def to_common_result(self, request: IntentAnalysisRequest) -> IntentAnalysisResult:
        self.validate_for_request(request)
        # A skipped second stage must not hide the first stage's execution failure.
        stage = self.extraction if self.extraction.status != "SUCCESS" else self.comparison
        return IntentAnalysisResult(
            status=stage.status, metadata=stage.metadata, reasons=stage.reasons,
            comparison=None if self.comparison.payload is None else self.comparison.payload.comparison,
        )


class IntentAnalyzer:
    """Caller owns the adapter; every analysis starts or receives exactly one budget."""

    def __init__(self, adapter: CompletionClient):
        self._adapter = adapter
        self._extractor = IntentExtractor(adapter)
        self._comparator = IntentComparator(adapter)

    async def analyze(
        self, request: IntentAnalysisRequest, *, trace_id: str, budget: AnalysisBudget | None = None,
    ) -> IntentAnalysisRun:
        settings = self._adapter.settings
        shared = budget if budget is not None else AnalysisBudget.start(settings.evaluation_timeout_seconds)
        extraction = await self.extract(request, trace_id=trace_id, budget=shared)
        comparison = await self.compare_extraction(request, extraction, trace_id=trace_id, budget=shared)
        return IntentAnalysisRun(extraction=extraction, comparison=comparison).validate_for_request(request)

    async def extract(self, request: IntentAnalysisRequest, *, trace_id: str, budget: AnalysisBudget) -> IntentExtractionResult:
        """Expose the existing stage so an engine can retain it before comparison."""
        return await self._extractor.extract(request, trace_id=trace_id, budget=budget)

    async def compare_extraction(
        self, request: IntentAnalysisRequest, extraction: IntentExtractionResult, *, trace_id: str, budget: AnalysisBudget,
    ) -> IntentComparisonResult:
        """Use the same mapping and skip rules as analyze, without another extraction."""
        extraction = IntentExtractionResult.model_validate(extraction.model_dump()).validate_for_request(request)
        if extraction.metadata.trace_id != trace_id:
            raise ValueError("comparison must retain the extraction trace")
        settings = self._adapter.settings
        if extraction.status != "SUCCESS":
            comparison = _comparison_without_call(settings, request.target, trace_id)
        else:
            mapped = None
            try:
                mapped = extraction.to_evaluation_request(request)
            except ValidationError:
                pass
            if mapped is None:
                comparison = _comparison_without_call(
                    settings, request.target, trace_id,
                    failure=AnalysisFailure(kind="INVALID_INPUT", retryable=False),
                )
            else:
                comparison = await self._comparator.compare(
                    mapped, trace_id=trace_id, budget=budget, clarifications=extraction.clarifications,
                )
        return IntentAnalysisRun(extraction=extraction, comparison=comparison).validate_for_request(request).comparison
