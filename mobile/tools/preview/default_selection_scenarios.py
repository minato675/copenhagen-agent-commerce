#!/usr/bin/env python3
"""Default-card/address native motion checks. Only in-memory loopback fixtures mutate.

Owns emulator-5554. Never touches the real wallet package, login, or backend.
"""
import argparse
import contextlib
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path

import device as d
from loading_motion_check import start, wait_enabled

OUT = Path("docs/redesign/evidence/default-selection")
RESULTS = []
CARD = dict(route="카드", heading="카드 관리", action="기본 카드로 지정", badge="기본 카드",
            first="••••  4242", second="••••  1881", path="/api/pay/v1/payment-methods/cards",
            first_id="33333333-3333-4333-8333-333333333333", second_id="44444444-4444-4444-8444-444444444444")
ADDRESS = dict(route="배송지", heading="배송지", action="기본 배송지로 지정", badge="기본 배송지",
               first="테스트 사용자", second="테스트 사무실", path="/api/core/v1/addresses",
               first_id="88888888-8888-4888-8888-888888888888", second_id="99999999-9999-4999-8999-999999999999")
NETWORK_ERROR = "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def request(path, body=None, method=None):
    req = urllib.request.Request("http://127.0.0.1:8787" + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(req) as response:
        return json.load(response)


def prepare(kind):
    start()
    if kind == ADDRESS:
        request(kind["path"], dict(recipient=kind["second"], phone="010-0000-0001", postalCode="03001",
            address="서울시 테스트로 24", detail="501호", memo="안내 데스크"))
    d.tap(kind["route"])
    wait_enabled(kind["action"])
    return settled(kind, kind["first"])


def item(root, identity):
    # Each object exposes a traversal group, so names and controls stay associated after sorting.
    candidates = [n for n in root.iter("node") if identity in d.visible_labels(n)
                  and any(c.get("clickable") == "true" for c in n.iter("node"))]
    assert candidates, "No complete visible fixture row: " + identity
    return min(candidates, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))


def selected(root, kind, identity):
    return kind["badge"] in d.visible_labels(item(root, identity))


def settled(kind, expected, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = d.tree()
        if not {kind["first"], kind["second"]}.issubset(d.visible_labels(root)):
            continue
        if selected(root, kind, expected):
            other = kind["second"] if expected == kind["first"] else kind["first"]
            assert not selected(root, kind, other), "Two default badges exposed"
            assert bounds(item(root, expected))[1] < bounds(item(root, other))[1], "Default did not move first"
            assert sum(n.get("content-desc") == kind["badge"] for n in root.iter("node")) == 1
            return root
    raise AssertionError("Default never settled: " + expected)


def target(root, kind, identity):
    row = item(root, identity)
    controls = [n for n in row.iter("node") if n.get("clickable") == "true" and n.get("enabled") == "true"
                and kind["action"] in d.visible_labels(n)]
    assert len(controls) == 1, "Ambiguous default action"
    return bounds(controls[0])


def tap(rect):
    x1, y1, x2, y2 = rect
    d.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def screenshot(name):
    (OUT / (name + ".png")).write_bytes(d.adb("exec-out", "screencap", "-p"))


@contextlib.contextmanager
def recording(name):
    remote = f"/sdcard/default-motion-{time.time_ns()}.mp4"
    prior = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", "screenrecord"], capture_output=True)
    assert not prior.stdout.strip(), "Another recorder owns this emulator"
    process = subprocess.Popen(["adb", "-s", "emulator-5554", "shell", "screenrecord", "--bit-rate", "4000000",
                                "--time-limit", "20", remote], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    pid = None
    try:
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            found = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", "screenrecord"], capture_output=True).stdout.decode().strip()
            if found:
                pid = found
                break
        assert pid
        yield
    finally:
        if pid and process.poll() is None:
            d.adb("shell", "kill", "-2", pid)
        process.wait(timeout=20)
        d.adb("pull", remote, str(OUT / (name + ".mp4")))
        d.adb("shell", "rm", "-f", remote)


def patch_count(kind, which="second"):
    return d.control("stats")["requests"].get("PATCH " + kind["path"] + "/" + kind[which + "_id"] + "/default", 0)


def switch(kind, name, slow=False, reduced=False):
    root = prepare(kind)
    rect = target(root, kind, kind["second"])
    screenshot(name + "-before")
    d.control("config", dict(read_latency_ms=8000 if slow else 0))
    with recording(name):
        tap(rect)
        if slow:
            tap(rect)  # Same enabled-at-start location; the in-flight action must reject duplicates.
            pending = d.tree()
            assert selected(pending, kind, kind["first"]), "Old default vanished before the authoritative read"
            assert not selected(pending, kind, kind["second"]), "Premature default shown"
            assert bounds(item(pending, kind["first"]))[1] < bounds(item(pending, kind["second"]))[1]
            assert "불러오는 중" in {n.get("content-desc") for n in pending.iter("node")} or any(
                n.get("enabled") == "false" and kind["action"] in d.visible_labels(n) for n in pending.iter("node"))
            screenshot(name + "-pending")
        settled(kind, kind["second"])
        screenshot(name + "-after")
    assert patch_count(kind) == 1, "One intent produced duplicate PATCH calls"
    d.control("config", dict(read_latency_ms=0))
    # The old default must still be its own object, with a working reverse action.
    root = d.tree()
    tap(target(root, kind, kind["first"]))
    settled(kind, kind["first"])
    assert patch_count(kind, "first") == 1
    RESULTS.append(dict(name=name, delayed_read_ms=8000 if slow else 0, reduced=reduced,
                        exactly_one_patch_per_selection=True, reverse_selection_passed=True))
    print("PASS", name, flush=True)


def failure(kind, name):
    root = prepare(kind)
    rect = target(root, kind, kind["second"])
    d.control("config", dict(mode="offline"))
    tap(rect)
    root = d.wait_for([NETWORK_ERROR, kind["first"], kind["second"]])
    assert selected(root, kind, kind["first"])
    assert not selected(root, kind, kind["second"])
    assert patch_count(kind) == 1, "Default mutation was automatically retried"
    screenshot(name)
    d.control("config", dict(mode="approved"))
    d.tap("오류 닫기")
    tap(target(d.tree(), kind, kind["second"]))
    settled(kind, kind["second"])
    RESULTS.append(dict(name=name, old_default_retained=True, manual_retry_passed=True))
    print("PASS", name, flush=True)


def navigation():
    root = prepare(CARD)
    d.control("config", dict(read_latency_ms=8000))
    tap(target(root, CARD, CARD["second"]))
    # App tabs retain their existing busy lock. The OS can still background the app.
    d.adb("shell", "input", "keyevent", "3")
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    # Returning to the list must read the actual result without replaying a mutation.
    d.control("config", dict(read_latency_ms=0))
    settled(CARD, CARD["second"])
    wait_enabled("내 정보 및 설정")
    d.tap("기록")
    d.wait_for(["승인 기록"])
    d.tap("지갑")
    wait_enabled("내 정보 및 설정")
    d.tap("카드")
    settled(CARD, CARD["second"])
    assert patch_count(CARD) == 1
    screenshot("cards-after-navigation")
    RESULTS.append(dict(name="background during request and tab return after confirmation", exactly_one_patch=True))
    print("PASS navigation", flush=True)


def large_text():
    font = d.adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    size = d.adb("shell", "wm", "size").decode()
    override = re.search(r"Override size: (\d+x\d+)", size)
    try:
        for kind, name in ((CARD, "cards"), (ADDRESS, "addresses")):
            prepare(kind)
            d.adb("shell", "wm", "size", "900x2000")
            d.adb("shell", "settings", "put", "system", "font_scale", "1.5")
            # Only scroll the real list; no forced programmatic promotion-to-top behavior.
            rect = None
            for attempt in range(6):
                root = d.tree()
                try:
                    rect = target(root, kind, kind["second"])
                    break
                except AssertionError:
                    if attempt == 5: raise
                    d.adb("shell", "input", "swipe", "450", "1550", "450", "650", "280")
            screenshot(name + "-large-text-action")
            tap(rect)
            wait_enabled("내 정보 및 설정")
            for attempt in range(6):
                root = d.tree()
                if kind["heading"] in d.visible_labels(root) and selected(root, kind, kind["second"]):
                    break
                if attempt == 5: raise AssertionError("Promoted item missing with large text")
                d.adb("shell", "input", "swipe", "450", "600", "450", "1550", "280")
            assert patch_count(kind) == 1
            screenshot(name + "-large-text-result")
            RESULTS.append(dict(name=name + " large text", display_px="900x2000", density_dpi=420, font_scale=1.5))
            print("PASS", name + " large text", flush=True)
            d.adb("shell", "wm", "size", override[1] if override else "reset")
            if font == "null": d.adb("shell", "settings", "delete", "system", "font_scale")
            else: d.adb("shell", "settings", "put", "system", "font_scale", font)
    finally:
        d.adb("shell", "wm", "size", override[1] if override else "reset")
        if font == "null": d.adb("shell", "settings", "delete", "system", "font_scale")
        else: d.adb("shell", "settings", "put", "system", "font_scale", font)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["motion", "stress", "reduced", "context", "all"], default="all", nargs="?")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    path = d.adb("shell", "pm", "path", d.PACKAGE).decode().strip().removeprefix("package:")
    apk_hash = d.adb("shell", "sha256sum", path).decode().split()[0]
    scale = d.adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
    failure_reason = None
    try:
        if args.mode in ("motion", "all"):
            switch(CARD, "cards")
            switch(ADDRESS, "addresses")
        if args.mode in ("stress", "all"):
            for kind, name in ((CARD, "cards"), (ADDRESS, "addresses")):
                switch(kind, name + "-slow", slow=True)
                failure(kind, name + "-offline")
        if args.mode in ("reduced", "all"):
            d.adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
            switch(CARD, "cards-reduced", reduced=True)
            switch(ADDRESS, "addresses-reduced", reduced=True)
        if args.mode == "context":
            navigation()
            large_text()
    except BaseException as error:
        failure_reason = repr(error)
        raise
    finally:
        d.control("config", dict(mode="approved", read_latency_ms=0))
        if scale == "null": d.adb("shell", "settings", "delete", "global", "animator_duration_scale")
        else: d.adb("shell", "settings", "put", "global", "animator_duration_scale", scale)
        (OUT / (args.mode + ".json")).write_text(json.dumps(dict(complete=failure_reason is None,
            failure=failure_reason, results=RESULTS, preview_apk_sha256=apk_hash,
            note="Recordings show native UI; emulator recordings are not performance benchmarks."), ensure_ascii=False, indent=2))
