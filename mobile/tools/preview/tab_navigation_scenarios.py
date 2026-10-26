#!/usr/bin/env python3
"""Native tab/profile regression checks using only the isolated preview and local fixture.

Own emulator-5554 while running. Keeps the saved login; never submits a payment,
changes account settings, or touches the real app's data.
"""
import json
import re
import time
from pathlib import Path

import device as d
import notification_scenarios as notification

OUT = Path("docs/redesign/evidence/tab-navigation")
ROOT_LABELS = {"지갑": ["나의 지갑"], "결제": ["승인할 요청"], "기록": ["승인 기록", "주문·배송"]}
RESULTS = []


def selected(root, label):
    return any(n.get("selected") == "true" and label in d.visible_labels(n)
               for n in root.iter("node"))


def root_screen(tab):
    root = d.wait_for(ROOT_LABELS[tab], absent=["설정", "PIN 변경", "주문 상세", "카드 관리"])
    assert selected(root, tab), "Selected tab does not match visible destination: " + tab
    return root


def select(tab):
    d.tap(tab)
    return root_screen(tab)


def profile():
    d.tap("내 정보 및 설정")
    return d.wait_for(["설정", "PIN 변경"])


def record(name):
    RESULTS.append(dict(name=name, passed=True))
    print("PASS", name, flush=True)


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def scroll(direction):
    areas = [n for n in d.tree().iter("node")
             if n.get("package") == d.PACKAGE and n.get("class") == "android.widget.ScrollView"]
    assert len(areas) == 1, "Expected one vertical page scroll area"
    x1, y1, x2, y2 = bounds(areas[0])
    start, end = (.8, .3) if direction == "down" else (.3, .8)
    d.adb("shell", "input", "swipe", str((x1 + x2) // 2), str(int(y1 + (y2 - y1) * start)),
          str((x1 + x2) // 2), str(int(y1 + (y2 - y1) * end)), "280")


def scroll_to(label, direction="down"):
    for attempt in range(6):
        root = d.tree()
        if label in d.visible_labels(root):
            return root
        if attempt < 5:
            scroll(direction)
    raise AssertionError("Visible label not found after bounded scroll: " + label)


def run():
    d.control("reset", {})
    d.adb("reverse", "tcp:8787", "tcp:8787")
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    root_screen("지갑")

    for tab, other in (("지갑", "결제"), ("결제", "기록"), ("기록", "지갑")):
        select(tab)
        profile()
        select(other)
        select(tab)
        record(f"{tab}: profile → {other} → original tab opens root")
        d.capture(str(OUT / {"지갑": "wallet.png", "결제": "payment.png", "기록": "history.png"}[tab]),
                  ROOT_LABELS[tab], absent=["설정", "PIN 변경"])

        profile()
        select(tab)
        record(f"{tab}: tapping the selected tab dismisses profile")

        profile()
        d.adb("shell", "input", "keyevent", "4")
        root_screen(tab)
        record(f"{tab}: system Back from profile returns to its origin")

    # Both model-owned filters and saveable text input survive a root selection.
    select("기록")
    d.tap("승인 기록")
    d.tap("승인 대기 중")
    d.wait_for(["승인 대기 중 기록"])
    d.fill("fixture-no-match")
    d.hide_keyboard()
    d.wait_for(["검색 결과가 없어요"])
    profile()
    select("지갑")
    select("기록")
    root = d.wait_for(["승인 대기 중 기록", "검색 결과가 없어요"])
    assert any(n.get("class") == "android.widget.EditText" and n.get("text") == "fixture-no-match"
               for n in root.iter("node")), "History search was discarded"
    record("history filter and search survive profile and tab changes")

    d.tap("주문·배송")
    d.wait_for(["에브리데이 토트백"])
    d.tap("에브리데이 토트백")
    d.wait_for(["주문 상세", "주문 번호"])
    select("결제")
    select("기록")
    root = d.wait_for(["에브리데이 토트백"], absent=["주문 상세", "주문 번호"])
    assert selected(root, "주문·배송"), "History subsection was discarded"
    # Back from the root must not resurrect a discarded detail or profile.
    d.adb("shell", "input", "keyevent", "4")
    root_screen("지갑")
    record("order detail is dismissed; order subsection is retained; Back opens wallet")

    scroll_to("카드")
    d.tap("카드")
    d.wait_for(["카드 관리"])
    select("결제")
    select("지갑")
    record("card management does not replace wallet on tab return")

    scroll("down")
    before = scroll_to("최근 기록")
    anchor = next(bounds(n) for n in before.iter("node") if n.get("text") == "최근 기록")
    profile()
    select("결제")
    d.tap("지갑")
    after = d.wait_for(["최근 기록"], absent=["설정", "PIN 변경"])
    restored = next(bounds(n) for n in after.iter("node") if n.get("text") == "최근 기록")
    assert anchor == restored, (anchor, restored)
    assert selected(after, "지갑")
    record("wallet scroll position survives profile and tab changes")
    scroll_to("나의 지갑", "up")

    # Notification return restores the explicit origin, independently of tab selection.
    select("기록")
    profile()
    origin = notification.top()
    d.adb("shell", "pm", "grant", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")
    host = notification.post_and_tap()
    assert host["task_id"] != origin["task_id"]
    d.tap("닫고 돌아가기")
    notification.wait_top(lambda current: notification.same_task(current, origin), "original preview task")
    root = d.wait_for(["설정", "PIN 변경"])
    assert selected(root, "기록")
    select("기록")
    record("notification close restores profile; explicit tab selection still opens history")

    stats = d.control("stats")
    mutations = {key: value for key, value in stats["requests"].items()
                 if not key.startswith(("GET ", "HEAD ")) and value}
    assert not mutations, mutations
    select("지갑")
    return stats


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    failure, stats = None, None
    started = time.time()
    apk_path = d.adb("shell", "pm", "path", d.PACKAGE).decode().strip().removeprefix("package:")
    apk_hash = d.adb("shell", "sha256sum", apk_path).decode().split()[0]
    try:
        stats = run()
    except BaseException as error:
        failure = repr(error)
        raise
    finally:
        result = dict(complete=failure is None, failure=failure, cases=RESULTS,
                      started_at_unix=started, finished_at_unix=time.time(),
                      installed_preview_apk_sha256=apk_hash, fixture_stats=stats,
                      notification_steps=notification.STEPS,
                      environment="isolated preview APK, Android native UI, loopback fixture")
        (OUT / "native-verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
