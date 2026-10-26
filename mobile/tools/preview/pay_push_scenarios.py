#!/usr/bin/env python3
"""Exercise the production Pay data-only dispatcher through native SystemUI on .preview only."""
import copy
import json
import shlex
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer
from pathlib import Path

import device as d
import fixture_server as f
import notification_scenarios as n
import shipping_notification_scenarios as s
from biometric_revocation_scenarios import preferences, write_preferences

OUT = Path("docs/redesign/evidence/pay-push")
PAYMENT = "12121212-1212-4212-8212-121212121212"
DETAIL = "/api/pay/v1/payment-approvals/" + n.ID


class Handler(s.Handler):
    denied = False

    def route(self, method, path, query, body):
        if path == DETAIL and self.denied:
            return dict(code="RESOURCE_NOT_FOUND"), 404
        return super().route(method, path, query, body)


def payload(kind="PAYMENT_RESULT", status="SUCCESS", action=None):
    event = str(uuid.uuid4())
    return dict(eventId=event, pushId=event, type=kind,
                approvalId="" if kind == "PURCHASE_HANDOFF" else n.ID,
                purchaseRequestId=s.PID if kind == "PURCHASE_HANDOFF" else "",
                paymentId=PAYMENT if kind == "PAYMENT_RESULT" else "",
                status=status if kind == "PAYMENT_RESULT" else "",
                nextAction=action or {"PURCHASE_HANDOFF": "OPEN_PURCHASE", "APPROVAL_REQUESTED": "OPEN_APPROVAL",
                                      "PAYMENT_RESULT": "WAIT_FOR_PAYMENT_STATUS" if status == "UNKNOWN" else "SHOW_PAYMENT_RESULT"}[kind])


def post(data, expected=True):
    args = ["am", "broadcast", "-n", n.RECEIVER, "-a", n.NOTIFY_ACTION]
    for key, value in data.items():
        args += ["--es", key, value]
    output = d.adb("shell", shlex.join(args)).decode()
    assert ("result=-1" if expected else "result=0") in output, output


def tap(title, labels):
    s.tap_notification("로컬 테스트 · " + title, labels)
    return n.wait_top(lambda value: value["component"] == n.HOST, "notification host")


def readonly():
    labels = d.visible_labels(d.tree())
    assert not {"42,800원 승인", "승인 요청 재전송", "결제 정보로", "PIN 인증", "PIN으로 결제하기"} & labels, labels
    writes = {key: value for key, value in f.STATE.requests.items() if key.startswith(("POST /api/pay/", "PATCH /api/pay/"))}
    assert not writes, writes
    assert f.STATE.requests["GET " + DETAIL] >= 1


def shot(name):
    (OUT / (name + ".png")).write_bytes(d.adb("exec-out", "screencap", "-p"))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    original = preferences()
    assert any(node.get("name") == "tokens" for node in ET.fromstring(original)), "Preview fixture login required"
    mapping = d.adb("reverse", "--list").decode().splitlines()
    old = next((line.split()[-1] for line in mapping if " tcp:8787 " in line), None)
    saved_settings = {key: d.adb("shell", "settings", "get", "system", key).decode().strip()
                      for key in ("accelerometer_rotation", "user_rotation")}
    apk = d.adb("shell", "pm", "path", d.PACKAGE).decode().strip().removeprefix("package:")
    sha = d.adb("shell", "sha256sum", apk).decode().split()[0]
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    cases = []

    def begin(status="APPROVED", context="settings", signed_in=True):
        s.restore(original); s.S.reset(); Handler.denied = False
        f.STATE.current_status = status
        f.STATE.config.update(latency_ms=80)
        if signed_in:
            s.launch()
        else:
            root = ET.fromstring(original)
            for node in list(root):
                if node.get("name") in {"tokens", "unresolvedApproval", "unresolvedPurchase"}: root.remove(node)
            write_preferences(ET.tostring(root, encoding="utf-8", xml_declaration=True))
        return n.origin(context)

    def passed(name, **details):
        cases.append(dict(scenario=name, passed=True, requests=copy.deepcopy(dict(f.STATE.requests)), **details))
        print(name, "PASS", flush=True)

    try:
        d.adb("reverse", "tcp:8787", f"tcp:{server.server_port}")
        d.adb("shell", "pm", "grant", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")

        origin = begin("PENDING")
        handoff = payload("PURCHASE_HANDOFF"); post(handoff)
        host = tap("배송지 선택 요청", ["이 배송지로 선택"])
        assert any(node.get("selected") == "true" or node.get("checked") == "true" for node in d.tree().iter("node"))
        assert not s.S.posts
        s.explicit_return(origin, host)
        passed("eight-field purchase handoff retains default preselection without confirming")

        origin = begin("PENDING")
        first = payload("APPROVAL_REQUESTED"); post(first); post(first, False)
        tap("결제 승인 요청", ["42,800원 승인"])
        n.click("42,800원 승인"); d.wait_for(["PIN으로 결제하기"])
        d.enter_pin("123456"); fingerprint = n.pin_fingerprint()
        second = payload("APPROVAL_REQUESTED"); post(second)
        tap("결제 승인 요청", ["PIN으로 결제하기"])
        assert n.pin_fingerprint() == fingerprint
        post(second, False)
        n.click("닫고 돌아가기")
        n.wait_top(lambda value: n.same_task(value, origin), "original context")
        d.adb("shell", "am", "force-stop", d.PACKAGE); post(second, False)
        assert f.STATE.approve_requests == 0
        passed("event resend deduplicates across dismissal and process restart; distinct approval event preserves PIN")

        for wire_status, server_status, title, label, context in [
            ("SUCCESS", "COMPLETED", "결제 완료", "결제가 완료되었어요", "settings"),
            ("FAILED", "INVALIDATED", "결제 실패", "결제를 완료하지 못했어요", "home")]:
            origin = begin(server_status, context)
            event = payload(status=wire_status); post(event)
            host = tap(title, [label, "돌아가기"])
            readonly(); shot(wire_status.lower())
            post(event, False)
            post(payload(status="UNKNOWN"), False)
            post(payload("APPROVAL_REQUESTED"), False)
            s.explicit_return(origin, host)
            passed(f"{wire_status} reads authoritative result and waits for explicit return to {context}")

        origin = begin("PENDING")
        post(payload("APPROVAL_REQUESTED")); host = tap("결제 승인 요청", ["42,800원 승인"])
        f.STATE.current_status = "COMPLETED"
        post(payload()); result_host = tap("결제 완료", ["결제가 완료되었어요", "돌아가기"])
        assert n.same_task(host, result_host)
        readonly(); s.explicit_return(origin, result_host)
        passed("a tapped result advances the same approval task without resubmitting or losing original context")

        origin = begin("APPROVED")
        post(payload(status="UNKNOWN")); host = tap("결제 결과 확인 중", ["결제 결과 확인 중", "상태 확인"])
        readonly(); shot("unknown")
        f.STATE.current_status = "COMPLETED"
        d.wait_for(["결제가 완료되었어요"], timeout=30)
        readonly(); s.explicit_return(origin, host)
        passed("UNKNOWN remains pending and advances through GET only without an approval retry")

        origin = begin("APPROVED")
        post(payload(status="SUCCESS")); host = tap("결제 완료", ["결제 결과 확인 중"])
        readonly(); assert "결제가 완료되었어요" not in d.visible_labels(d.tree())
        time.sleep(17)
        before = f.STATE.requests["GET " + DETAIL]; time.sleep(3)
        assert before == f.STATE.requests["GET " + DETAIL], "GET polling did not stop at its bound"
        assert before <= 11
        s.explicit_return(origin, host)
        passed("stale success hint cannot override pending server state; polling is bounded", detail_reads=before)

        origin = begin("COMPLETED", signed_in=False)
        event = payload(); post(event); host = tap("결제 완료", ["반가워요", "아이디"])
        assert f.STATE.requests["GET " + DETAIL] == 0
        n.click("다음"); d.wait_for(["비밀번호"]); d.fill("preview-password"); n.click("로그인")
        d.wait_for(["결제가 완료되었어요", "돌아가기"], timeout=40); readonly(); shot("after-login")
        d.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        d.adb("shell", "settings", "put", "system", "user_rotation", "1")
        d.wait_for(["결제가 완료되었어요", "결제 번호"]); readonly()
        d.adb("shell", "settings", "put", "system", "user_rotation", "0")
        d.wait_for(["결제가 완료되었어요"])
        s.explicit_return(origin, n.top())
        passed("signed-out result requires login; result identity survives Activity recreation")

        origin = begin("COMPLETED"); Handler.denied = True
        post(payload()); host = tap("결제 완료", ["결제 결과를 불러오지 못했어요.", "돌아가기"])
        assert "결제가 완료되었어요" not in d.visible_labels(d.tree())
        s.explicit_return(origin, host)
        passed("inaccessible approval never displays the success carried by the push")

        begin("PENDING")
        event = payload("APPROVAL_REQUESTED")
        d.adb("shell", "pm", "revoke", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")
        post(event, False)
        d.adb("shell", "pm", "grant", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")
        post(event)
        malformed = payload(); malformed["pushId"] = "mismatch"; post(malformed, False)
        passed("disabled permission does not consume an event; malformed contract is ignored")
    except Exception:
        (OUT / "failure-activities.txt").write_bytes(d.adb("shell", "dumpsys", "activity", "activities"))
        (OUT / "failure-task-list.txt").write_bytes(d.adb("shell", "am", "stack", "list"))
        raise
    finally:
        s.restore(original)
        for key, value in saved_settings.items():
            if value == "null": d.adb("shell", "settings", "delete", "system", key)
            else: d.adb("shell", "settings", "put", "system", key, value)
        if old: d.adb("reverse", "tcp:8787", old)
        else: d.adb("reverse", "--remove", "tcp:8787")
        server.shutdown(); server.server_close()
        (OUT / "verification.json").write_text(json.dumps(dict(cases=cases, expected_scenarios=10, passed=len(cases),
            complete=len(cases)==10, installed_apk_sha256=sha, preview_preferences_restored=preferences()==original,
            environment="API 36 emulator; .preview package; exact eight-field payloads; native SystemUI; loopback fixture; no live FCM or financial mutations"), ensure_ascii=False, indent=2))


if __name__ == "__main__": main()
