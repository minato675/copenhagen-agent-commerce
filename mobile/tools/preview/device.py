#!/usr/bin/env python3
"""Small adb driver for the isolated preview package, using rendered accessibility labels."""
import argparse
import json
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.copenhagen.wallet.preview"
ACTIVITY = PACKAGE + "/com.copenhagen.wallet.PreviewActivity"


def adb(*args, capture=True):
    return subprocess.run(["adb", "-s", "emulator-5554", *args], check=True, capture_output=capture).stdout


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/preview-window.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/preview-window.xml"))
    if not any(node.get("package") == PACKAGE for node in root.iter("node")):
        raise RuntimeError("Refusing UI automation: isolated preview is not visible")
    return root


def nodes():
    return list(tree().iter("node"))


def text_dump():
    for node in nodes():
        if node.get("text") or node.get("content-desc") or node.get("class") == "android.widget.EditText":
            print(node.get("text") or node.get("content-desc") or "[input]", node.get("bounds"), "enabled=" + node.get("enabled", ""))


def tap(label):
    candidates = [n for n in nodes() if n.get("text") == label or n.get("content-desc") == label]
    if not candidates:
        raise RuntimeError("Visible label not found: " + label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", candidates[-1].get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def fill(value, index=0):
    inputs = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", inputs[index].get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.3)
    adb("shell", "input", "text", value)


def _pin_node(predicate):
    """Find a keypad control using bounded scrolling, including enlarged-text layouts."""
    for direction in ("down", "up", "down"):
        for attempt in range(4):
            root = tree()
            candidates = [n for n in root.iter("node") if n.get("package") == PACKAGE and predicate(n)]
            if len(candidates) == 1:
                return candidates[0]
            if len(candidates) > 1:
                raise AssertionError("Ambiguous PIN keypad control")
            if attempt < 3:
                areas = [list(map(int, re.findall(r"\d+", n.get("bounds"))))
                         for n in root.iter("node") if n.get("package") == PACKAGE]
                width, height = max(b[2] for b in areas), max(b[3] for b in areas)
                start, end = (.75, .3) if direction == "down" else (.3, .8)
                adb("shell", "input", "swipe", str(width//2), str(int(height*start)),
                    str(width//2), str(int(height*end)), "280")
    raise AssertionError("PIN keypad control not found after bounded scrolling")


def pin_fingerprint():
    """Inspect only the accessible mask count; never read or store a secret value."""
    mask = _pin_node(lambda n: n.get("password") == "true" and
                     re.search(r"입력 상태, [0-6]/6 자리 입력됨", n.get("content-desc", "")))
    count = int(re.search(r"([0-6])/6 자리 입력됨", mask.get("content-desc"))[1])
    assert not any(n.get("class") == "android.widget.EditText" for n in nodes()), "PIN must not use an IME text field"
    assert not keyboard_visible(), "The PIN keypad must not open the system IME"
    return dict(character_count=count, password=True, system_ime=False)


def enter_pin(value):
    """Tap rendered random digits and verify the masked length without exposing the PIN."""
    if not re.fullmatch(r"[0-9]{1,6}", value):
        raise ValueError("Expected one to six fixture PIN digits")
    before = pin_fingerprint()["character_count"]
    if before + len(value) > 6:
        raise ValueError("PIN entry would exceed six digits")
    for digit in value:
        node = _pin_node(lambda n: n.get("text") == digit or n.get("content-desc") == digit)
        assert node.get("enabled") == "true", "PIN key is disabled"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    after = pin_fingerprint()
    assert after["character_count"] == before + len(value), "PIN keypad lost a key press"
    return after


def control(path, value=None):
    request = urllib.request.Request("http://127.0.0.1:8787/__control/" + path, data=None if value is None else json.dumps(value).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(request))


def visible_labels(root):
    return {value for node in root.iter("node")
            if node.get("package") == PACKAGE
            for value in (node.get("text"), node.get("content-desc")) if value}


def wait_for(expected, timeout=25, absent=()):
    """Wait on native semantics, not elapsed animation guesses or am-start completion."""
    if not expected:
        raise ValueError("At least one unique expected destination label is required")
    deadline = time.monotonic() + timeout
    last_labels = set()
    while time.monotonic() < deadline:
        visible = tree()  # uiautomator itself takes ~2 seconds; do not add polling sleeps.
        last_labels = visible_labels(visible)
        if all(label in last_labels for label in expected) and not any(label in last_labels for label in absent):
            return visible
    raise TimeoutError(f"Expected labels {expected!r}; last visible labels: {sorted(last_labels)!r}")


def capture(path, expected, timeout=25, absent=()):
    """Save only when unique destination labels exist both before and after screencap."""
    if not expected:
        raise ValueError("capture requires --expect with the destination heading/action")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        before = wait_for(expected, max(1, deadline - time.monotonic()), absent)
        png = adb("exec-out", "screencap", "-p")
        after = tree()
        labels = visible_labels(after)
        if all(label in labels for label in expected) and not any(label in labels for label in absent):
            Path(path).write_bytes(png)
            Path(path).with_suffix(".xml").write_bytes(ET.tostring(before, encoding="utf-8"))
            Path(path).with_suffix(".capture.json").write_text(json.dumps({
                "package": PACKAGE, "required_labels": list(expected), "excluded_labels": list(absent),
                "validated_before_and_after_screenshot": True,
                "labels_after": sorted(labels), "captured_at_unix": time.time()
            }, ensure_ascii=False, indent=2))
            return
    raise TimeoutError("Destination changed during capture; no misleading screenshot was saved")


def keyboard_visible():
    # InputMethodManager can report shown while an emulator IME has a zero-height surface.
    state = adb("shell", "dumpsys", "window").decode()
    for line in state.splitlines():
        if 'type=ime ' in line and 'visible=true' in line:
            bounds = re.search(r'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', line)
            if bounds and int(bounds[4]) > int(bounds[2]): return True
    return False


def hide_keyboard():
    if keyboard_visible():
        adb("shell", "input", "keyevent", "4")


def login():
    fill("preview")
    tap("다음")
    fill("preview-password")
    tap("로그인")
    wait_for(["나의 지갑"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["dump", "tap", "fill", "pin", "login", "capture", "config", "stats", "reset", "open", "wait"])
    parser.add_argument("value", nargs="?")
    parser.add_argument("--expect", action="append", default=[])
    parser.add_argument("--absent", action="append", default=[])
    parser.add_argument("--timeout", type=float, default=25)
    args = parser.parse_args()
    if args.action == "dump": text_dump()
    elif args.action == "tap": tap(args.value)
    elif args.action == "fill": fill(args.value)
    elif args.action == "pin": enter_pin(args.value)
    elif args.action == "login": login()
    elif args.action == "capture": capture(args.value, args.expect, args.timeout, args.absent)
    elif args.action == "wait": wait_for(args.expect or [args.value], args.timeout, args.absent)
    elif args.action == "config": print(json.dumps(control("config", json.loads(args.value)), ensure_ascii=False, indent=2))
    elif args.action == "stats": print(json.dumps(control("stats"), ensure_ascii=False, indent=2))
    elif args.action == "reset": print(json.dumps(control("reset", {}), ensure_ascii=False, indent=2))
    elif args.action == "open": adb("shell", "am", "start", "-n", ACTIVITY, "--es", "approvalId", args.value or "11111111-1111-4111-8111-111111111111")
