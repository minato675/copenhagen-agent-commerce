#!/usr/bin/env python3
"""DEPRECATED: historical interim-sheet Back reproducer, not a final acceptance test.

The current card chooser is inline and creates no new window. Run this only with
an older sheet APK while owning emulator-5554, with the preview user signed in and
the loopback fixture running. This resets fixture memory, never application data.
"""
import argparse
import json
import re
import time
from pathlib import Path

import device as d

APPROVAL_ID = "11111111-1111-4111-8111-111111111111"
REVIEW = ["요청 총액", "변경", "42,800원"]


def parse_focus(dump):
    windows = re.findall(r"mCurrentFocus=(Window\{([^\s}]+)[^\n]*\})", dump)
    apps = re.findall(r"mFocusedApp=([^\n]+)", dump)
    if not windows:
        return None
    # This harness targets one emulator display. Reject another foreground app.
    window, token = windows[0]
    app = next((value for value in apps if d.PACKAGE in value), None)
    if app is None or (d.PACKAGE not in window and "/" in window):
        raise RuntimeError(f"Preview does not own window focus: {window}; apps={apps}")
    return dict(token=token, window=window, focused_app=app)


def focus():
    return parse_focus(d.adb("shell", "dumpsys", "window").decode())


def wait_focus(predicate, timeout, samples, origin):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        before = time.monotonic()
        current = focus()
        after = time.monotonic()
        samples.append(dict(start_ms=round((before-origin)*1000, 2),
                            end_ms=round((after-origin)*1000, 2), focus=current))
        if current and predicate(current):
            return current
        # dumpsys is already a blocking observation; no animation-delay sleep.
    raise TimeoutError("Expected native window focus was not observed")


def coordinates(root, label):
    found = [node for node in root.iter("node")
             if node.get("package") == d.PACKAGE and
             (node.get("text") == label or node.get("content-desc") == label)]
    if not found:
        raise RuntimeError("Visible preview action not found: " + label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", found[-1].get("bounds")))
    return (x1+x2)//2, (y1+y2)//2


def assert_no_mutation(stats):
    writes = {route: count for route, count in stats["requests"].items()
              if not route.startswith("GET ") and count}
    assert stats["approve_requests"] == 0, stats
    assert stats["logical_approvals"] == 0, stats
    assert stats["idempotency_keys"] == [], stats
    assert stats["authoritative_status"] == "PENDING", stats
    assert not writes, writes


def run(output, repeats=3, focus_timeout=3):
    output.mkdir(parents=True, exist_ok=True)
    report = dict(complete=False, historical_interim_sheet_reproducer=True,
                  package=d.PACKAGE, serial="emulator-5554",
                  started_at_unix=time.time(), sheet_attempts=[],
                  timing_note="Host monotonic timestamps bracket adb commands. Back is sent immediately after a different preview window is observed focused. Focus observation does not prove the Compose entrance animation is still running; dumpsys adds measurement overhead. No UIAutomator runs between opening the sheet and sending Back.")
    error = None
    try:
        paths = d.adb("shell", "pm", "path", d.PACKAGE).decode().splitlines()
        base = next(line.removeprefix("package:") for line in paths if line.endswith("/base.apk"))
        report["installed_apk_sha256"] = d.adb("shell", "sha256sum", base).decode().split()[0]
        d.adb("reverse", "tcp:8787", "tcp:8787")
        d.control("reset", {})
        d.control("config", dict(mode="approved", latency_ms=250))
        d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", APPROVAL_ID)
        for index in range(repeats):
            root = d.wait_for(REVIEW, absent=["닫기", "PIN 6자리"])
            x, y = coordinates(root, "변경")
            baseline = focus()
            if baseline is None:
                raise RuntimeError("Review has no focused native window")
            origin = time.monotonic()
            attempt = dict(attempt=index+1, review_focus=baseline, observed_change_action=dict(x=x, y=y),
                           tap_started_ms=0, focus_samples=[])
            report["sheet_attempts"].append(attempt)
            d.adb("shell", "input", "tap", str(x), str(y))
            attempt["tap_returned_ms"] = round((time.monotonic()-origin)*1000, 2)
            attached = wait_focus(lambda current: current["token"] != baseline["token"],
                                  focus_timeout, attempt["focus_samples"], origin)
            attempt["attached_focus"] = attached
            attempt["back_started_ms"] = round((time.monotonic()-origin)*1000, 2)
            d.adb("shell", "input", "keyevent", "4")
            attempt["back_returned_ms"] = round((time.monotonic()-origin)*1000, 2)
            attempt["return_focus_samples"] = []
            wait_focus(lambda current: current["token"] == baseline["token"],
                       8, attempt["return_focus_samples"], origin)
            d.wait_for(REVIEW, absent=["닫기", "PIN 6자리"])
            attempt["fixture"] = d.control("stats")
            assert_no_mutation(attempt["fixture"])
            attempt["passed"] = True
            print(f"Focused sheet Back {index+1}/{repeats}: passed; Back dispatched {attempt['back_started_ms']} ms after tap began", flush=True)

        # This separately checks ordinary PIN-route Back; no PIN or submit is sent.
        d.tap("42,800원 승인")
        d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
        pin_start = time.monotonic()
        d.adb("shell", "input", "keyevent", "4")
        d.wait_for(REVIEW, absent=["닫기", "PIN 6자리"])
        report["pin_route_back"] = dict(passed=True, observed_return_after_ms=round((time.monotonic()-pin_start)*1000, 2),
                                        note="Settled PIN-route Back; UIAutomator observation time is included. No PIN was entered.")
        assert_no_mutation(d.control("stats"))
        d.capture(str(output / "review-after-focused-back.png"), REVIEW, absent=["닫기", "PIN 6자리"])
        report["complete"] = True
    except BaseException as failure:
        error = failure
        report["error"] = f"{type(failure).__name__}: {failure}"
        try:
            # Explicitly unvalidated evidence: never label a failed capture as review.
            (output / "failure-unvalidated.png").write_bytes(d.adb("exec-out", "screencap", "-p"))
            (output / "failure-window.txt").write_bytes(d.adb("shell", "dumpsys", "window"))
        except Exception as capture_failure:
            report["failure_capture_error"] = str(capture_failure)
    finally:
        try:
            report["fixture_after"] = d.control("stats")
            assert_no_mutation(report["fixture_after"])
            report["no_mutation"] = True
        except Exception as stats_failure:
            report["no_mutation"] = False
            report["fixture_error"] = str(stats_failure)
            if error is None:
                error = stats_failure
                report["complete"] = False
        (output / "focused-back.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if error is not None:
        raise error
    print("All focused sheet Back checks and PIN route Back passed with zero fixture writes.", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--historical-sheet-apk", action="store_true",
                        help="Explicitly acknowledge that an older, superseded card-sheet APK is installed")
    parser.add_argument("--output", type=Path, default=Path("docs/redesign/evidence/runtime/historical-sheet-reproduction"))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--focus-timeout", type=float, default=3)
    args = parser.parse_args()
    if not args.historical_sheet_apk:
        parser.error("Deprecated: current card correction is inline. This reproducer requires an older sheet APK and --historical-sheet-apk; do not use it for final acceptance.")
    if args.repeats < 1 or args.focus_timeout <= 0:
        parser.error("repeats and focus-timeout must be positive")
    run(args.output, args.repeats, args.focus_timeout)
