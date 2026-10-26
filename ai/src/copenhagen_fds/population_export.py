"""Export population profiles from explicit cohort membership and learning samples only."""

import argparse
import hashlib
import json
import sys
from collections import Counter
from datetime import timedelta, timezone
from pathlib import Path
from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from copenhagen_fds.contracts.learning_replay import LearningSample
from copenhagen_fds.contracts.population_baselines import PopulationBaselineRequest
from copenhagen_fds.contracts.population_profiles import (
    CohortDefinition, PopulationProfileConfig, PopulationProfileRequest, PopulationProfileSnapshot,
)
from copenhagen_fds.contracts.profiles import OriginValue
from copenhagen_fds.contracts.results import Timestamp
from copenhagen_fds.contracts.values import Array, Identifier, InputModel
from copenhagen_fds.personal_profiles import canonical_json, content_hash
from copenhagen_fds.population_baselines import derive_population_baseline
from copenhagen_fds.population_profiles import build_population_profile


class PopulationExportManifest(InputModel):
    schema_version: Literal["population-export-manifest-v1"] = "population-export-manifest-v1"
    origin: OriginValue
    source_reference: Identifier
    as_of: Timestamp
    config: PopulationProfileConfig = PopulationProfileConfig()
    cohorts: Annotated[Array[CohortDefinition], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_cohorts(self) -> Self:
        keys = [(cohort.cohort_id, cohort.currency) for cohort in self.cohorts]
        if len(set(keys)) != len(keys):
            raise ValueError("export requires unique cohort and currency pairs")
        if any(cohort.available_at > self.as_of for cohort in self.cohorts):
            raise ValueError("cohort membership must be available by the learning cutoff")
        return self


def export_population_profiles(
    sample_path: Path, manifest_path: Path, output: Path, *, aggregation: str | None = None,
) -> dict:
    """Keep immutable content versions and write into a fresh output directory."""
    manifest = PopulationExportManifest.model_validate_json(manifest_path.read_text(encoding="utf-8"))
    config = manifest.config if aggregation is None else PopulationProfileConfig.model_validate({
        **manifest.config.model_dump(mode="python"), "aggregation": aggregation,
    })
    cutoff = manifest.as_of.astimezone(timezone.utc)
    samples = tuple(LearningSample.model_validate_json(line) for line in sample_path.read_text(encoding="utf-8").splitlines())
    if any((sample.eligibility.as_of, sample.origin, sample.source_reference) != (
        cutoff, manifest.origin, manifest.source_reference,
    ) for sample in samples):
        raise ValueError("learning samples must match the manifest cutoff and provenance")
    snapshots, derivations = [], []
    for cohort in sorted(manifest.cohorts, key=lambda item: (item.cohort_id, item.currency)):
        profile_id = "population-" + content_hash([cohort.cohort_id, cohort.currency])
        snapshot = build_population_profile(PopulationProfileRequest(
            cohort=cohort, reference={"scope": "POPULATION", "cohort_id": cohort.cohort_id,
                                      "profile_id": profile_id, "version": "pending"},
            state_version="pending", origin=manifest.origin, source_reference=manifest.source_reference,
            as_of=cutoff, generated_at=cutoff, valid_until=cutoff + timedelta(days=1), config=config,
        ), samples)
        version = "empirical-population-v1-" + content_hash({
            "input": snapshot.input_sha256, "config": snapshot.config_sha256, "cohort": snapshot.cohort_sha256,
            "as_of": cutoff.isoformat(), "origin": manifest.origin, "source": manifest.source_reference,
        })
        data = snapshot.model_dump(mode="python")
        data["profile"]["reference"]["version"] = version
        data["state"]["profile_reference"]["version"] = version
        data["state"]["state_version"] = version
        snapshot = PopulationProfileSnapshot.model_validate(data)
        snapshots.append(snapshot)
        derivations.append(derive_population_baseline(snapshot, PopulationBaselineRequest(
            expected_source_reference=snapshot.profile.reference, currency=cohort.currency, available_at=cutoff,
        )))
    output.mkdir(parents=True, exist_ok=False)
    artifacts = {"population_profiles.jsonl": snapshots, "baseline_derivations.jsonl": derivations,
                 "population_baselines.jsonl": [item.baseline for item in derivations if item.baseline is not None]}
    hashes = {}
    for name, values in artifacts.items():
        path = output / name
        encoded = "".join(canonical_json(value.model_dump(mode="json")) + "\n" for value in values).encode("utf-8")
        path.write_bytes(encoded)
        hashes[name] = hashlib.sha256(encoded).hexdigest()
    report = {
        "status": "EXPORTED", "schema_version": "population-statistical-profile-v1",
        "algorithm_version": "empirical-population-v1", "as_of": cutoff.isoformat(),
        "origin": manifest.origin, "source_reference": manifest.source_reference,
        "config": config.model_dump(mode="json"), "profiles": len(snapshots),
        "available_baselines": sum(item.baseline is not None for item in derivations),
        "cohorts": [{
            "cohort_id": snapshot.cohort.cohort_id, "currency": snapshot.cohort.currency,
            "members": len(snapshot.cohort.member_user_ids),
            "long_term_contributors": sum(member.included_count > 0 for member in snapshot.long_term.members),
            "retained_samples": snapshot.long_term.retained_count, "included_samples": snapshot.long_term.included_count,
            "long_term_status": snapshot.long_term.status, "recent_status": snapshot.recent.status,
            "baseline_status": derived.status, "available_features": sum(f.parameter is not None for f in derived.features),
            "unavailable_reasons": dict(sorted(Counter(f.reason for f in derived.features if f.reason).items())),
            "profile_reference": snapshot.profile.reference.model_dump(mode="json"),
            "baseline_reference": derived.baseline.profile.reference.model_dump(mode="json") if derived.baseline else None,
        } for snapshot, derived in zip(snapshots, derivations)],
        "artifact_sha256": hashes, "model_scored": False,
        "note": "Explicit cohorts and empirical reference artifacts only; sample support and compatibility do not measure fraud detection accuracy.",
    }
    (output / "report.json").write_bytes((canonical_json(report) + "\n").encode("utf-8"))
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Fit explicit cohorts and export Story 4 reference artifacts")
    parser.add_argument("samples", type=Path)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--aggregation", choices=("TRANSACTION", "USER_EQUAL"))
    args = parser.parse_args(argv)
    try:
        report = export_population_profiles(args.samples, args.manifest, args.output, aggregation=args.aggregation)
    except (OSError, ValueError, TypeError):
        print("Population export failed: check the two input files, membership, cutoff, provenance, configuration and a new output directory.",
              file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
