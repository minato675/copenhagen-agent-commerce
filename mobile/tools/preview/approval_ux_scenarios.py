"""Native approval UX on the isolated preview APK and loopback fixture.

Requires ownership of emulator-5554, a fixture login, and emulator fingerprint 1
enrolled through Android Settings. Only in-memory fixture payments are submitted.
"""
import json
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import device as d
from secondary_sweep import click, scroll_to

OUT = Path("docs/redesign/evidence/approval-ux-2026-09-22")
ID = "11111111-1111-4111-8111-111111111111"
RESULTS = []


def capture(name, labels):
    d.capture(OUT / (name + ".png"), labels)


def click_button(label):
    candidates = [n for n in d.tree().iter("node") if n.get("clickable") == "true" and
                  n.get("enabled") == "true" and label in d.visible_labels(n)]
    assert len(candidates) == 1, "Expected one enabled action, excluding duplicate headings"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", candidates[0].get("bounds")))
    d.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def record(name):
    RESULTS.append(dict(name=name, passed=True, stats=d.control("stats")))
    (OUT / "native-results.json").write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    print(name, "PASS", flush=True)


def restart(reset=True, **config):
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    if reset:
        d.control("reset", {})
    d.control("config", dict(latency_ms=0, **config))
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    if config.get("pin_status") == "NOT_REGISTERED":
        d.wait_for(["결제 PIN 등록"])
    else:
        d.wait_for(["나의 지갑"], timeout=35)


def review():
    d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", ID)
    d.wait_for(["코펜하겐 스토어", "42,800원"])
    scroll_to("42,800원 승인")


def prompt_tree():
    d.adb("shell", "uiautomator", "dump", "/sdcard/approval-prompt.xml")
    root = ET.fromstring(d.adb("shell", "cat", "/sdcard/approval-prompt.xml"))
    packages = {n.get("package") for n in root.iter("node")}
    assert packages & {d.PACKAGE, "com.android.systemui"}, packages
    return root


def prompt(label):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        root = prompt_tree()
        if any(n.get("text") == label and n.get("package") == "com.android.systemui" for n in root.iter("node")):
            return root
    raise AssertionError("System biometric prompt did not appear automatically")


def prompt_tap(label):
    root = prompt(label)
    n = next(n for n in root.iter("node") if n.get("text") == label and n.get("package") == "com.android.systemui")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    d.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    restart(multi_item=True, purchase_needs_address=True)
    d.tap("결제")
    click("요청 99999999")
    d.wait_for(["배송지 선택", "에브리데이 토트백", "미니 파우치", "코튼 키링"])
    capture("shipping-items", ["배송지 선택", "에브리데이 토트백", "미니 파우치", "코튼 키링"])
    assert d.control("stats")["approve_requests"] == 0
    record("shipping products have individual rows")

    restart(multi_item=True)
    review()
    scroll_to("코펜하겐 스토어", direction="up")
    capture("approval-items", ["에브리데이 토트백", "미니 파우치", "코튼 키링"])
    click("42,800원 승인")
    d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
    d.enter_pin("12")
    click("PIN 한 자리 지우기")
    assert d.pin_fingerprint()["character_count"] == 1
    click("PIN 전체 지우기")
    assert d.pin_fingerprint()["character_count"] == 0
    capture("random-pin", ["PIN 6자리", "PIN 전체 지우기", "PIN 한 자리 지우기"])
    d.enter_pin("123456")
    assert d.control("stats")["approve_requests"] == 0
    click("42,800원 승인")
    d.wait_for(["승인 결과", "기록 보기"])
    stats = d.control("stats")
    assert stats["logical_approvals"] == stats["approve_requests"] == 1
    assert stats["authentication_methods"] == {"PIN": 1}
    record("unregistered biometric opens random PIN and submits once")

    for count, title in [(1, "코펜하겐 스토어"), (2, "코펜하겐 스토어 외 1곳")]:
        restart(merchant_count=count, status="APPROVED", checkout_statuses=["COMPLETED"]*count)
        d.tap("기록")
        d.wait_for(["구매 기록", title])
        capture("history-" + str(count), ["구매 기록", title])
        record("history seller title with " + str(count) + " merchants")

    restart(pin_status="NOT_REGISTERED")
    d.enter_pin("123456")
    click("다음")
    d.wait_for(["PIN 다시 입력"])
    assert d.pin_fingerprint()["character_count"] == 0
    d.enter_pin("123456")
    click("등록")
    d.wait_for(["생체 인증 등록"])
    assert d.control("stats")["requests"]["POST /api/pay/v1/users/me/payment-pin"] == 1
    capture("pin-enrolled", ["생체 인증 등록"])
    d.tap("나중에")
    d.wait_for(["나의 지갑"])
    record("PIN enrollment and confirmation use random keypad")

    d.tap("내 정보 및 설정")
    click("PIN 변경")
    d.wait_for(["현재 PIN"])
    d.enter_pin("123456"); click("다음")
    d.wait_for(["새 PIN"])
    d.enter_pin("654321"); click("다음")
    d.wait_for(["새 PIN 확인"])
    d.enter_pin("654321")
    capture("pin-change", ["새 PIN 확인"])
    scroll_to("처음부터 다시 입력")
    click_button("PIN 변경")
    d.wait_for(["생체 인증 등록"])
    assert d.control("stats")["requests"]["PUT /api/pay/v1/users/me/payment-pin"] == 1
    record("PIN change runs current new and confirmation steps")

    biometric_scenarios()


def biometric_scenarios():
    # Reset fixture PIN before enrolling the emulator's actual AndroidKeyStore key.
    restart()
    d.tap("내 정보 및 설정")
    click("생체 인증 등록")
    prompt("취소")
    d.adb("emu", "finger", "touch", "1")
    d.wait_for(["생체 인증 끄기"])
    assert d.control("stats")["biometric_credential_count"] == 1
    record("real emulator biometric key enrolls in fixture")

    review()
    d.tap("42,800원 승인")
    root = prompt("PIN으로 결제하기")
    (OUT / "biometric-prompt.xml").write_bytes(ET.tostring(root, encoding="utf-8"))
    (OUT / "biometric-prompt.png").write_bytes(d.adb("exec-out", "screencap", "-p"))
    assert d.control("stats")["approve_requests"] == 0
    prompt_tap("PIN으로 결제하기")
    d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
    assert d.control("stats")["approve_requests"] == 0
    capture("biometric-pin-fallback", ["PIN 6자리", "PIN으로 결제를 이어갈 수 있어요."])
    d.enter_pin("123456"); click("42,800원 승인")
    d.wait_for(["승인 결과", "기록 보기"])
    assert d.control("stats")["authentication_methods"] == {"PIN": 1}
    record("approval opens biometric immediately and cancellation falls back to PIN")

    restart(reset=False, status="PENDING")
    review()
    d.tap("42,800원 승인")
    prompt("PIN으로 결제하기")
    d.adb("emu", "finger", "touch", "1")
    d.wait_for(["승인 결과", "기록 보기"])
    stats = d.control("stats")
    assert stats["authentication_methods"] == {"PIN": 1, "BIOMETRIC": 1}
    assert stats["logical_approvals"] == stats["approve_requests"] == 2
    record("biometric success signs and approves with no extra confirmation")


if __name__ == "__main__":
    main()
