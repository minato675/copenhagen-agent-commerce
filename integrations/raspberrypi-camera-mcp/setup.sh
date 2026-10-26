#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
backend="${1:-usb}"
[[ "$backend" == usb || "$backend" == csi ]] || { echo 'Usage: bash setup.sh usb|csi'; exit 1; }
[[ "$(uname -s)" == Linux && "$(uname -m)" == aarch64 && "$(getconf LONG_BIT)" == 64 ]] || { echo 'Raspberry Pi OS 64-bit (aarch64) required.'; exit 1; }
[[ "$EUID" != 0 ]] || { echo 'Run as a regular user; sudo is requested for apt only.'; exit 1; }
sudo apt-get update
packages=(python3 python3-venv python3-pip python3-opencv python3-pil v4l-utils)
[[ "$backend" != csi ]] || packages+=(rpicam-apps)
sudo apt-get install -y "${packages[@]}"
python3 -c 'import sys; assert sys.version_info >= (3,11), "Python 3.11+ required"'
python3 -m venv --system-site-packages .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python install_tunnel.py
.venv/bin/python -c 'import cv2; import mcp; print("Python packages ready")'
printf '\nSetup complete. Start: bash connect-chatgpt.sh %s\n' "$backend"
echo 'If /dev/video access is denied: sudo usermod -aG video "$USER", then log out/in.'
