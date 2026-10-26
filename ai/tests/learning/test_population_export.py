"""Real file boundaries, immutable population versions and readable export artifacts."""

import json
from datetime import timedelta
from pathlib import Path

import pytest

from copenhagen_fds.contracts.baselines import PopulationBaseline
from copenhagen_fds.contracts.population_baselines import PopulationBaselineResult
from copenhagen_fds.contracts.population_profiles import PopulationProfileSnapshot
from copenhagen_fds.population_export import export_population_profiles, main


@pytest.fixture
def population_files(population, tmp_path):
    p = population
    samples = tuple(p.sample(f"a-{i}", counts=i) for i in range(9)) + (
        p.sample("b", user="b", amount=1000, counts=4, at=p.cutoff - timedelta(days=1, hours=1)),)
    sample_path, manifest_path = tmp_path / "samples.jsonl", tmp_path / "cohort.json"
    sample_path.write_text("\n".join(sample.model_dump_json() for sample in samples) + "\n", encoding="utf-8")
    request = p.request().model_dump(mode="json")
    manifest = {key: request[key] for key in ("origin", "source_reference", "as_of", "config")}
    manifest["cohorts"] = [request["cohort"]]
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return sample_path, manifest_path


def test_export_artifacts_and_reordered_duplicate_input_are_byte_reproducible(population_files, tmp_path):
    samples, manifest = population_files
    first = export_population_profiles(samples, manifest, tmp_path / "first")
    assert first["profiles"] == first["available_baselines"] == 1 and first["model_scored"] is False
    assert first["cohorts"][0]["members"] == 3 and first["cohorts"][0]["included_samples"] == 10
    lines = samples.read_text(encoding="utf-8").splitlines()
    samples.write_text("\n".join([*reversed(lines), lines[0]]) + "\n", encoding="utf-8")
    data = json.loads(manifest.read_text(encoding="utf-8"))
    data["cohorts"][0]["member_user_ids"].reverse()
    manifest.write_text(json.dumps(data), encoding="utf-8")
    second = export_population_profiles(samples, manifest, tmp_path / "second")
    assert first == second
    for name in (*first["artifact_sha256"], "report.json"):
        assert (tmp_path / "first" / name).read_bytes() == (tmp_path / "second" / name).read_bytes()
    for name, model in (("population_profiles.jsonl", PopulationProfileSnapshot),
                        ("population_baselines.jsonl", PopulationBaseline), ("baseline_derivations.jsonl", PopulationBaselineResult)):
        restored = model.model_validate_json((tmp_path / "first" / name).read_text(encoding="utf-8"))
        assert restored is not None


def test_weighting_and_membership_changes_create_distinct_versions(population_files, tmp_path):
    samples, manifest = population_files
    equal = export_population_profiles(samples, manifest, tmp_path / "equal")
    pooled = export_population_profiles(samples, manifest, tmp_path / "pooled", aggregation="TRANSACTION")
    assert equal["cohorts"][0]["profile_reference"]["profile_id"] == pooled["cohorts"][0]["profile_reference"]["profile_id"]
    assert equal["cohorts"][0]["profile_reference"]["version"] != pooled["cohorts"][0]["profile_reference"]["version"]
    assert equal["cohorts"][0]["baseline_reference"]["version"] != pooled["cohorts"][0]["baseline_reference"]["version"]
    means = [PopulationProfileSnapshot.model_validate_json((tmp_path / name / "population_profiles.jsonl").read_text(encoding="utf-8"))
             .long_term.features[0].numeric.mean for name in ("equal", "pooled")]
    assert means == [550, 190]
    data = json.loads(manifest.read_text(encoding="utf-8"))
    data["cohorts"][0]["membership_version"] = "members-v2"
    manifest.write_text(json.dumps(data), encoding="utf-8")
    changed = export_population_profiles(samples, manifest, tmp_path / "changed")
    assert changed["cohorts"][0]["profile_reference"]["version"] != equal["cohorts"][0]["profile_reference"]["version"]


def test_cli_reads_only_explicit_files_and_preserves_existing_output(population_files, tmp_path, monkeypatch, capsys):
    samples, manifest = population_files
    original, reads = Path.read_text, []

    def tracked_read(self, *args, **kwargs):
        reads.append(self)
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", tracked_read)
    args = [str(samples), str(manifest), "--output", str(tmp_path / "out")]
    assert main(args) == 0
    assert json.loads(capsys.readouterr().out)["profiles"] == 1
    assert reads == [manifest, samples]
    before = (tmp_path / "out" / "population_profiles.jsonl").read_bytes()
    assert main(args) == 2
    assert "new output directory" in capsys.readouterr().err
    assert (tmp_path / "out" / "population_profiles.jsonl").read_bytes() == before


@pytest.mark.parametrize("mode", ["cutoff", "source", "truth", "future-membership", "duplicate-cohort"])
def test_export_rejects_incompatible_or_hidden_inputs_without_writing(population_files, tmp_path, mode):
    samples, manifest = population_files
    if mode in {"source", "cutoff", "truth"}:
        data = json.loads(samples.read_text(encoding="utf-8").splitlines()[0])
        if mode == "source":
            data["source_reference"] = "another-source"
        elif mode == "cutoff":
            data["eligibility"]["as_of"] = "2026-05-02T00:00:00+09:00"
        else:
            data["is_fraud"] = True
        samples.write_text(json.dumps(data), encoding="utf-8")
    else:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        if mode == "future-membership":
            data["cohorts"][0]["available_at"] = "2026-05-02T00:00:00+09:00"
        else:
            data["cohorts"].append(data["cohorts"][0])
        manifest.write_text(json.dumps(data), encoding="utf-8")
    with pytest.raises(ValueError):
        export_population_profiles(samples, manifest, tmp_path / "invalid")
    assert not (tmp_path / "invalid").exists()


def test_explicit_empty_membership_retains_diagnostics_without_inventing_baselines(population_files, tmp_path):
    samples, manifest = population_files
    samples.write_text("", encoding="utf-8")
    result = export_population_profiles(samples, manifest, tmp_path / "empty")
    assert result["available_baselines"] == 0
    assert result["cohorts"][0]["long_term_status"] == "EMPTY"
    assert result["cohorts"][0]["unavailable_reasons"] == {"EMPTY": 9}
    assert (tmp_path / "empty" / "population_baselines.jsonl").read_bytes() == b""
