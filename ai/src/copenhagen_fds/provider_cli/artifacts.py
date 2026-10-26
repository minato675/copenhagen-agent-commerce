"""New-directory JSON artifacts with exact input bytes and verified inventories."""

from hashlib import sha256
import re

from copenhagen_fds.native_cli.runtime import fingerprint
from copenhagen_fds.native_cli.storage import check_new_output
from copenhagen_fds.personal_replay_export import read_json
from copenhagen_fds.synthetic.storage import canonical


def json_bytes(value):
    return (canonical(value) + "\n").encode("utf-8")


def read_bytes(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 16_000_000:
        raise ValueError("expected a bounded regular artifact")
    with path.open("rb") as stream:
        data = stream.read(16_000_001)
    if len(data) > 16_000_000:
        raise ValueError("artifact too large")
    return data


def safe_name(name):
    if not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]*\.json", name):
        raise ValueError("expected a flat JSON artifact name")
    return name


def reserve(output, *sources):
    check_new_output(output, *sources)
    output.mkdir(parents=True, exist_ok=False)


def write_bundle(output, artifacts, *, kind, **metadata):
    """Caller reserves first, including before model execution. Never overwrites."""
    hashes = {}
    for name, value in artifacts.items():
        safe_name(name)
        encoded = value if isinstance(value, bytes) else json_bytes(value)
        with (output / name).open("xb") as stream:
            stream.write(encoded)
        hashes[name] = sha256(encoded).hexdigest()
    manifest = dict(schema_version="fds-provider-demo-artifacts-v1", kind=kind, code=fingerprint(),
        mode="MOCK", external_api="NOT_RUN", **metadata, artifact_sha256=hashes)
    with (output / "manifest.json").open("xb") as stream:
        stream.write(json_bytes(manifest))
    return manifest


def read_bundle(source, *, kind, defer_expected=False):
    if source.is_symlink():
        raise ValueError("bundle cannot be a symlink")
    read_bytes(source / "manifest.json")
    manifest = read_json(source / "manifest.json")
    if (manifest["schema_version"] != "fds-provider-demo-artifacts-v1" or manifest["kind"] != kind or
        manifest["mode"] != "MOCK" or manifest["external_api"] != "NOT_RUN" or manifest["code"] != fingerprint()):
        raise ValueError("bundle requires its recorded code/runtime and offline mode")
    names = manifest["artifact_sha256"]
    if not isinstance(names, dict) or not names or len(names) > 512:
        raise ValueError("invalid artifact inventory")
    if {p.name for p in source.iterdir()} != {*names, "manifest.json"}:
        raise ValueError("unexpected or missing artifact")
    values = {}
    for name in names:
        safe_name(name)
        if defer_expected and name == "expected.json":
            continue  # Even the expected bytes are first read only after execution.
        values[name] = read_verified(source, name, manifest)
    return manifest, values


def read_verified(source, name, manifest):
    data = read_bytes(source / safe_name(name))
    if sha256(data).hexdigest() != manifest["artifact_sha256"][name]:
        raise ValueError("artifact content changed")
    return data
