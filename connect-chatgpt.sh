#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
[[ -x .venv/bin/python ]] || { echo 'Run bash setup.sh usb|csi first'; exit 1; }
exec .venv/bin/python connect.py "${1:-usb}"
