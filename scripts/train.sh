#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   bash scripts/train.sh <physical-gpu-id> [train.py arguments]
#   GPU_DEVICE=<physical-gpu-id> bash scripts/train.sh [train.py arguments]

if [[ $# -gt 0 && "$1" =~ ^[0-9]+$ ]]; then
    GPU_DEVICE="$1"
    shift
else
    GPU_DEVICE="${GPU_DEVICE:-}"
fi

if [[ -z "$GPU_DEVICE" ]]; then
    echo "ERROR: GPU device is required as the first argument or GPU_DEVICE environment variable." >&2
    exit 2
fi

if [[ ! "$GPU_DEVICE" =~ ^[0-9]+$ ]]; then
    echo "ERROR: GPU device must be a non-negative integer; received '$GPU_DEVICE'." >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"

RUN_TIMESTAMP="$(date -u +%Y%m%d_%H%M%S)"
LOG_FILE="$LOG_DIR/train_${RUN_TIMESTAMP}_gpu${GPU_DEVICE}.log"

echo "Selected physical GPU: $GPU_DEVICE"
echo "CUDA_DEVICE_ORDER: PCI_BUS_ID"
echo "Log file: $LOG_FILE"
echo "Command: python -u src/train.py $*"

set +e
CUDA_DEVICE_ORDER=PCI_BUS_ID \
CUDA_VISIBLE_DEVICES="$GPU_DEVICE" \
PYTHONUNBUFFERED=1 \
python -u "$PROJECT_ROOT/src/train.py" "$@" 2>&1 | tee "$LOG_FILE"
TRAIN_STATUS="${PIPESTATUS[0]}"
set -e

echo "Training command exit code: $TRAIN_STATUS"
exit "$TRAIN_STATUS"
