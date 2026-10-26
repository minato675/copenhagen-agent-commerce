"""Sequential collection with a durable pre-dispatch journal and no retries."""

import asyncio
import json
from pathlib import Path
import time

from copenhagen_fds.analysis import IntentAnalysisRun, IntentAnalyzer, IntentComparator
from copenhagen_fds.analysis.comparison_prompt import build_comparison_prompt
from copenhagen_fds.analysis.extraction_prompt import build_extraction_prompt
from copenhagen_fds.evaluation.experiments import (
    ExperimentBatch, ExperimentObservation, HttpAttempt, PromptPin, digest, prompt_body, utc_now,
)


class CollectionStop(BaseException):
    """A collection boundary must not be swallowed as a provider API_ERROR."""


def write_json(path: Path, data):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def verify_runtime(plan, adapter):
    settings = adapter.settings
    if (settings.openai_model != plan.requested_model
            or settings.openai_timeout_seconds != plan.provider_timeout_seconds
            or settings.evaluation_timeout_seconds != plan.evaluation_timeout_seconds):
        raise ValueError("runtime settings differ from the approved plan")
    cases = {c.case_id: c for c in plan.dataset.cases}
    sample = next(c for c in plan.dataset.cases if c.request.user_request.text is not None)
    actual = {
        "extraction": build_extraction_prompt(sample.request.user_request),
        "comparison": build_comparison_prompt(sample.request.with_extracted_intent(sample.mock_extraction)),
    }
    if any(PromptPin.from_prompt(p) != plan.prompts[s] for s, p in actual.items()):
        raise ValueError("runtime prompt or schema changed after approval")
    for key in plan.case_ids:
        if plan.mode == "PIPELINE" and cases[key].request.user_request.text is not None:
            value = digest(prompt_body(build_extraction_prompt(cases[key].request.user_request)))
            if value != plan.extraction_input_hashes[key]:
                raise ValueError("planned extraction input changed")
        if plan.mode == "COMPARISON_REPEAT":
            frozen = plan.frozen_inputs[key]
            value = digest(prompt_body(build_comparison_prompt(frozen.request, clarifications=frozen.clarifications)))
            if value != plan.comparison_input_hashes[key]:
                raise ValueError("planned comparison input changed")
    if settings.mode == "LIVE" and plan.dataset_role == "HOLDOUT" and plan.human_review is None:
        raise ValueError("live holdout collection requires human-approved labels")


async def collect(plan, adapter, output_directory: Path, *, progress=None):
    verify_runtime(plan, adapter)
    output_directory = Path(output_directory)
    output_directory.mkdir(parents=True, exist_ok=False)
    write_json(output_directory / "plan.json", plan.model_dump(mode="json"))
    records, requests = [], []
    started = utc_now()
    status, reason, completed = "RUNNING", None, None
    current = None
    pending = {}

    def snapshot():
        return ExperimentBatch(
            plan_sha256=plan.sha256, source="MOCK" if adapter.settings.mode == "MOCK" else "GMS_IMPORTED",
            status=status, stop_reason=reason, started_at=started, completed_at=completed,
            observations=tuple(records), requests=tuple(requests),
        )

    def checkpoint():
        write_json(output_directory / "observations.json", snapshot().model_dump(mode="json"))

    class ObservedAdapter:
        settings = adapter.settings

        async def complete(self, prompt, *, budget):
            if prompt.stage in pending:
                raise CollectionStop("CALL_LIMIT")
            pending[prompt.stage] = digest(prompt_body(prompt, plan.requested_model))
            return await adapter.complete(prompt, budget=budget)

    async def on_request(request):
        if request.method != "POST" or str(request.url) != adapter.settings.openai_base_url + "/chat/completions":
            raise CollectionStop("LOCAL_ERROR")
        body = json.loads(request.content)
        message_hash = digest(body)
        stages = [s for s, value in pending.items() if value == message_hash]
        if len(stages) != 1 or current is None:
            raise CollectionStop("LOCAL_ERROR")
        stage = stages[0]
        if plan.mode == "COMPARISON_REPEAT" and stage != "comparison":
            raise CollectionStop("LOCAL_ERROR")
        if len(requests) >= plan.max_http_requests or any(
            (r.case_id, r.repetition, r.stage) == (*current, stage) for r in requests
        ):
            raise CollectionStop("CALL_LIMIT")
        fixed = (plan.extraction_input_hashes if stage == "extraction" else plan.comparison_input_hashes).get(current[0])
        if fixed is not None and fixed != message_hash:
            raise CollectionStop("LOCAL_ERROR")
        requests.append(HttpAttempt(sequence=len(requests) + 1, case_id=current[0], repetition=current[1],
                                    stage=stage, input_sha256=message_hash))
        # Journal before transport; a crash leaves an unresolved attempt rather
        # than allowing it to disappear from cost/coverage accounting.
        checkpoint()

    async def on_response(response):
        requests[-1] = requests[-1].model_copy(update={"http_status": response.status_code})
        checkpoint()

    # The adapter owns/configures this httpx2 client (GMS only, redirects off,
    # trust_env off, max_retries=0). No replacement transport is installed here.
    transport = adapter._client._client
    transport.event_hooks["request"].append(on_request)
    transport.event_hooks["response"].append(on_response)
    checkpoint()
    cases = {c.case_id: c for c in plan.dataset.cases}
    observed_adapter = ObservedAdapter()
    analyzer, comparator = IntentAnalyzer(observed_adapter), IntentComparator(observed_adapter)
    try:
        for case_id, repetition in plan.run_ids:
            current = (case_id, repetition)
            pending.clear()
            case = cases[case_id]
            began = time.monotonic()
            run, comparison = None, None
            if plan.mode == "PIPELINE":
                run = await analyzer.analyze(case.request, trace_id=f"{plan.experiment_id}.{case_id}.r{repetition}")
            else:
                frozen = plan.frozen_inputs[case_id]
                comparison = await comparator.compare(
                    frozen.request, trace_id=f"{plan.experiment_id}.{case_id}.r{repetition}",
                    budget=adapter.new_budget(), clarifications=frozen.clarifications,
                )
            wall_ms = (time.monotonic() - began) * 1000
            first_hash = plan.extraction_input_hashes.get(case_id) if plan.mode == "PIPELINE" else None
            second_hash = plan.comparison_input_hashes.get(case_id) if plan.mode == "COMPARISON_REPEAT" else pending.get("comparison")
            records.append(ExperimentObservation(
                case_id=case_id, repetition=repetition, run_id=f"{case_id}.r{repetition}", run=run, comparison=comparison,
                wall_duration_ms=wall_ms, extraction_input_sha256=first_hash, comparison_input_sha256=second_hash,
            ))
            checkpoint()
            if progress:
                progress({"completed": len(records), "planned": len(plan.run_ids), "http_requests": len(requests)})
            failure = (run.extraction.failure or run.comparison.failure) if run else comparison.failure
            if failure and failure.http_status in (400, 401, 403, 404, 422, 429):
                status, reason = "STOPPED", "PROVIDER_ACCESS"
                break
            recent = [(o.run.extraction.failure or o.run.comparison.failure) if o.run else o.comparison.failure
                      for o in records[-3:]]
            if len(recent) == 3 and all(f is not None and f.kind in ("API_ERROR", "TIMEOUT") for f in recent):
                status, reason = "STOPPED", "REPEATED_FAILURE"
                break
        else:
            status = "COMPLETE"
    except CollectionStop as error:
        status, reason = "STOPPED", str(error)
    except asyncio.CancelledError:
        status, reason = "INTERRUPTED", "CANCELLED"
        raise
    except Exception:
        status, reason = "STOPPED", "LOCAL_ERROR"
    finally:
        completed = utc_now()
        transport.event_hooks["request"].remove(on_request)
        transport.event_hooks["response"].remove(on_response)
        checkpoint()
    return snapshot()
