#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[[ "$EUID" == 0 ]] || { echo 'Run: sudo bash install-service.sh'; exit 1; }
service_user="${SUDO_USER:-}"
[[ -n "$service_user" && "$service_user" != root ]] || { echo 'Use sudo from the regular Raspberry Pi user account.'; exit 1; }
[[ "$service_user" =~ ^[a-zA-Z_][a-zA-Z0-9_-]*\$?$ ]] || { echo 'Unsupported user name'; exit 1; }
[[ "$root_dir" =~ ^/[a-zA-Z0-9_./-]+$ ]] || { echo 'Use an installation path without spaces or special characters.'; exit 1; }
[[ -x "$root_dir/.venv/bin/python" && -x "$root_dir/bin/tunnel-client/tunnel-client" ]] || { echo 'Run bash setup.sh usb as your regular user first.'; exit 1; }
env_file=/etc/raspberrypi-camera.env
if [[ ! -e "$env_file" ]]; then
    install -o root -g root -m 600 "$root_dir/.env.example" "$env_file"
    echo 'Created /etc/raspberrypi-camera.env. Edit it with sudo nano, then run this installer again.'
    exit 1
fi
[[ -f "$env_file" && ! -L "$env_file" ]] || { echo 'Environment file must be a regular file, not a symlink.'; exit 1; }
chown root:root "$env_file"
chmod 600 "$env_file"
if grep -q 'REPLACE_ME' "$env_file"; then
    echo 'Replace the example values in /etc/raspberrypi-camera.env first.'
    exit 1
fi
unit_file=/etc/systemd/system/raspberrypi-camera.service
cat > "$unit_file" <<EOF
[Unit]
Description=Raspberry Pi Camera MCP and OpenAI Tunnel
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=$service_user
SupplementaryGroups=video
WorkingDirectory=$root_dir
EnvironmentFile=$env_file
Environment=PYTHONUNBUFFERED=1
ExecStart=$root_dir/.venv/bin/python $root_dir/connect.py --non-interactive
Restart=always
RestartSec=15
TimeoutStopSec=15
KillMode=control-group
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$unit_file"
systemd-analyze verify "$unit_file"
systemctl daemon-reload
systemctl enable raspberrypi-camera.service
systemctl restart raspberrypi-camera.service
echo 'Enabled at boot and started. Check: systemctl status raspberrypi-camera.service'
echo 'Connection readiness: curl http://127.0.0.1:18765/readyz'
