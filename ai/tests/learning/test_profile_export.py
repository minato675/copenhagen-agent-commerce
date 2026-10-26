"""The runnable profile path reads only validated learning samples and preserves replay bytes."""

import json
from pathlib import Path

import pytest

from copenhagen_fds.contracts.personal_profiles import PersonalProfileConfig, PersonalProfileSnapshot
from copenhagen_fds.learning_replay import replay_learning_samples
from copenhagen_fds.profile_export import export_personal_profiles, main
from copenhagen_fds.synthetic.generator import GeneratorConfig, generate
from copenhagen_fds.synthetic.learning import synthetic_learning_inputs


@pytest.fixture
def sample_file(tmp_path):
    config = GeneratorConfig(users=12, days=12, train_days=4, validation_days=4)
    data = generate(config)
    cutoff = "2026-01-13T00:00:00+09:00"
    inputs = synthetic_learning_inputs(data, as_of=cutoff)
    samples = replay_learning_samples(inputs, origin="SYNTHETIC", source_reference="frozen-observations")
    path = tmp_path / "learning_samples.jsonl"
    path.write_text("\n".join(sample.model_dump_json() for sample in samples) + "\n", encoding="utf-8")
    return path, cutoff


def test_frozen_learning_to_profiles_is_reproducible_and_roundtrips(sample_file, tmp_path):
    path, cutoff = sample_file
    result = export_personal_profiles(path, tmp_path / "first", as_of=cutoff)
    assert result["status"] == "EXPORTED" and result["profiles"] == 12
    assert result["model_scored"] is False and result["included_samples"] > 0
    # Duplicates and input row order must not affect values, hashes, or versions.
    lines = path.read_text(encoding="utf-8").splitlines()
    path.write_text("\n".join([*reversed(lines), lines[0]]) + "\n", encoding="utf-8")
    again = export_personal_profiles(path, tmp_path / "second", as_of=cutoff)
    assert again == result
    for name in ("personal_profiles.jsonl", "report.json"):
        assert (tmp_path / "first" / name).read_bytes() == (tmp_path / "second" / name).read_bytes()
    profiles = [PersonalProfileSnapshot.model_validate_json(line) for line in
                (tmp_path / "first" / "personal_profiles.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len({(profile.profile.reference.user_id, profile.profile.currency) for profile in profiles}) == 12
    assert all(profile.profile.reference.version.startswith("empirical-personal-v1-") for profile in profiles)


def test_cli_reads_only_learning_file_and_does_not_overwrite(sample_file, tmp_path, monkeypatch, capsys):
    path, cutoff = sample_file
    original = Path.read_text
    reads = []

    def tracked_read(self, *args, **kwargs):
        reads.append(self)
        return original(self, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", tracked_read)
    args = [str(path), "--as-of", cutoff, "--output", str(tmp_path / "out")]
    assert main(args) == 0
    assert json.loads(capsys.readouterr().out)["profiles"] == 12
    assert reads == [path]
    before = (tmp_path / "out" / "personal_profiles.jsonl").read_bytes()
    assert main(args) == 2
    assert "new output directory" in capsys.readouterr().err
    assert (tmp_path / "out" / "personal_profiles.jsonl").read_bytes() == before


def test_changed_config_produces_a_different_immutable_profile_version(sample_file, tmp_path):
    path, cutoff = sample_file
    versions = []
    for minimum in (5, 10):
        destination = tmp_path / str(minimum)
        export_personal_profiles(path, destination, as_of=cutoff, config=PersonalProfileConfig(min_samples=minimum))
        profile = PersonalProfileSnapshot.model_validate_json(
            (destination / "personal_profiles.jsonl").read_text(encoding="utf-8").splitlines()[0])
        versions.append((profile.profile.reference.profile_id, profile.profile.reference.version, profile.config_sha256))
    assert versions[0][0] == versions[1][0]
    assert versions[0][1] != versions[1][1] and versions[0][2] != versions[1][2]


@pytest.mark.parametrize("mode", ["future-cutoff", "truth-field", "empty"])
def test_export_rejects_mismatched_or_noncontract_input(sample_file, tmp_path, mode):
    path, cutoff = sample_file
    if mode == "future-cutoff":
        cutoff = "2026-01-14T00:00:00+09:00"
    elif mode == "truth-field":
        row = json.loads(path.read_text(encoding="utf-8").splitlines()[0])
        row["is_fraud"] = True
        path.write_text(json.dumps(row), encoding="utf-8")
    else:
        path.write_text("", encoding="utf-8")
    with pytest.raises(ValueError):
        export_personal_profiles(path, tmp_path / "rejected", as_of=cutoff)
    assert not (tmp_path / "rejected").exists()
