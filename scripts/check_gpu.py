"""Report PyTorch/CUDA visibility and run a tiny CUDA tensor smoke test.

The physical GPU is selected outside Python with CUDA_VISIBLE_DEVICES.  When
one physical GPU is made visible, PyTorch intentionally enumerates it as
``cuda:0`` inside the process; this script does not hard-code a physical GPU
number.
"""

from __future__ import annotations

import os
import sys


def main() -> int:
    """Print GPU diagnostics and return zero even when CUDA is unavailable."""

    try:
        import torch
    except ImportError:
        print("[GPU CHECK] PyTorch is not installed in the active environment.")
        print("[GPU CHECK] Install the project environment before running CUDA tests.")
        return 0

    print(f"PyTorch: {torch.__version__}")
    print(f"CUDA available: {torch.cuda.is_available()}")
    print(f"Torch CUDA version: {torch.version.cuda}")
    print(f"CUDA_VISIBLE_DEVICES: {os.environ.get('CUDA_VISIBLE_DEVICES', '<not set>')}")

    visible_count = torch.cuda.device_count()
    print(f"Visible GPU count: {visible_count}")
    for index in range(visible_count):
        print(f"Visible GPU {index}: {torch.cuda.get_device_name(index)}")

    if not torch.cuda.is_available() or visible_count == 0:
        print("[GPU CHECK] CUDA is unavailable; CPU fallback is available.")
        if not torch.cuda.is_available():
            print("[GPU CHECK] Check the PyTorch build, NVIDIA driver, and CUDA-compatible environment.")
        return 0

    try:
        current_device = torch.cuda.current_device()
        device = torch.device("cuda")
        print(f"Current device: {current_device} ({torch.cuda.get_device_name(current_device)})")

        values = torch.tensor([1.0, 2.0, 3.0], device=device)
        result = (values * 2).sum()
        torch.cuda.synchronize()
        print(f"CUDA tensor operation: PASS (sum={result.item():.1f})")

        allocation = torch.empty((1024, 1024), dtype=torch.float32, device=device)
        allocation.fill_(1.0)
        torch.cuda.synchronize()
        print(f"CUDA memory allocation: PASS ({allocation.numel() * allocation.element_size() / 1024**2:.1f} MiB)")
        del allocation
        return 0
    except Exception as exc:  # CUDA diagnostics should explain failures, not crash mysteriously.
        print(f"[GPU CHECK] CUDA operation failed: {type(exc).__name__}: {exc}")
        print("[GPU CHECK] CPU fallback remains available.")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
