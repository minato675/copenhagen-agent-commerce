"""Current Pay/Core contract on the isolated preview APK and loopback fixture server.

Requires an installed, signed-in preview APK, emulator-5554 and fixture_server.py
on port 8787. Never opens a live backend or clears app/account data.
"""
import json
import shlex
import uuid
from pathlib import Path

import device as d
import notification_scenarios as n
import shipping_notification_scenarios as s

OUT = Path("docs/redesign/evidence/backend-compatibility-2026-09-22")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    cases = [
        ("partial", ["COMPLETED", "FAILED"], "일부 결제만 완료되었어요"),
        ("failed", ["FAILED", "FAILED"], "결제를 완료하지 못했어요"),
        ("pending", ["COMPLETED", "COMPLETING"], "결제 결과 확인 중"),
    ]
    d.adb("shell", "pm", "grant", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")
    for name, statuses, expected in cases:
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        d.control("reset", {})
        d.control("config", {"merchant_count": 2, "checkout_statuses": statuses, "status": "APPROVED", "latency_ms": 0})
        d.adb("shell", "am", "start", "-n", d.ACTIVITY)
        d.wait_for(["나의 지갑"], timeout=35)
        event = str(uuid.uuid4())
        # Deliberately stale SUCCESS hint: only the verified Core state may set the receipt.
        payload = dict(eventId=event, pushId=event, type="PAYMENT_RESULT", approvalId=n.ID,
                       purchaseRequestId="", paymentId=str(uuid.uuid4()), status="SUCCESS", nextAction="SHOW_PAYMENT_RESULT")
        args = ["am", "broadcast", "-n", n.RECEIVER, "-a", n.NOTIFY_ACTION]
        for key, value in payload.items():
            args += ["--es", key, value]
        output = d.adb("shell", shlex.join(args)).decode()
        assert "result=-1" in output, output
        s.tap_notification("로컬 테스트 · 결제 완료", [expected, "판매처별 결제 결과"])
        labels = d.visible_labels(d.tree())
        assert not {"42,800원 승인", "승인 요청 재전송", "결제 정보로", "PIN으로 결제하기"} & labels
        assert "결제가 완료되었어요" not in labels
        d.capture(OUT / (name + ".png"), [expected, "판매처별 결제 결과"])
        stats = d.control("stats")
        assert stats["approve_requests"] == stats["logical_approvals"] == 0
        assert stats["requests"].get("GET /api/core/v1/purchase-requests/99999999-9999-4999-8999-999999999999", 0) > 0
        results.append(dict(scenario=name, passed=True, notification_hint="SUCCESS", checkout_statuses=statuses,
                            displayed_result=expected, stats=stats))
        print(name, "PASS", flush=True)
    (OUT / "native-results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
