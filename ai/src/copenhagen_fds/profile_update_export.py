"""Offline JSON entry points for immutable profile initialization, updates and replay."""

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from pydantic import TypeAdapter

from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.profile_updates import ProfileSnapshot, ProfileUpdateRequest, ProfileUpdateState
from copenhagen_fds.personal_profiles import canonical_json
from copenhagen_fds.profile_update_replay import replay_profile_update
from copenhagen_fds.profile_updates import initialize_profile_update, update_profile


def _write_artifacts(output, artifacts, report):
    """Validate/compute first, then reserve a fresh output directory; never overwrite input."""
    encoded = {name: (canonical_json(value.model_dump(mode="json") if hasattr(value, "model_dump") else value) + "\n").encode("utf-8")
               for name, value in artifacts.items()}
    report["artifact_sha256"] = {name: hashlib.sha256(value).hexdigest() for name, value in encoded.items()}
    output.mkdir(parents=True, exist_ok=False)
    for name, value in encoded.items():
        (output / name).write_bytes(value)
    (output / "report.json").write_bytes((canonical_json(report) + "\n").encode("utf-8"))
    return report


def export_initial_update_state(snapshot_path: Path, output: Path, *, profile_id: str | None = None) -> dict:
    """Read a snapshot JSON or JSONL; a multi-profile file requires an explicit ID."""
    adapter = TypeAdapter(ProfileSnapshot)
    raw = snapshot_path.read_text(encoding="utf-8")
    try:
        snapshots = (adapter.validate_python(json.loads(raw)),)
    except json.JSONDecodeError:
        snapshots = tuple(adapter.validate_json(line) for line in raw.splitlines() if line.strip())
    selected = tuple(item for item in snapshots if profile_id is None or item.profile.reference.profile_id == profile_id)
    if len(selected) != 1:
        raise ValueError("select exactly one fitted profile by its profile_id")
    state = initialize_profile_update(selected[0])
    return _write_artifacts(output, {"state.json": state}, {
        "status": "INITIALIZED", "state_version": state.state_version,
        "profile_reference": state.snapshot.profile.reference.model_dump(mode="json"),
        "as_of": state.snapshot.profile.as_of.isoformat(), "retained_samples": state.snapshot.long_term.retained_count,
        "model_scored": False,
    })


def export_profile_update(state_path: Path, request_path: Path, output: Path, *, replay_samples: Path | None = None) -> dict:
    state = ProfileUpdateState.model_validate_json(state_path.read_text(encoding="utf-8"))
    request = ProfileUpdateRequest.model_validate_json(request_path.read_text(encoding="utf-8"))
    comparison = None
    if replay_samples is None:
        result = update_profile(state, request)
    else:
        samples = tuple(LearningSample.model_validate_json(line) for line in replay_samples.read_text(encoding="utf-8").splitlines()
                        if line.strip())
        result, comparison = replay_profile_update(state, request, samples)
    verified = comparison is None or comparison["matched"]
    artifacts = {"request.json": request, "result.json": result}
    if result.state is not None and verified:
        artifacts["state.json"] = result.state
    report = {
        "status": result.status if verified else "VERIFICATION_FAILED", "issue": result.issue,
        "previous_state_version": result.previous_state_version,
        "state_version": result.state.state_version if result.state is not None and verified else None,
        "events": dict(sorted(Counter(item.outcome for item in result.events).items())),
        "event_learning_status": dict(sorted(Counter(item.learning_status for item in result.events if item.learning_status).items())),
        "full_replay_comparison": comparison, "model_scored": False,
    }
    return _write_artifacts(output, artifacts, report)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Initialize or update one fitted personal/population profile offline")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="Initialize revision state from a complete fitted snapshot")
    init.add_argument("snapshot", type=Path)
    init.add_argument("--profile-id")
    init.add_argument("--output", type=Path, required=True)
    for name in ("apply", "replay"):
        command = commands.add_parser(name, help="Apply explicit events" if name == "apply" else "Diff a COMPLETE final learning replay")
        command.add_argument("state", type=Path)
        command.add_argument("request", type=Path)
        if name == "replay":
            command.add_argument("samples", type=Path)
        command.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            report = export_initial_update_state(args.snapshot, args.output, profile_id=args.profile_id)
        else:
            report = export_profile_update(args.state, args.request, args.output,
                                           replay_samples=args.samples if args.command == "replay" else None)
    except (OSError, ValueError, TypeError):
        print("Profile update export failed: check explicit versions, cutoff, complete replay inputs and a new output directory.", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if report["status"] in {"INITIALIZED", "UPDATED", "UNCHANGED"} else 3


if __name__ == "__main__":
    raise SystemExit(main())
