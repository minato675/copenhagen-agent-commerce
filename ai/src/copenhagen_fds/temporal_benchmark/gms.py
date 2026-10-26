"""Freeze a small, non-holdout GMS diagnostic without credentials or API calls.

The existing experiment collector accepts >=20 reference cases, while a plan
may explicitly select a smaller subset. Each PIPELINE plan permits one full
extraction/comparison run, so repeated pipelines use separate frozen rounds.
"""

import hashlib
import json
from pathlib import Path
from collections import Counter, defaultdict
import re

from copenhagen_fds.analysis.comparison_prompt import build_comparison_prompt
from copenhagen_fds.analysis.extraction_prompt import build_extraction_prompt
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.evaluation.collection import collect, write_json
from copenhagen_fds.evaluation.experiment_scoring import distribution
from copenhagen_fds.evaluation.experiments import (
    ExperimentBatch, ExperimentPlan, digest as message_digest, make_plan, pinned_prompt_body,
)
from copenhagen_fds.evaluation.grading import _provider_metrics, grade_case
from copenhagen_fds.evaluation.models import EvaluationCase, EvaluationDataset, ExpectedOutcome, Observation
from copenhagen_fds.evaluation.suite_cases import fields_for
from copenhagen_fds.native_cli.storage import check_new_output, write_artifacts
from copenhagen_fds.synthetic.storage import digest
from .preparation import read_inputs, read_targets
from .semantic_cases import SemanticExpected


GMS_SCHEMA = "temporal-gms-plan-bundle-v1"
INTERPRETATION = "NON_HOLDOUT_SUBSAMPLED_SYNTHETIC_DIAGNOSTIC"


def _validate_limits(limit: int, repetitions: int = 1) -> None:
    if type(limit) is not int or not 1 <= limit <= 12:
        raise ValueError("GMS diagnostic sample limit must be an integer from 1 to 12")
    if type(repetitions) is not int or not 1 <= repetitions <= 2:
        raise ValueError("GMS diagnostic pipeline rounds must be an integer from 1 to 2")


def _case_id(purchase_id: str) -> str:
    return "temporal-" + hashlib.sha256(purchase_id.encode("utf-8")).hexdigest()[:32]


def _corpus(source: Path, limit: int):
    _validate_limits(limit)
    plan, _, semantic, selected = read_inputs(source)
    # Freeze identities before opening any evaluator labels. No balancing by
    # fraud labels, semantic answers, FDS results, or successful GMS outcomes.
    eligible = tuple(pid for pid in selected if semantic[pid].analysis.user_request.text is not None)
    if len(eligible) < 20:
        raise ValueError("GMS reference corpus requires at least 20 purchases with observed text")
    sampled = eligible[:limit]
    targets = read_targets(source)
    if set(targets["semantic"]) != set(selected):
        raise ValueError("semantic references must cover the frozen source selection")
    cases = []
    for pid in eligible:
        observation = semantic[pid]
        reference = SemanticExpected.model_validate(targets["semantic"][pid])
        if (reference.expected_extraction is None or reference.expected_comparison is None
                or reference.comparison is None):
            raise ValueError("observed text requires explicit semantic reference payloads")
        # Purchase/order/item references retain their original identities; only
        # the experiment's case identity is namespaced, so no remapping occurs.
        reference.expected_comparison.validate_for_request(
            observation.analysis.with_extracted_intent(reference.expected_extraction),
        )
        categories = ["temporal-benchmark", "synthetic-authored",
                      reference.recipe.lower().replace("_", "-"), reference.comparison.lower()]
        if reference.recipe.startswith("INJECTION_"):
            categories.append("prompt-injection")
        if reference.comparison == "MISMATCH":
            categories.append("critical")
        cases.append(EvaluationCase(
            case_id=_case_id(pid), categories=tuple(categories), rubric=reference.basis,
            request=observation.analysis,
            mock_extraction=reference.expected_extraction,
            mock_comparison=reference.expected_comparison,
            expected=ExpectedOutcome(
                extraction_status=reference.extraction_status,
                comparison_status=reference.comparison_status,
                comparison=reference.comparison,
                fields=fields_for(reference.expected_extraction),
                required_findings=tuple(sorted({f.code for f in reference.expected_comparison.findings})),
                required_confirmations=tuple(sorted({f.code for f in reference.expected_comparison.confirmation_requests})),
                uncertainty="comparison" if reference.comparison == "UNKNOWN" else None,
            ),
        ))
    dataset = EvaluationDataset(version="temporal-gms-diagnostic-v1", cases=tuple(cases))
    return plan, dataset, sampled


def build_gms_dataset(source: Path, limit: int = 12) -> EvaluationDataset:
    """Return the reference corpus; the first ``limit`` cases are the sample.

    The corpus intentionally retains >=20 cases for the existing dataset
    contract. ``prepare_gms_plan`` pins only the selected <=12 case identities
    as actual planned calls. Missing-text purchases never create paid calls.
    """
    return _corpus(Path(source), limit)[1]


def prepare_gms_plan(source: Path, output: Path, limit: int = 12, repetitions: int = 2) -> dict:
    """Prepare bounded independent pipeline rounds; never load a key or call GMS."""
    _validate_limits(limit, repetitions)
    source, output = Path(source), Path(output)
    check_new_output(output, source)
    benchmark, dataset, sampled = _corpus(source, limit)
    case_ids = tuple(_case_id(pid) for pid in sampled)
    sample = dict(
        selection="OBSERVABLE_SELECTION_ORDER_WITH_NONEMPTY_TEXT",
        purchase_ids=list(sampled), case_ids=list(case_ids), sample_limit=limit,
        source_plan_sha256=personalization_hash(benchmark), dataset_sha256=dataset.sha256,
        label_used_for_selection=False, prediction_used_for_selection=False,
    )
    sample_hash = personalization_hash(sample)
    artifacts = {"dataset.json": dataset.model_dump(mode="json"), "sample.json": sample}
    rounds = []
    for number in range(1, repetitions + 1):
        plan = make_plan(dataset, experiment_id=f"temporal-{sample_hash[:20]}-round-{number}",
            mode="PIPELINE", case_ids=case_ids, repetitions=1,
            max_http_requests=2 * len(sampled), dataset_role="REGRESSION")
        name = f"round-{number}.plan.json"
        artifacts[name] = plan.model_dump(mode="json")
        rounds.append(dict(round=number, plan_file=name, plan_sha256=plan.sha256,
                           max_http_requests=plan.max_http_requests))
    metadata = dict(
        schema_version=GMS_SCHEMA, interpretation=INTERPRETATION,
        dataset_role="REGRESSION", human_review="NOT_PERFORMED", quality_targets="NOT_CLAIMED",
        source_manifest_sha256=digest(source / "manifest.json"),
        source_plan_sha256=personalization_hash(benchmark), dataset_sha256=dataset.sha256,
        sample_sha256=sample_hash, corpus_cases=len(dataset.cases), sampled_purchases=len(sampled),
        pipeline_rounds=repetitions, rounds=rounds,
        max_http_requests=sum(row["max_http_requests"] for row in rounds),
        budget_basis="CALL_COUNT_ONLY_UNPRICED", billed_cost=None,
        api_calls=0, credentials_loaded=False,
        notes=[
            "References are synthetic recipes without human label review; this is not a holdout.",
            "Sampling uses observable identity/order only; it does not enforce label balance.",
            "Separate complete pipeline rounds measure end-to-end variation, not comparison-only stability.",
            "Existing experiment quality thresholds are not Story 10 acceptance criteria.",
            "Scripted Mock collection checks wiring and must not be reported as model quality.",
        ],
    )
    output.mkdir(parents=True, exist_ok=False)
    write_artifacts(output, artifacts, metadata)
    return dict(status="PREPARED", interpretation=INTERPRETATION, sampled_purchases=len(sampled),
                corpus_cases=len(dataset.cases), pipeline_rounds=repetitions, rounds=rounds,
                max_http_requests=metadata["max_http_requests"], api_calls=0,
                human_review="NOT_PERFORMED", quality_targets="NOT_CLAIMED")


def read_gms_plans(source: Path) -> tuple[ExperimentPlan, ...]:
    """Verify the complete prepared inventory before explicit collector dispatch."""
    source = Path(source)
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    if (manifest["schema_version"] != GMS_SCHEMA or manifest["interpretation"] != INTERPRETATION
            or manifest["human_review"] != "NOT_PERFORMED" or manifest["dataset_role"] != "REGRESSION"):
        raise ValueError("unsupported GMS diagnostic plan bundle")
    _validate_limits(manifest["sampled_purchases"], manifest["pipeline_rounds"])
    names = {"dataset.json", "sample.json"} | {
        f"round-{number}.plan.json" for number in range(1, manifest["pipeline_rounds"] + 1)
    }
    if set(manifest["artifact_sha256"]) != names:
        raise ValueError("unexpected GMS plan artifact inventory")
    for name in names:
        if digest(source / name) != manifest["artifact_sha256"][name]:
            raise ValueError("GMS plan artifact changed")
    dataset = EvaluationDataset.model_validate_json((source / "dataset.json").read_text(encoding="utf-8"))
    sample = json.loads((source / "sample.json").read_text(encoding="utf-8"))
    if (dataset.sha256 != manifest["dataset_sha256"] or dataset.sha256 != sample["dataset_sha256"]
            or personalization_hash(sample) != manifest["sample_sha256"]):
        raise ValueError("GMS sample or corpus hash mismatch")
    if (sample["case_ids"] != [_case_id(pid) for pid in sample["purchase_ids"]]
            or len(sample["case_ids"]) != manifest["sampled_purchases"]
            or sample["case_ids"] != [case.case_id for case in dataset.cases[:manifest["sampled_purchases"]]]
            or sample["label_used_for_selection"] or sample["prediction_used_for_selection"]):
        raise ValueError("GMS sample does not match the declared observable selection")
    plans = []
    if len(manifest["rounds"]) != manifest["pipeline_rounds"]:
        raise ValueError("GMS round inventory differs from the declared plan")
    for number, row in enumerate(manifest["rounds"], 1):
        name = f"round-{number}.plan.json"
        if row["round"] != number or row["plan_file"] != name:
            raise ValueError("GMS rounds must be unique and ordered")
        plan = ExperimentPlan.model_validate_json((source / name).read_text(encoding="utf-8"))
        if (plan.sha256 != row["plan_sha256"] or plan.dataset.sha256 != dataset.sha256
                or plan.case_ids != tuple(sample["case_ids"]) or plan.mode != "PIPELINE"
                or plan.repetitions != 1 or plan.dataset_role != "REGRESSION" or plan.human_review is not None
                or plan.max_http_requests != 2 * len(plan.case_ids)
                or plan.max_http_requests != row["max_http_requests"]):
            raise ValueError("GMS round differs from its frozen sample or call budget")
        plans.append(plan)
    if sum(plan.max_http_requests for plan in plans) != manifest["max_http_requests"]:
        raise ValueError("GMS aggregate call budget mismatch")
    return tuple(plans)


def _diagnostic_plan(plan: ExperimentPlan) -> ExperimentPlan:
    plan = ExperimentPlan.model_validate(plan.model_dump())
    _validate_limits(len(plan.case_ids))
    if (plan.dataset.version != "temporal-gms-diagnostic-v1" or plan.dataset_role != "REGRESSION"
            or plan.human_review is not None or plan.mode != "PIPELINE" or plan.repetitions != 1
            or plan.max_http_requests != 2 * len(plan.case_ids)):
        raise ValueError("expected a bounded non-holdout temporal pipeline plan")
    for case in plan.dataset.cases:
        if case.case_id in plan.case_ids:
            if case.request.user_request.text is None or case.expected.comparison is None:
                raise ValueError("GMS diagnostic requires observed text and semantic references")
            prompt = build_extraction_prompt(case.request.user_request)
            if message_digest(pinned_prompt_body(prompt, plan)) != plan.extraction_input_hashes[case.case_id]:
                raise ValueError("GMS extraction input differs from the frozen plan")
    return plan


def _validate_round(plan: ExperimentPlan, batch: ExperimentBatch):
    """Validate pipeline provenance without claiming the legacy v1 corpus role."""
    plan = _diagnostic_plan(plan)
    batch = ExperimentBatch.model_validate(batch.model_dump())
    if batch.plan_sha256 != plan.sha256:
        raise ValueError("GMS observations belong to another plan")
    cases = {case.case_id: case for case in plan.dataset.cases}
    planned = set(plan.run_ids)
    observed = {(row.case_id, row.repetition): row for row in batch.observations}
    if not set(observed) <= planned or len(batch.requests) > plan.max_http_requests:
        raise ValueError("GMS observations or requests exceed the frozen plan")
    dispatched = {(r.case_id, r.repetition, r.stage) for r in batch.requests}
    for request in batch.requests:
        key = (request.case_id, request.repetition)
        if key not in planned:
            raise ValueError("GMS request is outside the frozen sample")
        if request.stage == "extraction" and request.input_sha256 != plan.extraction_input_hashes[request.case_id]:
            raise ValueError("GMS journal extraction hash differs from plan")
        if key in observed and request.input_sha256 != getattr(observed[key], request.stage + "_input_sha256"):
            raise ValueError("GMS request and observation hashes disagree")
    models = defaultdict(set)
    for key, observation in observed.items():
        if observation.run is None or observation.comparison is not None:
            raise ValueError("GMS diagnostic requires a complete pipeline observation shape")
        run = observation.run.validate_for_request(cases[key[0]].request)
        if observation.extraction_input_sha256 != plan.extraction_input_hashes[key[0]]:
            raise ValueError("GMS extraction observation changed its input")
        if observation.comparison_input_sha256 is not None:
            mapped = run.extraction.to_evaluation_request(cases[key[0]].request)
            prompt = build_comparison_prompt(mapped, clarifications=run.extraction.clarifications)
            if observation.comparison_input_sha256 != message_digest(pinned_prompt_body(prompt, plan)):
                raise ValueError("GMS comparison input differs from observed extraction")
        for stage in ("extraction", "comparison"):
            result = getattr(run, stage)
            if (result.metadata.prompt_version != plan.prompts[stage].version
                    or result.telemetry.requested_model != plan.requested_model):
                raise ValueError("GMS model or prompt metadata differs from plan")
            response_evidence = (result.status == "SUCCESS" or result.telemetry.request_id is not None
                or result.telemetry.response_id is not None or result.telemetry.usage is not None
                or (result.failure is not None and result.failure.http_status is not None))
            if response_evidence and (*key, stage) not in dispatched:
                raise ValueError("GMS response lacks a request journal entry")
            version = result.metadata.model_version
            if version is not None:
                if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,200}", version):
                    raise ValueError("invalid GMS response model identifier")
                models[stage].add(version)
    if any(len(values) > 1 for values in models.values()):
        raise ValueError("GMS round contains mixed response model versions")
    return plan, batch, cases, observed, {key: sorted(values) for key, values in models.items()}


def summarize_temporal_round(plan: ExperimentPlan, batch: ExperimentBatch) -> dict:
    """Diagnostic metrics include failures/missing trials; no acceptance verdict.

    The legacy ``score_experiment`` intentionally accepts only the old corpus
    for REGRESSION. This separate report reuses field/reason grading primitives
    and explicitly does not claim that corpus's thresholds or holdout status.
    """
    plan, batch, cases, observed, models = _validate_round(plan, batch)
    rows = []
    for case_id, repetition in plan.run_ids:
        case, observation = cases[case_id], observed.get((case_id, repetition))
        if observation is None:
            row = dict(case_id=case_id, passed=False, expected_comparison=case.expected.comparison,
                actual_outcome="MISSING", failure_kind=None,
                checks=dict(comparison=False),
                field_checks={field.path: False for field in case.expected.fields},
                reason_checks=dict(findings=False, confirmations=False, paired=False))
        else:
            row = grade_case(case, Observation(case_id=case_id, run=observation.run))
        row.update(run_id=f"{case_id}.r{repetition}", purchase_request_id=case.request.context.purchase_request_id,
                   categories=list(case.categories))
        rows.append(row)
    labels = ("MATCH", "MISMATCH", "UNKNOWN")
    matrix = {label: dict.fromkeys((*labels, "FAILED", "NOT_RUN", "MISSING"), 0) for label in labels}
    for row in rows:
        matrix[row["expected_comparison"]][row["actual_outcome"]] += 1

    def ratio(passed, total):
        return dict(passed=passed, total=total, rate=passed / total if total else None)

    fields = [value for row in rows for value in row["field_checks"].values()]
    valid = sum(row["actual_outcome"] in labels for row in rows)
    orphaned = [r for r in batch.requests if (r.case_id, r.repetition) not in observed]
    timings = [row.wall_duration_ms for row in batch.observations]
    metrics = dict(
        confusion=matrix, outcomes=dict(Counter(row["actual_outcome"] for row in rows)),
        label_accuracy=ratio(sum(row["checks"]["comparison"] for row in rows), len(rows)),
        extraction_field_checks=ratio(sum(fields), len(fields)),
        case_checks=ratio(sum(row["passed"] for row in rows), len(rows)),
        semantic_result_coverage=ratio(valid, len(rows)),
        reason_checks={name: ratio(sum(row["reason_checks"][name] for row in rows), len(rows))
                       for name in ("findings", "confirmations", "paired")},
    )
    return dict(
        schema_version="temporal-gms-diagnostic-report-v1", interpretation=INTERPRETATION,
        source=batch.source, experiment_id=plan.experiment_id, plan_sha256=plan.sha256,
        dataset_sha256=plan.dataset.sha256, dataset_role="REGRESSION", human_review="NOT_PERFORMED",
        quality_targets="NOT_CLAIMED", population_performance="NOT_ESTIMATED",
        mock_is_model_quality=False, status=batch.status, stop_reason=batch.stop_reason,
        complete=batch.status == "COMPLETE" and len(observed) == len(rows) and not orphaned,
        planned_purchases=len(rows), observed_purchases=len(observed),
        http_requests=len(batch.requests), max_http_requests=plan.max_http_requests,
        requests_without_observation=len(orphaned), billed_cost=None,
        metrics=metrics, rows=rows,
        timing_scope="WHOLE_EXTRACTION_AND_COMPARISON_PIPELINE_ATTEMPTS",
        wall_duration_ms=distribution(timings, len(rows)),
        latency_is_gms_measurement=batch.source == "GMS_IMPORTED",
        provider_metrics=({stage: _provider_metrics([getattr(row.run, stage) for row in batch.observations])
                           for stage in ("extraction", "comparison")} if batch.source == "GMS_IMPORTED" else None),
        response_models=models,
        limitations=[
            "A small observable-order sample, without human review or population weighting.",
            "Mock observations check contracts and provide no model quality or live latency evidence.",
            "Repeated rounds rerun both extraction and comparison; missing and failed trials stay in denominators.",
        ],
    )


async def collect_temporal_gms(plan: ExperimentPlan, adapter, output: Path, *, progress=None):
    """Use the existing bounded collector unchanged, then write this diagnostic.

    Explicit callers supply an already configured adapter. This function does
    not find credentials, enable LIVE mode, retry, or overwrite prior runs.
    """
    plan = _diagnostic_plan(plan)
    output = Path(output)
    batch = await collect(plan, adapter, output, progress=progress)
    report = summarize_temporal_round(plan, batch)
    write_json(output / "report.json", report)
    return batch, report
