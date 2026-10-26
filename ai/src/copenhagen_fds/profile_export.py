"""Fit frozen personal profiles from a learning sample JSONL, without source labels or I/O services."""

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from datetime import timedelta, timezone
from pathlib import Path

from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.personal_profiles import PersonalProfileConfig, PersonalProfileRequest
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import InputModel
from copenhagen_fds.personal_profiles import build_personal_profile, canonical_json, content_hash


class _ExportOptions(InputModel):
    as_of: Timestamp
    config: PersonalProfileConfig = PersonalProfileConfig()


def export_personal_profiles(
    sample_path: Path, output: Path, *, as_of: Timestamp,
    config: PersonalProfileConfig = PersonalProfileConfig(),
) -> dict:
    """Content-derived versions, explicit cutoff, and fresh output directories only."""
    options = _ExportOptions(as_of=as_of, config=config)
    cutoff = options.as_of.astimezone(timezone.utc)
    groups = defaultdict(list)
    for line in sample_path.read_text(encoding="utf-8").splitlines():
        sample = LearningSample.model_validate_json(line)
        if sample.eligibility.as_of != cutoff:
            raise ValueError("sample file must match the requested learning cutoff")
        target = sample.eligibility.target
        groups[target.user_id, target.currency].append(sample)
    if not groups:
        raise ValueError("profile export requires at least one identified purchase sample")
    profiles = []
    for (user_id, currency), samples in sorted(groups.items()):
        # A purchase's source must be consistent; the builder rejects mixed provenance.
        first = samples[0]
        identity = "personal-" + content_hash([user_id, currency])
        common = dict(user_id=user_id, currency=currency, origin=first.origin,
                      source_reference=first.source_reference, as_of=cutoff, generated_at=cutoff,
                      valid_until=cutoff + timedelta(days=1), config=options.config)
        # Fit once, then assign immutable content versions to the resulting snapshot.
        draft = build_personal_profile(PersonalProfileRequest(
            **common, reference={"scope": "PERSONAL", "user_id": user_id, "profile_id": identity, "version": "pending"},
            state_version="pending",
        ), tuple(samples))
        version = "empirical-personal-v1-" + content_hash({
            "input": draft.input_sha256, "config": draft.config_sha256,
            "as_of": cutoff.isoformat(), "source": first.source_reference, "origin": first.origin,
        })
        data = draft.model_dump(mode="python")
        data["profile"]["reference"]["version"] = version
        data["state"]["profile_reference"]["version"] = version
        data["state"]["state_version"] = version
        profiles.append(type(draft).model_validate(data))
    output.mkdir(parents=True, exist_ok=False)
    path = output / "personal_profiles.jsonl"
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for profile in profiles:
            handle.write(canonical_json(profile.model_dump(mode="json")) + "\n")
    report = {
        "status": "EXPORTED", "schema_version": "personal-statistical-profile-v1",
        "algorithm_version": "empirical-personal-v1", "as_of": cutoff.isoformat(),
        "config": options.config.model_dump(mode="json"), "profiles": len(profiles),
        "long_term_status": dict(sorted(Counter(profile.long_term.status for profile in profiles).items())),
        "recent_status": dict(sorted(Counter(profile.recent.status for profile in profiles).items())),
        "retained_samples": sum(profile.long_term.retained_count for profile in profiles),
        "included_samples": sum(profile.long_term.included_count for profile in profiles),
        "profiles_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "model_scored": False,
        "note": "Empirical personal distributions only. READY denotes sample count, not detection accuracy or collection completeness.",
    }
    (output / "report.json").write_text(canonical_json(report) + "\n", encoding="utf-8")
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Fit personal statistics from frozen learning samples")
    parser.add_argument("samples", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--as-of", required=True)
    parser.add_argument("--recent-days", type=int, default=7)
    parser.add_argument("--long-term-days", type=int, default=90)
    parser.add_argument("--min-samples", type=int, default=5)
    args = parser.parse_args(argv)
    try:
        config = PersonalProfileConfig(recent_days=args.recent_days, long_term_days=args.long_term_days,
                                       min_samples=args.min_samples)
        result = export_personal_profiles(args.samples, args.output, as_of=args.as_of, config=config)
    except (OSError, ValueError, TypeError):
        print("Personal profile export failed: check samples, cutoff, configuration and use a new output directory.", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
