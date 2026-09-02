"""Minimal training-entry smoke test for the assigned GPU environment.

This file intentionally does not implement the FDS model.  It validates the
runtime selected by ``scripts/train.sh`` and provides a safe CPU fallback so a
real training loop can be added without hard-coding a physical GPU number.
"""

from __future__ import annotations

import argparse
import random
import sys


def set_seed(seed: int) -> None:
    """Seed Python, NumPy and PyTorch when those libraries are available."""

    random.seed(seed)
    try:
        import numpy as np

        np.random.seed(seed)
    except ImportError:
        pass

    try:
        import torch

        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)
    except ImportError:
        pass


def run_tensor_smoke_test(torch: object, device: object) -> None:
    """Run a small tensor operation and allocation on the requested device."""

    values = torch.tensor([1.0, 2.0, 3.0], device=device)
    result = (values * 2).sum()
    if getattr(device, "type", "cpu") == "cuda":
        torch.cuda.synchronize()
    print(f"Tensor operation: PASS (sum={result.item():.1f}, device={device})")

    allocation = torch.empty((1024, 1024), dtype=torch.float32, device=device)
    allocation.fill_(1.0)
    if getattr(device, "type", "cpu") == "cuda":
        torch.cuda.synchronize()
    print(f"Memory allocation: PASS ({allocation.numel() * allocation.element_size() / 1024**2:.1f} MiB)")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """Parse smoke-test options while allowing future training arguments."""

    parser = argparse.ArgumentParser(description="FDS training-entry GPU smoke test")
    parser.add_argument("--epochs", type=int, default=1)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--seed", type=int, default=42)
    args, unknown = parser.parse_known_args(argv)
    args.unknown = unknown
    return args


def main(argv: list[str] | None = None) -> int:
    """Validate the selected runtime without starting a real model training job."""

    args = parse_args(argv)
    set_seed(args.seed)
    try:
        import torch
    except ImportError:
        print("[TRAIN SMOKE TEST] PyTorch is not installed in the active environment.", file=sys.stderr)
        print("[TRAIN SMOKE TEST] Create the Conda environment and install a compatible PyTorch build.", file=sys.stderr)
        return 2

    cuda_available = torch.cuda.is_available()
    device = torch.device("cuda" if cuda_available else "cpu")
    print(f"PyTorch: {torch.__version__}")
    print(f"CUDA available: {cuda_available}")
    print(f"Requested smoke-test settings: epochs={args.epochs}, batch_size={args.batch_size}, seed={args.seed}")
    if args.unknown:
        print(f"Additional arguments received for future training logic: {args.unknown}")

    if cuda_available:
        print(f"Current GPU: {torch.cuda.get_device_name(torch.cuda.current_device())}")
    else:
        print("CUDA unavailable; using CPU fallback.")

    try:
        run_tensor_smoke_test(torch, device)
    except Exception as exc:
        if device.type != "cuda":
            print(f"CPU smoke test failed: {type(exc).__name__}: {exc}", file=sys.stderr)
            return 1
        print(f"CUDA smoke test failed: {type(exc).__name__}: {exc}")
        print("Retrying the same smoke test on CPU.")
        run_tensor_smoke_test(torch, torch.device("cpu"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
