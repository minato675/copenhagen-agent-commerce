#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
export CAMERA_BACKEND="${1:-usb}"
[[ -x .venv/bin/python ]] || { echo 'Run bash setup.sh usb|csi first'; exit 1; }
exec .venv/bin/python server.py
