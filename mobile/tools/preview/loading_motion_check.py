#!/usr/bin/env python3
"""Read-only native loading checks; owns emulator-5554 and the isolated preview fixture."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path

from PIL import Image, ImageStat
import device as d

OUT = Path("docs/redesign/evidence/loading-motion")


def control_bounds(label, checked=False):
    targets = [n for n in d.tree().iter("node") if n.get("clickable") == "true"
               and label in d.visible_labels(n) and (not checked or n.get("checked") == "true")]
    rectangles = [tuple(map(int, re.findall(r"\d+", n.get("bounds")))) for n in targets]
    assert rectangles, "Expected enabled preview control: " + label
    return min(rectangles, key=lambda r: (r[2] - r[0]) * (r[3] - r[1]))


def wait_enabled(label, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = d.tree()
        if any(n.get("clickable") == "true" and n.get("enabled") == "true" and label in d.visible_labels(n)
               for n in root.iter("node")):
            return root
    raise AssertionError("Preview control did not become ready: " + label)


def snapshot(name):
    path = OUT / (name + ".png")
    path.write_bytes(d.adb("exec-out", "screencap", "-p"))
    return path


def fill_color(path, bounds):
    x1, y1, x2, y2 = bounds
    # Interior strip avoids text, check glyphs, rounded edges, and the activity indicator.
    area = (x1 + 30, y1 + 24, x2 - 80, y1 + 32)
    return ImageStat.Stat(Image.open(path).convert("RGB").crop(area)).median


def start(empty=False):
    d.control("reset", {})
    d.control("config", dict(empty=empty))
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    deadline = time.monotonic() + 40
    while True:
        try:
            d.wait_for(["나의 지갑"], timeout=max(1, deadline - time.monotonic()))
            break
        except RuntimeError as error:
            # Android may still expose its launcher/splash immediately after an APK update.
            if str(error) != "Refusing UI automation: isolated preview is not visible" or time.monotonic() >= deadline:
                raise
    wait_enabled("내 정보 및 설정")


def appearance(phase):
    start(empty=True)
    button = control_bounds("결제 요청")
    wallet_idle = snapshot(phase + "-wallet-idle")
    d.tap("기록")
    wait_enabled("승인 전달됨")
    chip = control_bounds("승인 전달됨", checked=True)
    filter_idle = snapshot(phase + "-filter-idle")
    d.control("config", dict(read_latency_ms=800))
    d.tap("지갑")
    time.sleep(.6)
    wallet_reading = snapshot(phase + "-wallet-reading")
    wait_enabled("결제 요청")
    d.control("config", dict(read_latency_ms=3000))
    d.tap("기록")
    time.sleep(.6)
    filter_reading = snapshot(phase + "-filter-reading")
    wait_enabled("승인 전달됨")
    d.control("config", dict(read_latency_ms=0))
    result = dict(button_bounds=button, selected_filter_bounds=chip,
        button_colors=[fill_color(wallet_idle, button), fill_color(wallet_reading, button)],
        filter_colors=[fill_color(filter_idle, chip), fill_color(filter_reading, chip)])
    for name in ("button", "filter"):
        idle, reading = result[name + "_colors"]
        difference = max(abs(a - b) for a, b in zip(idle, reading))
        result[name + "_max_channel_difference"] = difference
        if phase == "after": assert difference <= 2, (name, idle, reading)
        else: assert difference > 10, ("Baseline did not reproduce the flash", name, idle, reading)
    return result


def movie(latency, reduced=False):
    start()
    d.tap("기록")
    wait_enabled("주문·배송")
    x1, y1, x2, y2 = control_bounds("주문·배송")
    d.control("config", dict(read_latency_ms=latency))
    name = f"orders-{latency}ms" + ("-reduced" if reduced else "")
    remote = f"/sdcard/{name}-{time.time_ns()}.mp4"
    previous = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", "screenrecord"], capture_output=True)
    assert not previous.stdout.strip(), "Another screen recording is already active"
    process = subprocess.Popen(["adb", "-s", "emulator-5554", "shell", "screenrecord", "--bit-rate", "4000000", "--time-limit", "15", remote],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    recorder = None
    try:
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            found = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", "screenrecord"], capture_output=True).stdout.decode().strip()
            if found:
                recorder = found
                break
        assert recorder, "Recorder did not start"
        d.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        root = d.wait_for(["에브리데이 토트백", "전체"], absent=["불러오는 중"])
        assert "다시 불러오기" not in d.visible_labels(root)
        snapshot(name + "-ready")
    finally:
        if recorder: d.adb("shell", "kill", "-2", recorder)
        process.wait(timeout=20)
        d.adb("pull", remote, str(OUT / (name + ".mp4")))
        d.adb("shell", "rm", "-f", remote)
        d.control("config", dict(read_latency_ms=0))
    return dict(name=name, latency_ms=latency, reduced_motion=reduced, passed=True)


def cached_orders():
    # The preceding movie leaves an actual loaded order list in memory.
    d.tap("지갑")
    wait_enabled("내 정보 및 설정")
    d.control("config", dict(read_latency_ms=8000))
    d.tap("기록")
    root = d.wait_for(["에브리데이 토트백", "주문·배송"], absent=["불러오는 중"])
    assert "다시 불러오기" not in d.visible_labels(root)
    snapshot("cached-orders-during-read")
    wait_enabled("전체")
    d.control("config", dict(mode="offline", read_latency_ms=0))
    d.tap("새로고침")
    d.wait_for(["에브리데이 토트백", "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."])
    snapshot("cached-orders-after-read-error")
    d.control("config", dict(mode="approved"))
    return dict(name="cached order list survives revalidation and a read error", passed=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["before", "after", "motion"])
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    failure, result = None, None
    path = d.adb("shell", "pm", "path", d.PACKAGE).decode().strip().removeprefix("package:")
    apk_hash = d.adb("shell", "sha256sum", path).decode().split()[0]
    scale = d.adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
    try:
        if args.phase == "motion":
            result = []
            for latency in (0, 80, 250, 800):
                result.append(movie(latency))
                print("PASS", result[-1]["name"], flush=True)
            result.append(cached_orders())
            d.adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
            result.append(movie(250, reduced=True))
        else: result = appearance(args.phase)
        stats = d.control("stats")
        assert not {k: v for k, v in stats["requests"].items() if not k.startswith("GET ") and v}, stats
        print("PASS", args.phase, flush=True)
    except BaseException as error:
        failure = repr(error)
        raise
    finally:
        if scale == "null": d.adb("shell", "settings", "delete", "global", "animator_duration_scale")
        else: d.adb("shell", "settings", "put", "global", "animator_duration_scale", scale)
        d.control("config", dict(mode="approved", read_latency_ms=0))
        (OUT / (args.phase + ".json")).write_text(json.dumps(dict(complete=failure is None, failure=failure,
            results=result, installed_preview_apk_sha256=apk_hash,
            timing_note="Configured fixture latency; video is visual evidence, not a device performance benchmark."), ensure_ascii=False, indent=2))
