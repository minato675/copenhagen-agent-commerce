"""Immutable input bundles with a separate evaluator-only answer file."""

import json
from pathlib import Path

from copenhagen_fds.contracts.personalization_inputs import personalization_hash
from copenhagen_fds.native_cli.storage import check_new_output, write_artifacts
from copenhagen_fds.synthetic.learning import OBSERVABLE_TABLES
from copenhagen_fds.synthetic.replay import ReplayIndex
from copenhagen_fds.synthetic.storage import digest
from .dataset import build_dataset, choose_recipe, select_purchases
from .protocol import BenchmarkPlan


INPUT_NAMES = frozenset({"plan.json", "observations.json", "semantic-observations.json", "selection.json"})
ANSWER_NAME = "evaluator-labels.json"
SCHEMA = "temporal-benchmark-inputs-v1"


def prepare(plan: BenchmarkPlan, output: Path) -> dict:
    from .semantic_cases import build_semantic_case

    plan = BenchmarkPlan.model_validate(plan.model_dump())
    check_new_output(output)
    observations, evaluator = build_dataset(plan)
    selected = select_purchases(observations, plan)
    index = ReplayIndex(observations)
    semantic, expected = {}, {}
    for pid in selected:
        observation, target = build_semantic_case(index.feature_input(pid), choose_recipe(pid, plan.semantic_seed))
        semantic[pid] = observation.model_dump(mode="json")
        expected[pid] = target.model_dump(mode="json")
    evaluator["semantic"] = expected
    artifacts = {
        "plan.json": plan.model_dump(mode="json"), "observations.json": observations,
        "semantic-observations.json": semantic, "selection.json": list(selected), ANSWER_NAME: evaluator,
    }
    output.mkdir(parents=True, exist_ok=False)
    metadata = dict(schema_version=SCHEMA, origin="SYNTHETIC", plan_sha256=personalization_hash(plan),
        generation_code={name: digest(Path(__file__).parent / name)
                         for name in ("protocol.py", "dataset.py", "semantic_cases.py", "preparation.py")},
        generator_sha256=digest(Path(__file__).parents[1] / "synthetic" / "generator.py"),
        semantic_origin="DECLARED_SYNTHETIC_RECIPE_NOT_RECOVERED_USER_TEXT",
        human_review="NOT_PERFORMED", model_scored=False, external_api="NOT_RUN")
    write_artifacts(output, artifacts, metadata)
    return dict(status="PREPARED", purchases=len(selected), users=len(observations["users"]),
        plan_sha256=metadata["plan_sha256"], data_role=plan.data_role, suite=plan.suite,
        model_scored=False, external_api="NOT_RUN", human_review="NOT_PERFORMED")


def _manifest(source: Path) -> dict:
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    if manifest["schema_version"] != SCHEMA or manifest["origin"] != "SYNTHETIC":
        raise ValueError("unsupported temporal benchmark source")
    if set(manifest["artifact_sha256"]) != INPUT_NAMES | {ANSWER_NAME}:
        raise ValueError("unexpected benchmark input inventory")
    return manifest


def _read(source: Path, name: str, manifest: dict):
    if name not in INPUT_NAMES | {ANSWER_NAME}:
        raise ValueError("unknown benchmark artifact")
    if digest(source / name) != manifest["artifact_sha256"][name]:
        raise ValueError("benchmark artifact changed")
    return json.loads((source / name).read_text(encoding="utf-8"))


def read_inputs(source: Path):
    """The execution reader deliberately never opens evaluator-labels.json."""
    from .semantic_cases import SemanticObservation, bind_semantic_observation

    manifest = _manifest(source)
    plan = BenchmarkPlan.model_validate(_read(source, "plan.json", manifest))
    if personalization_hash(plan) != manifest["plan_sha256"]:
        raise ValueError("frozen plan hash mismatch")
    observations = _read(source, "observations.json", manifest)
    if set(observations) != set(OBSERVABLE_TABLES):
        raise ValueError("execution source must contain only observable tables")
    selected = tuple(_read(source, "selection.json", manifest))
    if selected != select_purchases(observations, plan):
        raise ValueError("selection must reproduce from arrival times and identities")
    raw = _read(source, "semantic-observations.json", manifest)
    if set(raw) != set(selected):
        raise ValueError("semantic observations must cover exactly the selected purchases")
    semantic = {pid: SemanticObservation.model_validate(raw[pid]) for pid in selected}
    index = ReplayIndex(observations)
    for pid in selected:
        bind_semantic_observation(index.feature_input(pid), semantic[pid])
    return plan, observations, semantic, selected


def read_targets(source: Path) -> dict:
    """Evaluator-only entry point, called after predictions have been persisted."""
    return _read(source, ANSWER_NAME, _manifest(source))


def verify_inputs(source: Path) -> dict:
    plan, observations, _, selected = read_inputs(source)
    targets = read_targets(source)
    if set(selected) != {row["purchase_request_id"] for row in targets["truth"]}:
        raise ValueError("truth coverage differs from selected purchases")
    if set(targets["semantic"]) != set(selected):
        raise ValueError("semantic label coverage differs from selected purchases")
    return dict(status="VERIFIED", purchases=len(selected), users=len(observations["users"]),
                plan_sha256=personalization_hash(plan), model_scored=False, external_api="NOT_RUN")
