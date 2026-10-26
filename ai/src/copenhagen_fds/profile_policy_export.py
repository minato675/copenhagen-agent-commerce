"""Offline policy evidence export and immutable policy profile commands."""

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from pydantic import TypeAdapter

from copenhagen_fds.contracts.profile_policy import PolicyObservation, ProfilePolicyConfig
from copenhagen_fds.contracts.profile_policy_state import ProfilePolicyState, ProfilePolicyUpdateRequest
from copenhagen_fds.contracts.profile_updates import ProfileSnapshot
from copenhagen_fds.personal_profiles import canonical_json
from copenhagen_fds.profile_policy_replay import policy_observations_from_replay
from copenhagen_fds.profile_policy_updates import initialize_profile_policy, update_profile_policy
from copenhagen_fds.profile_update_export import _write_artifacts
from copenhagen_fds.synthetic.learning import read_learning_observations, synthetic_learning_inputs


def read_policy_observations(path):
    return tuple(PolicyObservation.model_validate_json(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())


def export_policy_observations(dataset: Path, output: Path, *, as_of):
    data, source = read_learning_observations(dataset)
    rows = policy_observations_from_replay(synthetic_learning_inputs(data, as_of=as_of), origin="SYNTHETIC", source_reference=source)
    encoded = ("".join(canonical_json(row.model_dump(mode="json")) + "\n" for row in rows)).encode("utf-8")
    report = {"status": "EXPORTED", "source_reference": source, "observations": len(rows),
              "observations_sha256": hashlib.sha256(encoded).hexdigest(), "model_scored": False}
    output.mkdir(parents=True, exist_ok=False)
    (output / "observations.jsonl").write_bytes(encoded)
    (output / "report.json").write_text(canonical_json(report) + "\n", encoding="utf-8")
    return report


def _summary(state):
    decisions = state.evaluation.decisions
    return {"state_version": state.state_version, "published_reference": state.published.profile.reference.model_dump(mode="json"),
            "policy_version": state.evaluation.policy_version, "config": state.evaluation.config.model_dump(mode="json"),
            "config_sha256": state.evaluation.config_sha256, "as_of": state.evaluation.as_of.isoformat(),
            "policy_status": dict(sorted(Counter(d.status for d in decisions).items())),
            "policy_reasons": dict(sorted(Counter(d.reason for d in decisions).items())),
            "policy_held_confirmed": sum(d.raw_status == "INCLUDE" and d.status == "DEFER" for d in decisions),
            "published_long_included": state.published.long_term.included_count,
            "published_recent_included": state.published.recent.included_count, "model_scored": False}


def export_policy_initial(snapshot_path: Path, observations_path: Path, output: Path, *, config=None, profile_id=None):
    raw = snapshot_path.read_text(encoding="utf-8")
    adapter = TypeAdapter(ProfileSnapshot)
    try:
        snapshots = (adapter.validate_python(json.loads(raw)),)
    except json.JSONDecodeError:
        snapshots = tuple(adapter.validate_json(line) for line in raw.splitlines() if line.strip())
    selected = [s for s in snapshots if profile_id is None or s.profile.reference.profile_id == profile_id]
    if len(selected) != 1:
        raise ValueError("select one candidate snapshot by profile identity")
    state = initialize_profile_policy(selected[0], read_policy_observations(observations_path), config or ProfilePolicyConfig())
    return _write_artifacts(output, {"state.json": state, "published_profile.json": state.published,
                                   "policy_evaluation.json": state.evaluation}, {"status": "INITIALIZED", **_summary(state)})


def export_policy_update(state_path: Path, request_path: Path, observations_path: Path, output: Path):
    state = ProfilePolicyState.model_validate_json(state_path.read_text(encoding="utf-8"))
    request = ProfilePolicyUpdateRequest.model_validate_json(request_path.read_text(encoding="utf-8"))
    result = update_profile_policy(state, request, read_policy_observations(observations_path))
    changes = {"candidate_events": [e.model_dump(mode="json") for e in result.candidate_events],
               "policy_transitions": [t.model_dump(mode="json") for t in result.transitions]}
    artifacts = {"request.json": request, "changes.json": changes}
    report = {"status": result.status, "issue": result.issue, "previous_state_version": result.previous_state_version,
              "previous_published_reference": result.previous_published_reference.model_dump(mode="json"), "model_scored": False}
    if result.state is not None:
        artifacts.update({"state.json": result.state, "published_profile.json": result.state.published,
                          "policy_evaluation.json": result.state.evaluation})
        report.update(_summary(result.state))
    return _write_artifacts(output, artifacts, report)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Export observable review evidence and apply profile admission policy")
    commands = parser.add_subparsers(dest="command", required=True)
    observe = commands.add_parser("observe")
    observe.add_argument("dataset", type=Path)
    observe.add_argument("--as-of", required=True)
    init = commands.add_parser("init")
    init.add_argument("snapshot", type=Path)
    init.add_argument("observations", type=Path)
    init.add_argument("--config", type=Path)
    init.add_argument("--profile-id")
    update = commands.add_parser("update")
    update.add_argument("state", type=Path)
    update.add_argument("request", type=Path)
    update.add_argument("observations", type=Path)
    for command in (observe, init, update):
        command.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "observe":
            report = export_policy_observations(args.dataset, args.output, as_of=args.as_of)
        elif args.command == "init":
            config = ProfilePolicyConfig.model_validate_json(args.config.read_text(encoding="utf-8")) if args.config else None
            report = export_policy_initial(args.snapshot, args.observations, args.output, config=config, profile_id=args.profile_id)
        else:
            report = export_policy_update(args.state, args.request, args.observations, args.output)
    except (OSError, ValueError, TypeError):
        print("Policy export failed: check complete observations, review evidence, versions, cutoff and a new output directory.", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if report["status"] in {"EXPORTED", "INITIALIZED", "UPDATED", "UNCHANGED"} else 3


if __name__ == "__main__":
    raise SystemExit(main())
