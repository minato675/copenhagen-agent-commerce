#!/usr/bin/env python3
"""Native notification/task regression scenarios for the isolated fixture APK.

Run one scenario only while owning emulator-5554. Each scenario resets the local
fixture and restarts the preview process without clearing its saved login. The
notification is posted by the preview receiver and tapped in Android SystemUI;
the approval activity is never launched directly. Results must remain in that
activity until the user taps 돌아가기. All payments are local fixtures.
"""
import argparse
import json
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

import device as d

ID = "11111111-1111-4111-8111-111111111111"
RECEIVER = d.PACKAGE + "/com.copenhagen.wallet.PreviewNotificationReceiver"
NOTIFY_ACTION = d.PACKAGE + ".NOTIFY"
NOTIFICATION_TITLE = "로컬 테스트 · 결제 승인 요청"
HOST = d.PACKAGE + "/com.copenhagen.wallet.PreviewApprovalActivity"
SYSTEM_UI = "com.android.systemui"
OUT = Path("docs/redesign/evidence/notification")
STEPS = []


def adb(*args):
    return subprocess.run(["adb", "-s", "emulator-5554", *args], check=True,
                          capture_output=True, timeout=20).stdout


def control(path, value=None):
    request = urllib.request.Request("http://127.0.0.1:8787/__control/" + path,
        data=None if value is None else json.dumps(value).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)


def canonical_component(value):
    package, activity = value.split("/", 1)
    return package + "/" + (package + activity if activity.startswith(".") else activity)


def parse_top(dump):
    """Extract the resumed ActivityRecord, including its actual Android task ID."""
    for marker in ("topResumedActivity", "mResumedActivity"):
        for line in dump.splitlines():
            if marker not in line:
                continue
            found = re.search(r"\bu\d+\s+([^\s]+/[^\s]+)\s+t(\d+)\b", line)
            if found:
                return dict(component=canonical_component(found[1]), task_id=int(found[2]),
                            activity_record=line.strip(), observed_at_unix=time.time())
    return None


def top():
    return parse_top(adb("shell", "dumpsys", "activity", "activities").decode())


def notification_task_present(dump, host):
    task = str(host["task_id"])
    # Orientation/history diagnostics retain old ActivityRecord references after task removal.
    # Count actual task/history rows only, never those diagnostic source= records.
    if re.search(r"(?m)^\s*\*\s+Task\{[^}\n]*\s#" + task + r"(?:\s|})", dump):
        return True
    for component, task_id in re.findall(r"(?m)^\s*\*\s+Hist\s+#\d+:\s+ActivityRecord\{[^}\n]*\bu\d+\s+([^\s]+/[^\s]+)\s+t(\d+)\b", dump):
        if canonical_component(component) == HOST and int(task_id) == host["task_id"]:
            return True
    return False


def wait_notification_task_gone(host, timeout=15):
    deadline = time.monotonic() + min(timeout, 30)
    while time.monotonic() < deadline:
        dump = adb("shell", "dumpsys", "activity", "activities").decode()
        if not notification_task_present(dump, host):
            return dict(removed_task_id=host["task_id"], notification_task_absent=True)
        time.sleep(.2)
    raise TimeoutError("Notification task still exists: " + repr(host))


def preview_process_ids():
    found = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", d.PACKAGE],
                           capture_output=True, timeout=10)
    assert found.returncode in (0, 1), found.stderr.decode()
    return found.stdout.decode().split()


def same_task(left, right):
    return bool(left and right and left["component"] == right["component"]
                and left["task_id"] == right["task_id"])


def wait_top(predicate, description, timeout=40):
    deadline = time.monotonic() + min(timeout, 40)
    last = None
    while time.monotonic() < deadline:
        last = top()
        if last and predicate(last):
            return last
        time.sleep(.2)
    raise TimeoutError(f"Expected {description}; last resumed activity: {last}")


def native_tree(required_package):
    """SystemUI is allowed only for the notification shade, never arbitrary app taps."""
    if required_package not in (d.PACKAGE, SYSTEM_UI):
        raise ValueError("Only preview and native notification surfaces are supported")
    adb("shell", "uiautomator", "dump", "/sdcard/preview-notification-window.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/preview-notification-window.xml"))
    if not any(n.get("package") == required_package for n in root.iter("node")):
        raise RuntimeError("Expected native surface is absent: " + required_package)
    return root


def preview_labels(root):
    return sorted({value for node in root.iter("node")
                   if node.get("package") == d.PACKAGE and node.get("class") != "android.widget.EditText"
                   for value in (node.get("text"), node.get("content-desc")) if value})


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    assert x2 > x1 and y2 > y1, "Cannot tap an empty native target"
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def scroll_to(label, direction="down", attempts=5):
    for attempt in range(attempts + 1):
        root = native_tree(d.PACKAGE)
        if label in d.visible_labels(root):
            return root
        if attempt == attempts:
            break
        areas = [bounds(n) for n in root.iter("node") if n.get("package") == d.PACKAGE]
        width, height = max(b[2] for b in areas), max(b[3] for b in areas)
        start, end = (.76, .3) if direction == "down" else (.3, .8)
        adb("shell", "input", "swipe", str(width // 2), str(int(height * start)),
            str(width // 2), str(int(height * end)), "250")
    raise RuntimeError("Label not found after bounded scroll: " + label)


def click(label, direction="down"):
    root = scroll_to(label, direction)
    matches = [n for n in root.iter("node") if n.get("package") == d.PACKAGE
               and (n.get("text") == label or n.get("content-desc") == label)]
    tap_node(matches[-1])


def checkpoint(name, include_preview=False, extra=None):
    value = dict(name=name, top=top(), fixture=control("stats"))
    if include_preview:
        value["visible_labels"] = preview_labels(native_tree(d.PACKAGE))
        value["top_after_semantics"] = top()
    if extra:
        value.update(extra)
    STEPS.append(value)
    print(name, json.dumps(value["top"], ensure_ascii=False), flush=True)
    return value


def assert_counts(approves=0, logical=0, rejects=0, status="PENDING"):
    stats = control("stats")
    assert stats["approve_requests"] == approves, stats
    assert stats["logical_approvals"] == logical, stats
    assert stats["requests"].get(f"POST /api/pay/v1/payment-approvals/{ID}/reject", 0) == rejects, stats
    assert stats["authoritative_status"] == status, stats
    return stats


def assert_zero_payment_posts():
    stats = assert_counts()
    posts = {key: value for key, value in stats["requests"].items()
             if key.startswith("POST /api/pay/v1/payment-approvals") and value}
    assert not posts, posts


def start_main(mode="approved"):
    control("reset", {})
    control("config", dict(mode=mode, latency_ms=80))
    adb("reverse", "tcp:8787", "tcp:8787")
    adb("shell", "pm", "grant", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "force-stop", d.PACKAGE)
    adb("shell", "am", "start", "-n", d.ACTIVITY)
    d.wait_for(["나의 지갑", "결제 확인"], timeout=40)
    return wait_top(lambda value: value["component"] == d.ACTIVITY, "the main preview activity")


def origin(context):
    if context == "settings":
        adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        result = wait_top(lambda value: value["component"].startswith("com.android.settings/"), "Android Settings")
    elif context == "home":
        resolved = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a",
                       "android.intent.action.MAIN", "-c", "android.intent.category.HOME").decode()
        components = [line.strip() for line in resolved.splitlines() if "/" in line and " " not in line.strip()]
        assert components, "Cannot resolve the native Home activity: " + resolved
        home = canonical_component(components[-1])
        adb("shell", "input", "keyevent", "3")
        result = wait_top(lambda value: value["component"] == home, "native Home")
    elif context == "history":
        click("기록")
        d.wait_for(["승인 기록", "주문·배송"])
        click("주문·배송")
        d.wait_for(["주문·배송", "에브리데이 토트백", "주문 완료"])
        result = wait_top(lambda value: value["component"] == d.ACTIVITY, "main history")
    else:
        raise ValueError(context)
    checkpoint("origin-" + context, include_preview=context == "history")
    return result


def post_and_tap(expected=("요청 총액", "변경")):
    broadcast = adb("shell", "am", "broadcast", "-n", RECEIVER, "-a", NOTIFY_ACTION,
                    "--es", "approvalId", ID).decode()
    assert "result=-1" in broadcast, "Notification was not posted: " + broadcast
    adb("shell", "cmd", "statusbar", "expand-notifications")
    deadline = time.monotonic() + 25
    target = None
    shade_labels = []
    shade_error = None
    while time.monotonic() < deadline:
        try:
            root = native_tree(SYSTEM_UI)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError, RuntimeError) as error:
            # UIAutomator can report a null root while Android first expands the shade.
            shade_error = repr(error)
            continue
        shade_labels = [n.get("text") for n in root.iter("node")
                        if n.get("package") == SYSTEM_UI and n.get("text")]
        candidates = [n for n in root.iter("node") if n.get("package") == SYSTEM_UI
                      and n.get("text") == NOTIFICATION_TITLE]
        if candidates:
            target = candidates[-1]
            break
    assert target is not None, "Native fixture notification not found in shade: " + repr((shade_labels, shade_error))
    tap_node(target)
    host = wait_top(lambda value: value["component"] == HOST, "PreviewApprovalActivity opened by notification")
    d.wait_for(list(expected), timeout=40)
    checkpoint("notification-tapped", include_preview=True,
               extra=dict(broadcast_result=broadcast.strip(), shade_title=NOTIFICATION_TITLE))
    return host


def enter_pin():
    click("42,800원 승인")
    d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
    d.enter_pin("123456")
    return pin_fingerprint()


def pin_fingerprint():
    return d.pin_fingerprint()


def restored(before, context):
    after = wait_top(lambda value: same_task(value, before), "the same original component and task")
    checkpoint("returned-" + context, include_preview=context == "history")
    assert after["component"] != HOST
    if context == "history":
        d.wait_for(["승인 기록", "주문·배송", "에브리데이 토트백", "주문 완료"], absent=["PIN으로 결제하기", "요청 총액"])
    return after


def result_awaiting_return(host, status, approves, logical, rejects):
    title = {"APPROVED": "승인을 전달했어요", "COMPLETED": "결제가 완료되었어요",
             "REJECTED": "요청을 거절했어요"}[status]
    d.wait_for([title], timeout=40)
    assert same_task(top(), host), "Result automatically left the notification task"
    assert_counts(approves, logical, rejects, status)
    result = checkpoint("result-awaiting-explicit-return", include_preview=True,
                        extra=dict(expected_result_title=title, expected_status=status))
    assert same_task(result["top"], host) and same_task(result["top_after_semantics"], host), result
    d.capture(str(OUT / (status.lower() + "-awaiting-return.png")), [title, "돌아가기"])
    click("돌아가기")


def approve(context, mode="approved"):
    start_main(mode)
    status = "COMPLETED" if mode == "completed" else "APPROVED"
    before = origin(context)
    host = post_and_tap()
    assert host["task_id"] != before["task_id"], "Notification reused the original task"
    entered = enter_pin()
    assert entered["character_count"] == 6
    assert_zero_payment_posts()
    checkpoint("before-explicit-approve", include_preview=True)
    click("42,800원 승인")
    result_awaiting_return(host, status, 1, 1, 0)
    restored(before, context)
    assert_counts(1, 1, 0, status)


def reject(context):
    start_main()
    before = origin(context)
    host = post_and_tap()
    assert host["task_id"] != before["task_id"], "Notification reused the original task"
    click("요청 거절")
    d.wait_for(["승인 요청을 거절할까요?", "거절"])
    assert_zero_payment_posts()
    click("거절")
    result_awaiting_return(host, "REJECTED", 0, 0, 1)
    restored(before, context)
    assert_counts(0, 0, 1, "REJECTED")


def review_back():
    start_main()
    before = origin("settings")
    post_and_tap()
    assert_zero_payment_posts()
    adb("shell", "input", "keyevent", "4")
    restored(before, "settings")
    assert_zero_payment_posts()


def duplicate_pin():
    start_main()
    before = origin("settings")
    host = post_and_tap()
    fingerprint = enter_pin()
    assert fingerprint["character_count"] == 6
    checkpoint("pin-entered-before-duplicate", include_preview=True, extra=dict(pin=fingerprint))
    repeated = post_and_tap(("PIN으로 결제하기", "PIN 6자리"))
    assert same_task(host, repeated), "Duplicate notification replaced the approval host task"
    after = pin_fingerprint()
    assert after == fingerprint, "Duplicate notification cleared the in-progress PIN"
    assert_zero_payment_posts()
    checkpoint("duplicate-preserved-pin", include_preview=True, extra=dict(pin=after))
    click("42,800원 승인")
    result_awaiting_return(host, "APPROVED", 1, 1, 0)
    restored(before, "settings")
    assert_counts(1, 1, 0, "APPROVED")


def unknown_disconnect():
    start_main("disconnect")
    before = origin("settings")
    host = post_and_tap()
    enter_pin()
    click("42,800원 승인")
    d.wait_for(["처리 확인 필요"], timeout=40)
    assert same_task(top(), host), "Unknown response incorrectly returned to the caller"
    assert_counts(1, 1, 0, "APPROVED")
    unknown = checkpoint("unknown-stays-in-notification", include_preview=True)
    assert same_task(unknown["top_after_semantics"], host)
    # A status read establishes truth; returning still requires a separate explicit action.
    click("상태 확인")
    scroll_to("승인을 전달했어요", direction="up")
    d.wait_for(["승인을 전달했어요"], timeout=25)
    confirmed = checkpoint("read-confirmed-still-in-notification", include_preview=True)
    assert same_task(confirmed["top"], host) and same_task(confirmed["top_after_semantics"], host), confirmed
    assert_counts(1, 1, 0, "APPROVED")
    click("돌아가기")
    restored(before, "settings")
    assert_counts(1, 1, 0, "APPROVED")


def cold_settings():
    start_main()
    before = origin("settings")
    adb("shell", "am", "force-stop", d.PACKAGE)
    deadline = time.monotonic() + 5
    while preview_process_ids() and time.monotonic() < deadline:
        time.sleep(.1)
    assert not preview_process_ids(), "Preview process survived force-stop"
    assert same_task(top(), before), "Preparing the cold launch changed the Settings task"
    checkpoint("cold-before-notification", extra=dict(preview_process_ids=[]))
    # Receiver starts the cold process; no main-activity launch occurs before the tap.
    host = post_and_tap()
    assert host["task_id"] != before["task_id"]
    checkpoint("cold-notification-host", include_preview=True,
               extra=dict(preview_process_ids=preview_process_ids()))
    entered = enter_pin()
    assert entered["character_count"] == 6
    assert_zero_payment_posts()
    click("42,800원 승인")
    result_awaiting_return(host, "APPROVED", 1, 1, 0)
    restored(before, "settings")
    assert_counts(1, 1, 0, "APPROVED")


def main_handoff():
    start_main()
    before = origin("history")
    host = post_and_tap()
    assert_zero_payment_posts()
    origin("home")
    # Match an explicit Android launcher intent to the existing main task.
    adb("shell", "am", "start", "-n", d.ACTIVITY, "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000")
    restored(before, "history")
    removal = wait_notification_task_gone(host)
    assert_zero_payment_posts()
    checkpoint("main-launcher-restored-history", include_preview=True, extra=removal)


def processing_return():
    start_main()
    latency_ms = 15000
    control("config", dict(mode="approved", latency_ms=latency_ms))
    before = origin("settings")
    host = post_and_tap()
    enter_pin()
    click("42,800원 승인")
    d.wait_for(["승인 전달 중"], timeout=20)
    assert same_task(top(), host), "Processing left the notification before explicit return"
    stats = assert_counts(1, 1, 0, "APPROVED")
    accepted_at = stats["events"][0]["at_monotonic"]
    reply_at = accepted_at + latency_ms / 1000
    checkpoint("processing-before-explicit-return", include_preview=True,
               extra=dict(fixture_reply_scheduled_at_monotonic=reply_at))
    click("닫고 돌아가기")
    restored(before, "settings")
    left_at = time.monotonic()
    assert left_at < reply_at, "Harness reached Settings after the delayed reply; processing return was not exercised"
    # Observe the original task through the deterministic reply window. This is not
    # an animation/performance measurement or a claim about exact UI response time.
    deadline = time.monotonic() + 30
    observe_until = reply_at + 1.5
    observations = 0
    while time.monotonic() < observe_until:
        assert time.monotonic() < deadline, "Fixture settlement observation exceeded 30 seconds"
        assert same_task(top(), before), "Late payment result interrupted the original Settings task"
        observations += 1
        time.sleep(.25)
    assert_counts(1, 1, 0, "APPROVED")
    removal = wait_notification_task_gone(host)
    checkpoint("settled-without-reopening-notification", extra=dict(
        **removal, continuous_task_observations=observations,
        returned_before_fixture_reply=True, left_at_monotonic=left_at,
        fixture_reply_scheduled_at_monotonic=reply_at,
        observation_note="Original task observed through configured fixture reply deadline plus 1.5 seconds."))


SCENARIOS = {
    "approve-settings": lambda: approve("settings"),
    "completed-settings": lambda: approve("settings", mode="completed"),
    "reject-settings": lambda: reject("settings"),
    "approve-home": lambda: approve("home"),
    "reject-home": lambda: reject("home"),
    "review-back": review_back,
    "duplicate-pin": duplicate_pin,
    "unknown-disconnect": unknown_disconnect,
    "main-history": lambda: approve("history"),
    "cold-settings": cold_settings,
    "main-handoff": main_handoff,
    "processing-return": processing_return,
}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=SCENARIOS)
    parser.add_argument("--output-dir", type=Path, default=OUT,
                        help="Evidence directory; use a separate directory for each verification pass")
    args = parser.parse_args()
    OUT = args.output_dir
    OUT.mkdir(parents=True, exist_ok=True)
    failure = None
    started = time.time()
    apk_path = adb("shell", "pm", "path", d.PACKAGE).decode().strip().removeprefix("package:")
    apk_sha256 = adb("shell", "sha256sum", apk_path).decode().split()[0]
    try:
        SCENARIOS[args.scenario]()
    except BaseException as error:
        failure = repr(error)
        raise
    finally:
        # Never navigate away or clear an uncertain operation merely to make a failed run tidy.
        result = dict(scenario=args.scenario, complete=failure is None, failure=failure,
                      started_at_unix=started, finished_at_unix=time.time(), steps=STEPS,
                      installed_apk_sha256=apk_sha256,
                      environment="isolated preview APK + loopback fixture; native SystemUI notification tap")
        try:
            result["final_top"] = top()
            result["final_fixture"] = control("stats")
        except Exception as evidence_error:
            result["final_evidence_error"] = repr(evidence_error)
        (OUT / (args.scenario + ".json")).write_text(json.dumps(result, ensure_ascii=False, indent=2))
