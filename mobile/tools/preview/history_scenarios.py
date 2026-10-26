"""Read-only purchase history checks on the isolated preview APK and loopback fixture.

Requires the owned emulator-5554, signed-in preview app and fixture_server.py on 8787.
No payment, card, account or delivery mutations are sent.
"""
import json
import time
from pathlib import Path

import device as d
from secondary_sweep import click, scroll_to

OUT = Path("docs/redesign/evidence/history-2026-09-22")
RESULTS = []


def capture(name, labels, absent=()):
    d.capture(OUT / (name + ".png"), labels, absent=absent)


def wait_notice(part):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        labels = d.visible_labels(d.tree())
        if any(part in label for label in labels):
            return
    raise AssertionError("Expected notice: " + part)


def start(states, **config):
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.control("reset", {})
    d.control("config", dict(merchant_count=2, checkout_statuses=states, status="APPROVED", latency_ms=0, **config))
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    d.wait_for(["나의 지갑"], timeout=35)
    d.tap("기록")


def record(name):
    stats = d.control("stats")
    assert stats["approve_requests"] == stats["logical_approvals"] == 0
    writes = {key: count for key, count in stats["requests"].items()
              if not key.startswith("GET ") and "/auth/" not in key and "/notification-installations" not in key}
    assert not writes, writes
    RESULTS.append(dict(name=name, passed=True, stats=stats))
    (OUT / "native-results.json").write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    print(name, "PASS", flush=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    start(["COMPLETED", "FAILED"])
    d.wait_for(["구매 기록", "일부 구매 완료", "24,300원", "42,800원"])
    capture("partial-list", ["구매 기록", "일부 구매 완료", "24,300원", "42,800원"])
    d.tap("완료")
    d.wait_for(["조건에 맞는 구매가 없어요"])
    capture("completed-filter", ["조건에 맞는 구매가 없어요"], absent=["24,300원"])
    d.tap("전체")
    d.tap("코펜하겐 스토어 외 1곳")
    d.wait_for(["구매 상세", "결제 완료 금액", "24,300원", "18,500원"])
    capture("partial-detail", ["구매 상세", "결제 완료 금액", "24,300원", "결제되지 않은 금액"])
    scroll_to("노르딕 문구점")
    scroll_to("위클리 노트")
    capture("merchant-results", ["노르딕 문구점", "위클리 노트", "결제 실패", "18,500원"])
    click("승인 내역 보기")
    d.wait_for(["일부 결제만 완료되었어요"])
    click("기록 보기")
    scroll_to("구매 상세", direction="up")
    d.wait_for(["구매 상세", "결제 완료 금액", "24,300원"])
    record("partial payment and result return to grouped purchase history")

    click("승인 내역 보기")
    scroll_to("일부 결제만 완료되었어요", direction="up")
    d.wait_for(["일부 결제만 완료되었어요"])
    d.tap("뒤로")
    scroll_to("구매 상세", direction="up")
    d.wait_for(["구매 상세", "결제 완료 금액"])
    record("Back from approval returns to purchase detail")

    click("주문·배송 보기")
    d.wait_for(["주문·배송", "에브리데이 토트백", "주문 확정"])
    d.tap("에브리데이 토트백")
    d.wait_for(["주문 상세", "주문 번호"])
    capture("order-detail", ["주문 상세", "주문 번호", "주문 확정"])
    record("order and delivery history remains available")

    start(["COMPLETED", "COMPLETING"])
    d.wait_for(["결제 처리 중", "24,300원"])
    d.tap("코펜하겐 스토어 외 1곳")
    d.wait_for(["구매 상세", "처리 중·승인 대기", "24,300원", "18,500원"])
    capture("pending-detail", ["구매 상세", "결제 처리 중", "처리 중·승인 대기", "18,500원"])
    d.control("config", {"checkout_statuses": ["COMPLETED", "COMPLETED"]})
    d.tap("새로고침")
    d.wait_for(["구매 완료", "결제 완료 금액", "42,800원"], absent=["처리 중·승인 대기"])
    capture("completed-detail", ["구매 완료", "결제 완료 금액", "42,800원"])
    record("pending outcome refreshes to completed without another approval")

    d.control("config", {"history_core_error": True})
    d.tap("새로고침")
    wait_notice("마지막으로 확인한 결과")
    capture("stale-detail", ["구매 완료", "42,800원"])
    record("failed refresh preserves data with an explicit stale notice")

    start(["FAILED", "FAILED"])
    d.wait_for(["구매를 진행하지 못했어요", "0원", "42,800원"])
    capture("failed-list", ["구매를 진행하지 못했어요", "0원", "42,800원"])
    record("fully failed purchase has no completed amount")

    start(["COMPLETED", "FAILED"], history_pay_error=True)
    d.wait_for(["판매처 2곳 구매", "일부 구매 완료", "24,300원"])
    d.tap("판매처 2곳 구매")
    d.wait_for(["구매 상세", "결제 완료 금액", "24,300원", "18,500원"])
    scroll_to("일부 상점·상품 정보를 확인하지 못했어요. 표시된 결제 결과와 금액은 구매 기록 기준이에요.")
    capture("metadata-unavailable", ["구매 상세", "결제 완료 금액", "24,300원"])
    record("Pay outage does not erase verified Core outcomes")

    start(["COMPLETED", "COMPLETED"], empty=True)
    d.wait_for(["아직 구매 기록이 없어요", "이전 주문은 주문·배송에서 확인할 수 있어요."])
    capture("empty", ["아직 구매 기록이 없어요", "최근 AI 구매 요청을 최대 50건까지 보여드려요."])
    record("empty recent history explains scope and previous orders")

    start(["COMPLETED", "FAILED"])
    d.wait_for(["일부 구매 완료", "24,300원"])
    d.fill("no-match")
    d.hide_keyboard()
    d.wait_for(["조건에 맞는 구매가 없어요"])
    d.tap("승인 기록")
    d.wait_for(["승인 전달됨 기록"])
    d.tap("구매 기록")
    d.wait_for(["조건에 맞는 구매가 없어요"])
    assert any(n.get("class") == "android.widget.EditText" and n.get("text") == "no-match" for n in d.nodes())
    record("purchase search survives history subsection changes")


if __name__ == "__main__":
    main()
