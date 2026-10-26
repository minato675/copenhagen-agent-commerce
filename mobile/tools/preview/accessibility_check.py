#!/usr/bin/env python3
"""Custom PIN keypad probe at narrow widths and enlarged text.

Own emulator-5554 and use only the signed-in preview APK and loopback fixture.
Checks masked entry, editing, system-IME absence, bounded scroll reachability and
native Tab/Enter activation. No payment is submitted. TalkBack and physical-keyboard
coverage still require device verification; a semantic tree is not a screen reader.
"""
import argparse
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path

import device as d
from reduced_focus_check import installed_hash, restore_setting


ID = "11111111-1111-4111-8111-111111111111"


def scroll_to(label, attempts=6, direction="down"):
    for index in range(attempts+1):
        root = d.tree()
        if label in d.visible_labels(root):
            return root
        if index < attempts:
            areas = [list(map(int, re.findall(r"\d+", node.get("bounds"))))
                     for node in root.iter("node") if node.get("package") == d.PACKAGE]
            width, height = max(area[2] for area in areas), max(area[3] for area in areas)
            start, end = (.75, .3) if direction == "down" else (.3, .8)
            d.adb("shell", "input", "swipe", str(width//2), str(int(height*start)),
                  str(width//2), str(int(height*end)), "280")
    raise RuntimeError("Action not reached by bounded native scrolling: " + label)


def activate_focused_digit():
    for _ in range(18):
        d.adb("shell", "input", "keyevent", "61")
        root = d.tree()
        digits = [node for node in root.iter("node") if node.get("focused") == "true" and
                  any(re.fullmatch(r"[0-9]", text) for text in d.visible_labels(node))]
        if digits:
            d.adb("shell", "input", "keyevent", "66")
            assert d.pin_fingerprint()["character_count"] == 1, "Enter did not activate the focused digit"
            return dict(tab_focus_reached_digit=True, enter_activated_digit=True)
    raise AssertionError("Tab did not reach a numeric keypad control")


def run(output, fonts=(1.0, 1.5, 2.0)):
    output.mkdir(parents=True, exist_ok=False)
    report = dict(complete=False, probe="custom PIN keypad", package=d.PACKAGE,
                  started_at_unix=time.time(), cases=[],
                  boundary="Native semantic and screenshot checks at 360×640 dp. No payment, TalkBack session or physical keyboard is used; screenshots require visual review.")
    original_font = None
    original_size = None
    failure = None
    try:
        report["installed_apk_sha256"] = installed_hash()
        original_font = d.adb("shell", "settings", "get", "system", "font_scale").decode().strip()
        sizes = d.adb("shell", "wm", "size").decode()
        override = re.search(r"Override size:\s*(\d+x\d+)", sizes)
        original_size = override[1] if override else "reset"
        densities = d.adb("shell", "wm", "density").decode()
        density_values = re.findall(r"(?:Physical|Override) density:\s*(\d+)", densities)
        density = int(density_values[-1])
        d.adb("shell", "wm", "size", f"{round(360*density/160)}x{round(640*density/160)}")
        d.control("reset", {})
        for font in fonts:
            d.adb("shell", "settings", "put", "system", "font_scale", str(font))
            d.adb("shell", "am", "force-stop", d.PACKAGE)
            d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", ID)
            d.wait_for(["승인 요청"])
            scroll_to("42,800원 승인")
            d.tap("42,800원 승인")
            d.wait_for(["PIN 인증"])
            scroll_to("PIN 6자리", direction="up")
            assert d.pin_fingerprint()["character_count"] == 0
            filled = d.enter_pin("123456")
            d.capture(str(output / f"keypad-filled-{font}.png"), ["PIN 6자리 입력 상태, 6/6 자리 입력됨"])
            scroll_to("42,800원 승인")
            d.capture(str(output / f"keypad-submit-{font}.png"), ["42,800원 승인"])
            assert not d.keyboard_visible()
            scroll_to("PIN 한 자리 지우기", direction="up")
            d.tap("PIN 한 자리 지우기")
            assert d.pin_fingerprint()["character_count"] == 5
            scroll_to("PIN 전체 지우기")
            d.tap("PIN 전체 지우기")
            assert d.pin_fingerprint()["character_count"] == 0
            case = dict(font_scale=font, width_dp=360, height_dp=640, filled=filled,
                        delete_count=5, clear_count=0, submit_reachable=True)
            if font == 1.0:
                case["keyboard_navigation"] = activate_focused_digit()
            report["cases"].append(case)
            print(f"PIN keypad font scale {font}: PASS", flush=True)
        report["complete"] = True
    except BaseException as error:
        failure = error
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        cleanup_errors = []
        if original_size is not None:
            try:
                d.adb("shell", "wm", "size", original_size)
            except Exception as error:
                cleanup_errors.append(str(error))
        if original_font is not None:
            try:
                restore_setting("system", "font_scale", original_font)
            except Exception as error:
                cleanup_errors.append(str(error))
        try:
            stats = d.control("stats")
            report["fixture"] = stats
            assert stats["approve_requests"] == 0 and stats["logical_approvals"] == 0, stats
        except Exception as error:
            cleanup_errors.append(str(error))
        if cleanup_errors:
            report["cleanup_errors"] = cleanup_errors
            report["complete"] = False
            if failure is None:
                failure = RuntimeError("; ".join(cleanup_errors))
        (output / "keypad-probe.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if failure is not None:
        raise failure
    print(f"Native keypad checks passed; inspect images for layout: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{time.time_ns()}"
    parser.add_argument("--output", type=Path, default=Path("docs/redesign/evidence/keypad-probes") / run_id,
                        help="New directory only; refuses an existing directory")
    parser.add_argument("--font-scale", type=float, action="append", help="Run only the specified text sizes")
    args = parser.parse_args()
    run(args.output, args.font_scale or (1.0, 1.5, 2.0))
