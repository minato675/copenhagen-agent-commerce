"""Install the bundled official ARM64 release only after pinned SHA256 validation."""
import hashlib
import os
from pathlib import Path
import platform
import zipfile

EXPECTED = 'c51bfd883fc22e3445494a03c0179875176564bde470661b308fd83af5d01abb'
ROOT = Path(__file__).resolve().parent


def install():
    if platform.system() != 'Linux' or platform.machine() not in ('aarch64', 'arm64'):
        raise RuntimeError('Linux ARM64 required.')
    archive = ROOT/'bin'/'linux-arm64.zip'
    if hashlib.sha256(archive.read_bytes()).hexdigest() != EXPECTED:
        raise RuntimeError('Tunnel archive checksum mismatch. Use the original package.')
    target = (ROOT/'bin'/'tunnel-client').resolve()
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            destination = (target/item.filename).resolve()
            if not destination.is_relative_to(target):
                raise RuntimeError('Unsafe archive member')
        source.extractall(target)
    for path in target.rglob('*'):
        if path.is_file() and path.name in ('tunnel-client', 'cloudflared'):
            path.chmod(0o755)
    matches = list(target.rglob('tunnel-client'))
    binaries = [p for p in matches if p.is_file()]
    if len(binaries) != 1:
        raise RuntimeError('Unexpected tunnel archive layout')
    print('Verified and installed OpenAI tunnel-client v0.0.15 (Linux ARM64).')


if __name__ == '__main__':
    install()
