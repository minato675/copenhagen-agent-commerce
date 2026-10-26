"""Rotate during approval reads before a system biometric prompt can start.

Run after approval_ux_scenarios.py, with its registered emulator key and fixture.
The guard must release busy and offer PIN without transmitting approval.
"""
import json
from pathlib import Path

import device as d
from approval_ux_scenarios import OUT, restart, review
from reduced_focus_check import restore_setting
from secondary_sweep import scroll_to


def main():
    original = {name: d.adb("shell", "settings", "get", "system", name).decode().strip()
                for name in ("accelerometer_rotation", "user_rotation")}
    before = d.control("stats")["approve_requests"]
    result = dict(passed=False)
    try:
        d.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        d.adb("shell", "settings", "put", "system", "user_rotation", "0")
        restart(reset=False, status="PENDING", read_latency_ms=0)
        review()
        d.control("config", {"read_latency_ms": 2000})
        d.tap("42,800원 승인")
        d.adb("shell", "settings", "put", "system", "user_rotation", "1")
        d.wait_for(["PIN 인증"], timeout=30)
        d.control("config", {"read_latency_ms": 0})
        d.adb("shell", "settings", "put", "system", "user_rotation", "0")
        scroll_to("PIN 6자리", direction="up")
        assert d.pin_fingerprint()["character_count"] == 0
        d.enter_pin("1")
        assert d.pin_fingerprint()["character_count"] == 1
        assert d.control("stats")["approve_requests"] == before
        d.capture(OUT / "rotation-pin-fallback.png", ["PIN 6자리", "PIN으로 결제를 이어갈 수 있어요."])
        result.update(passed=True, approval_requests_unchanged=True, pin_input_enabled=True)
    finally:
        d.control("config", {"read_latency_ms": 0})
        for name, value in original.items():
            restore_setting("system", name, value)
        (OUT / "interruption-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print("Delayed approval read + Activity rotation -> usable PIN without payment PASS")


if __name__ == "__main__":
    main()
