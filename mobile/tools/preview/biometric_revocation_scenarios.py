#!/usr/bin/env python3
"""Exercise real app revocation against a disposable loopback fixture.

Only the .preview package is seeded, with a synthetic credential reference (no
hardware biometric key). Original preview preferences and port mapping are restored.
No real credential, account, backend, or payment is changed.
"""
import hashlib
import json
import re
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer
from pathlib import Path

import device as d
import fixture_server as fixture
from loading_motion_check import wait_enabled

OUT = Path("docs/redesign/evidence/biometric-revocation")
PATH = "/api/pay/v1/users/me/biometric-credentials"
PREFS = "shared_prefs/wallet.minimal.xml"
OFF = "생체 인증 끄기"
REGISTER = "생체 인증 등록"
NETWORK_ERROR = "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."
SERVER_ERROR = "요청을 처리하지 못했어요. 다시 시도해 주세요."


class CredentialFixture:
    def __init__(self):
        self.lock = threading.Lock()
        self.events = []
        self.registered = True
        self.outcome = 204
        self.delay = 0
        self.accepted = threading.Event()

    def arm(self, outcome=204, delay=0, registered=True):
        with self.lock:
            self.outcome, self.delay, self.registered = outcome, delay, registered
            self.events = []
            self.accepted.clear()


CREDENTIAL = CredentialFixture()


class Handler(fixture.Handler):
    def do_DELETE(self):
        if self.path != PATH:
            return super().handle_request()
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        key = self.headers.get("Idempotency-Key", "")
        device = self.headers.get("X-Device-Id", "")
        try:
            uuid.UUID(key)
            assert self.headers.get("Authorization") == "Bearer fixture-access-only"
            assert device and len(device) <= 150 and not body
            assert self.headers.get("Content-Type") is None
        except (ValueError, AssertionError):
            return self.reply(dict(code="BIOMETRIC_CREDENTIAL_INVALID"), 400)
        with CREDENTIAL.lock:
            outcome, delay = CREDENTIAL.outcome, CREDENTIAL.delay
            CREDENTIAL.events.append(dict(key=key, device=device, body_bytes=len(body), outcome=outcome))
        CREDENTIAL.accepted.set()
        if delay:
            time.sleep(delay)
        if outcome == 500:
            return self.reply(dict(code="INTERNAL_ERROR"), 500)
        if outcome == "offline":
            return self.disconnect()
        with CREDENTIAL.lock:
            CREDENTIAL.registered = False
        if outcome == "lost_response":
            return self.disconnect()
        self.send_response(204)
        self.end_headers()


def preferences():
    return d.adb("exec-out", "run-as", d.PACKAGE, "cat", PREFS)


def write_preferences(raw):
    assert d.PACKAGE == "com.copenhagen.wallet.preview"
    subprocess.run(["adb", "-s", "emulator-5554", "shell", "run-as", d.PACKAGE,
                    "sh", "-c", "'cat > " + PREFS + "'"], input=raw, check=True, capture_output=True)


def local_state():
    values = {node.get("name"): node.text for node in ET.fromstring(preferences())}
    return values.get("credentialId"), values.get("biometricRevocationKey")


def launch():
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    deadline = time.monotonic() + 35
    while True:
        try:
            return d.wait_for(["나의 지갑"], timeout=max(1, deadline - time.monotonic()))
        except (RuntimeError, ET.ParseError):
            if time.monotonic() >= deadline:
                raise


def settings():
    wait_enabled("내 정보 및 설정")
    d.tap("내 정보 및 설정")
    return d.wait_for(["설정", OFF])


def seed(original):
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    tree = ET.fromstring(original)
    for node in list(tree):
        if node.get("name") in {"credentialId", "biometricRevocationKey"}:
            tree.remove(node)
    ET.SubElement(tree, "string", name="credentialId").text = "fixture-biometric-credential"
    write_preferences(ET.tostring(tree, encoding="utf-8", xml_declaration=True))
    launch()
    return settings()


def click_rect(root, label):
    targets = [node for node in root.iter("node") if node.get("clickable") == "true"
               and label in d.visible_labels(node)]
    rects = [tuple(map(int, re.findall(r"\d+", node.get("bounds")))) for node in targets]
    return min(rects, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))


def tap(rect):
    x1, y1, x2, y2 = rect
    d.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def snapshot(name):
    (OUT / (name + ".png")).write_bytes(d.adb("exec-out", "screencap", "-p"))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    previous_mapping = d.adb("reverse", "--list").decode().splitlines()
    original_target = next((line.split()[-1] for line in previous_mapping if " tcp:8787 " in line), None)
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    original = preferences()
    assert any(n.get("name") == "tokens" for n in ET.fromstring(original)), "Login to the isolated fixture first"
    fixture.STATE.reset()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    cases = []
    failure = None
    try:
        d.adb("reverse", "tcp:8787", f"tcp:{server.server_port}")
        CREDENTIAL.arm(delay=8)
        root = seed(original)
        snapshot("enabled")
        rect = click_rect(root, OFF)
        tap(rect)
        assert CREDENTIAL.accepted.wait(3), "No DELETE was dispatched"
        tap(rect)
        assert local_state()[0] is not None, "Local credential cleared before server confirmation"
        root = d.tree()
        assert OFF in d.visible_labels(root) and REGISTER not in d.visible_labels(root)
        assert any(n.get("enabled") == "false" and OFF in d.visible_labels(n) for n in root.iter("node"))
        snapshot("pending")
        d.wait_for([REGISTER], absent=[OFF])
        assert local_state() == (None, None)
        assert len(CREDENTIAL.events) == 1, "Repeated tap duplicated DELETE"
        snapshot("disabled")
        cases.append(dict(case="delayed_success_and_double_tap", delete_count=1, delay_seconds=8))
        print("PASS delayed_success_and_double_tap", flush=True)

        # Revocation makes the next approval use the normal PIN screen; no approval is submitted.
        d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", "11111111-1111-4111-8111-111111111111")
        wait_enabled("42,800원 승인")
        d.tap("42,800원 승인")
        d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
        snapshot("pin-after-revocation")
        assert fixture.STATE.approve_requests == 0
        cases.append(dict(case="pin_entry_after_revocation", approval_requests=0))
        print("PASS pin_entry_after_revocation", flush=True)

        for outcome, label in [(500, SERVER_ERROR), ("offline", NETWORK_ERROR), ("lost_response", NETWORK_ERROR)]:
            CREDENTIAL.arm(outcome=outcome)
            seed(original)
            d.tap(OFF)
            d.wait_for([OFF, label], absent=[REGISTER])
            credential, key = local_state()
            assert credential and key and len(CREDENTIAL.events) == 1
            assert CREDENTIAL.registered == (outcome != "lost_response")
            snapshot(str(outcome) + "-retained")
            # No background retry on recreation. The same stored operation is used only on another tap.
            d.adb("shell", "am", "force-stop", d.PACKAGE)
            CREDENTIAL.outcome = 204
            launch()
            settings()
            assert len(CREDENTIAL.events) == 1
            assert local_state() == (credential, key)
            d.tap(OFF)
            d.wait_for([REGISTER], absent=[OFF])
            assert local_state() == (None, None)
            assert len(CREDENTIAL.events) == 2
            assert CREDENTIAL.events[0]["key"] == CREDENTIAL.events[1]["key"] == key
            assert CREDENTIAL.events[0]["device"] == CREDENTIAL.events[1]["device"]
            cases.append(dict(case=str(outcome) + "_restart_and_explicit_retry", delete_count=2,
                              same_key_and_device=True, no_automatic_retry=True))
            print("PASS", cases[-1]["case"], flush=True)

        CREDENTIAL.arm(registered=False)
        seed(original)
        d.tap(OFF)
        d.wait_for([REGISTER], absent=[OFF])
        assert local_state() == (None, None) and len(CREDENTIAL.events) == 1
        cases.append(dict(case="already_absent_is_success", delete_count=1))
        print("PASS already_absent_is_success", flush=True)

        # Confirm persistence of the off state after a process restart.
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        launch()
        wait_enabled("내 정보 및 설정")
        d.tap("내 정보 및 설정")
        d.wait_for([REGISTER], absent=[OFF])
        assert local_state() == (None, None) and len(CREDENTIAL.events) == 1
        cases.append(dict(case="disabled_state_survives_restart", delete_count=1))
        print("PASS disabled_state_survives_restart", flush=True)
    except Exception as error:
        failure = str(error)
        raise
    finally:
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        write_preferences(original)
        assert preferences() == original, "Preview preferences were not restored"
        if original_target:
            d.adb("reverse", "tcp:8787", original_target)
        else:
            d.adb("reverse", "--remove", "tcp:8787")
        server.shutdown()
        server.server_close()
        report = dict(cases=cases, failure=failure, package=d.PACKAGE, fixture_only=True,
                      native_biometric_key_seeded=False, actual_enrollment_tested=False,
                      original_preview_preferences_restored=True, approval_requests=fixture.STATE.approve_requests,
                      preview_apk_sha256=hashlib.sha256(Path("app/build/outputs/apk/preview/app-preview.apk").read_bytes()).hexdigest())
        (OUT / "native-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
