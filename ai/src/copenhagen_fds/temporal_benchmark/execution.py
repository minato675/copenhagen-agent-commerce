"""Persist paired predictions before any evaluator-side label join."""

import json
from pathlib import Path
import time

from copenhagen_fds.native_cli.runtime import fingerprint
from copenhagen_fds.native_cli.storage import check_new_output, write_artifacts
from copenhagen_fds.synthetic.storage import canonical, digest
from .preparation import INPUT_NAMES, read_inputs
from .runner import replay


async def execute(source: Path, output: Path, *, progress=None) -> dict:
    check_new_output(output, source)
    plan, observations, semantic, selected = read_inputs(source)
    code = fingerprint()
    input_hashes = {name: digest(source / name) for name in sorted(INPUT_NAMES)}
    output.mkdir(parents=True, exist_ok=False)
    # A partial journal is deliberately retained if execution is interrupted.
    count, started = 0, time.perf_counter()
    with (output / "predictions.jsonl").open("x", encoding="utf-8", newline="\n") as stream:
        async for row in replay(plan, observations, semantic, selected):
            stream.write(canonical(row) + "\n")
            stream.flush()
            count += 1
            if progress is not None:
                progress(count, len(selected))
    summary = dict(status="EXECUTED", purchases=count, native_evaluations=count * 2,
        mode="MOCK", semantic_quality="NOT_MEASURED_CONSTANT_PROVIDER",
        external_api="NOT_RUN", labels_read=False, service_integrated=False,
        profile_persistence="IN_MEMORY_ONLY", wall_duration_seconds=time.perf_counter() - started)
    write_artifacts(output, {"summary.json": summary, "plan.json": plan.model_dump(mode="json")},
        dict(schema_version="temporal-benchmark-run-v1", code=code,
            input_sha256=input_hashes, predictions_sha256=digest(output / "predictions.jsonl"),
            selected_purchase_ids=list(selected), labels_read=False, external_api="NOT_RUN"))
    return summary


def read_predictions(source: Path, output: Path):
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    if (manifest["schema_version"] != "temporal-benchmark-run-v1" or manifest["labels_read"]
            or manifest["external_api"] != "NOT_RUN"):
        raise ValueError("unsupported temporal prediction provenance")
    if set(manifest["input_sha256"]) != INPUT_NAMES or set(manifest["artifact_sha256"]) != {"plan.json", "summary.json"}:
        raise ValueError("unexpected temporal run inventory")
    for name, expected in manifest["input_sha256"].items():
        if digest(source / name) != expected:
            raise ValueError("run input changed")
    for name, expected in manifest["artifact_sha256"].items():
        if digest(output / name) != expected:
            raise ValueError("run artifact changed")
    if digest(output / "predictions.jsonl") != manifest["predictions_sha256"]:
        raise ValueError("prediction journal changed")
    plan, _, _, selected = read_inputs(source)
    if json.loads((output / "plan.json").read_text(encoding="utf-8")) != plan.model_dump(mode="json"):
        raise ValueError("run settings differ from frozen input")
    rows = [json.loads(line) for line in (output / "predictions.jsonl").read_text(encoding="utf-8").splitlines()]
    if tuple(row["purchase_request_id"] for row in rows) != selected or tuple(manifest["selected_purchase_ids"]) != selected:
        raise ValueError("prediction journal does not cover the frozen selection")
    return manifest, rows


async def verify_run(source: Path, output: Path, *, progress=None):
    manifest, expected = read_predictions(source, output)
    if manifest["code"] != fingerprint():
        raise ValueError("reproduction requires the recorded source code and runtime")
    plan, observations, semantic, selected = read_inputs(source)
    count = 0
    async for actual in replay(plan, observations, semantic, selected):
        if actual != expected[count]:
            raise ValueError("historical predictions or profile transitions did not reproduce")
        count += 1
        if progress is not None:
            progress(count, len(selected))
    return dict(status="REPRODUCED", purchases=count, external_api="NOT_RUN", labels_read=False,
                wall_duration_reproduced=False)
