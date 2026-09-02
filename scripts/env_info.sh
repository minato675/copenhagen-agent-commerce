#!/usr/bin/env bash
set -euo pipefail

echo "===== ENVIRONMENT INFO ====="
echo "hostname: $(hostname)"
echo "whoami: $(whoami)"
echo "pwd: $(pwd)"
echo "python: $(command -v python || true)"
python --version 2>&1 || true
echo "pip: $(command -v pip || true)"
python -m pip --version 2>&1 || true

if command -v conda >/dev/null 2>&1; then
    echo "conda: $(command -v conda)"
    conda --version
    echo "conda environment: ${CONDA_DEFAULT_ENV:-<not active>}"
else
    echo "conda: not found"
fi

python - <<'PY'
try:
    import torch

    print(f"torch: {torch.__version__}")
    print(f"torch CUDA: {torch.version.cuda}")
    print(f"CUDA available: {torch.cuda.is_available()}")
    print(f"visible GPU count: {torch.cuda.device_count()}")
except ImportError:
    print("torch: not installed")
PY

echo "===== NVIDIA-SMI ====="
if command -v nvidia-smi >/dev/null 2>&1; then
    nvidia-smi
else
    echo "nvidia-smi: not found"
fi

echo "===== CPU ====="
if command -v lscpu >/dev/null 2>&1; then
    lscpu | grep -E '^(Model name|CPU\(s\)|Thread|Core|Socket)' || true
else
    getconf _NPROCESSORS_ONLN 2>/dev/null || true
fi

echo "===== RAM ====="
if command -v free >/dev/null 2>&1; then
    free -h
else
    grep -E 'MemTotal|MemAvailable' /proc/meminfo 2>/dev/null || true
fi

echo "===== DISK ====="
df -h
