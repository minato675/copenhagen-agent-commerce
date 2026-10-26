"""Immutable replay artifacts, code fingerprints, summaries and score re-verification."""

import hashlib
import json
import platform
from collections import Counter
from importlib.metadata import version
from pathlib import Path

from copenhagen_fds.baselines import _unique_json
from copenhagen_fds.contracts.personal_replay import PersonalReplayRequest, PersonalReplayResult, PersonalReplayRow
from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.personal_replay import run_personal_replay
from copenhagen_fds.synthetic.learning import read_learning_observations
from copenhagen_fds.synthetic.storage import canonical, digest


def read_json(path):
    def invalid_constant(value):
        raise ValueError("nonstandard JSON number")

    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=_unique_json, parse_constant=invalid_constant)


def code_fingerprint():
    root = Path(__file__).parent
    # Git may check out the same source with LF or CRLF on another machine.
    files = {p.relative_to(root).as_posix(): hashlib.sha256(
        p.read_text(encoding="utf-8").replace("\r\n", "\n").encode("utf-8")).hexdigest()
        for p in sorted(root.rglob("*.py"))}
    return {"source_sha256": personalization_hash(files), "files": files,
            "python": platform.python_version(), "pydantic": version("pydantic")}


def comparison_summary(result):
    rows = []
    for row in result.rows:
        evaluation = row.evaluation
        features = []
        for feature, support, decision in zip(evaluation.result.features,
                evaluation.components.transition.readiness.features, evaluation.components.transition.features):
            score = lambda part: part.score.value if part.score is not None else None
            personal, cold, final = score(feature.personal), score(feature.cold), score(feature.combined)
            features.append({"feature_id": feature.combined.feature_id, "raw": decision.raw.model_dump(mode="json"),
                "cold": cold, "personal": personal, "combined": final,
                "delta_from_cold": final - cold if final is not None and cold is not None else None,
                "status": feature.combined.status.value, "stage": feature.stage,
                "preferred_personal_weight": decision.preferred_personal_weight,
                "personal_weight": feature.personal_weight, "cold_weight": feature.cold_weight,
                "window": feature.personal_window, "reasons": list(feature.stage_reasons),
                "window_samples": {w.window: w.measures.valid_samples for w in support.windows},
                "window_weights": {w.window: w.personal_weight for w in support.windows}})
        rows.append({"case_id": row.case.case_id, "mode": row.case.mode,
            "user_id": row.input.cold.request.evaluation.target.user_id,
            "purchase_request_id": row.input.cold.request.evaluation.target.purchase_request_id,
            "evaluated_at": row.input.cold.request.evaluation.evaluated_at.isoformat(),
            "profile_as_of": row.case.profile_as_of.isoformat() if row.case.profile_as_of else None,
            "profile_status": evaluation.result.profiles[0].status,
            "profile_reference": row.input.personal.expected_reference.model_dump(mode="json") if row.input.personal else None,
            "input_sha256": evaluation.result.input_sha256,
            "status": evaluation.result.behavior.status.value, "coverage": evaluation.result.behavior.coverage.value,
            "features": features})
    return {"schema_version": "personal-replay-comparison-v1", "rows": rows,
            "note": "Synthetic regression only. Controlled probes assume zero prior 24h activity; no fraud labels, probabilities or final risk decision."}


def check_output_location(dataset, output):
    dataset, output = dataset.resolve(), output.resolve()
    if output == dataset or dataset in output.parents or output in dataset.parents or output.exists():
        raise ValueError("replay requires a new output directory outside the dataset tree")


def export_personal_replay(dataset: Path, output: Path, request: PersonalReplayRequest, *, observations=None):
    check_output_location(dataset, output)
    data, source = observations if observations is not None else read_learning_observations(dataset)
    request = PersonalReplayRequest.model_validate(request.model_dump())
    result = run_personal_replay(data, request)
    summary = comparison_summary(result)
    artifacts = {
        "request.json": canonical(request.model_dump(mode="json")) + "\n",
        "evaluations.jsonl": "".join(canonical(r.model_dump(mode="json")) + "\n" for r in result.rows),
        "comparisons.json": canonical(summary) + "\n",
    }
    encoded = {name: value.encode("utf-8") for name, value in artifacts.items()}
    failed = any(r.evaluation.result.behavior.status == "FAILED" for r in result.rows)
    report = {"schema_version": "personal-replay-export-v1", "status": "FAILED" if failed else "SCORED",
        "dataset_source_reference": source, "request_sha256": result.request_sha256,
        "settings_sha256": personalization_hash(request.settings), "code": code_fingerprint(),
        "cases": len(result.rows), "stages": dict(sorted(Counter(f.stage for r in result.rows for f in r.evaluation.result.features).items())),
        "execution": dict(sorted(Counter(r.evaluation.result.behavior.status.value for r in result.rows).items())),
        "coverage": dict(sorted(Counter(r.evaluation.result.behavior.coverage.value for r in result.rows).items())),
        "scored_features": sum(f.combined.status == "SCORED" for r in result.rows for f in r.evaluation.result.features),
        "artifact_sha256": {name: hashlib.sha256(value).hexdigest() for name, value in encoded.items()},
        "model_scored": True, "truth_read": False, "personas_read": False, "service_integrated": False}
    # Reserve once after all computations/encoding; never remove or replace existing output.
    output.mkdir(parents=True, exist_ok=False)
    for name, value in encoded.items():
        (output / name).write_bytes(value)
    (output / "report.json").write_bytes((canonical(report) + "\n").encode("utf-8"))
    return report


def verify_personal_replay(output: Path):
    report = read_json(output / "report.json")
    expected = {"request.json", "evaluations.jsonl", "comparisons.json"}
    if report["schema_version"] != "personal-replay-export-v1" or set(report["artifact_sha256"]) != expected:
        raise ValueError("unsupported replay artifact inventory")
    for name in expected:
        if digest(output / name) != report["artifact_sha256"][name]:
            raise ValueError("replay artifact hash mismatch")
    if report["code"] != code_fingerprint():
        raise ValueError("score reproduction requires the recorded code and runtime")
    request = PersonalReplayRequest.model_validate(read_json(output / "request.json"))
    rows = tuple(PersonalReplayRow.model_validate_json(line)
        for line in (output / "evaluations.jsonl").read_text(encoding="utf-8").splitlines())
    result = PersonalReplayResult(request_sha256=report["request_sha256"], rows=rows).validate_for_request(request)
    if report["settings_sha256"] != personalization_hash(request.settings) or report["cases"] != len(rows):
        raise ValueError("replay report must match the recorded settings and case count")
    execution = dict(sorted(Counter(r.evaluation.result.behavior.status.value for r in rows).items()))
    stages = dict(sorted(Counter(f.stage for r in rows for f in r.evaluation.result.features).items()))
    coverage = dict(sorted(Counter(r.evaluation.result.behavior.coverage.value for r in rows).items()))
    if report["coverage"] != coverage or report["scored_features"] != sum(f.combined.status == "SCORED" for r in rows for f in r.evaluation.result.features):
        raise ValueError("replay report must preserve actual score availability")
    if (report["execution"], report["stages"], report["status"]) != (
        execution, stages, "FAILED" if execution.get("FAILED") else "SCORED"
    ):
        raise ValueError("replay report must preserve execution failures and stage counts")
    if (report["model_scored"], report["truth_read"], report["personas_read"], report["service_integrated"]) != (True, False, False, False):
        raise ValueError("replay report must preserve its computation scope")
    for row in rows:
        row.evaluation.validate_for_input(row.input)
    if comparison_summary(result) != read_json(output / "comparisons.json"):
        raise ValueError("comparison summary must match the detailed scored results")
    return {"status": "VERIFIED", "cases": len(rows), "request_sha256": report["request_sha256"],
            "note": "Artifact integrity and score replay verified; rebuilding published profiles requires the original observable dataset."}
