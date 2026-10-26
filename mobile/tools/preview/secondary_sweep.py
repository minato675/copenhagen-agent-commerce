#!/usr/bin/env python3
"""Bounded native secondary-route sweep; run only with ownership of emulator-5554.

All API traffic is restricted by the preview APK to the loopback fixture. This
driver never submits a payment, registers a card, changes a PIN/server, logs out,
launches Kakao, or opens device notification/biometric enrollment. The optional
cart quantity round trip is an in-memory fixture mutation, restored immediately.
"""
import argparse
import json
import re
import time
from pathlib import Path

import device as d

OUT = Path("docs/redesign/evidence/secondary")
RESULTS = []
NETWORK_ERROR = "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds"))))


def swipe(direction="down"):
    root = d.tree()
    areas = [bounds(n) for n in root.iter("node") if n.get("package") == d.PACKAGE]
    width = max(b[2] for b in areas)
    height = max(b[3] for b in areas)
    start, end = (.75, .3) if direction == "down" else (.3, .8)
    d.adb("shell", "input", "swipe", str(width // 2), str(int(height * start)),
          str(width // 2), str(int(height * end)), "280")


def scroll_to(label, direction="down", attempts=5):
    for attempt in range(attempts + 1):
        root = d.tree()
        if label in d.visible_labels(root):
            return root
        if attempt != attempts:
            swipe(direction)
    raise RuntimeError(f"Visible label not found after bounded scroll: {label}")


def click(label, direction="down"):
    scroll_to(label, direction)
    d.tap(label)


def capture(name, expected, absent=()):
    d.capture(str(OUT / (name + ".png")), expected, absent=absent)
    RESULTS.append(dict(name=name, required_labels=expected, absent=list(absent)))
    print("captured", name, flush=True)


def back(expected):
    d.hide_keyboard()
    d.tap("뒤로")
    scroll_to(expected[0], direction="up")
    d.wait_for(expected)


def home():
    d.hide_keyboard()
    for _ in range(7):
        labels = d.visible_labels(d.tree())
        if "지갑" in labels:
            d.tap("지갑")
            scroll_to("나의 지갑", direction="up")
            d.wait_for(["나의 지갑"])
            return
        if "뒤로" in labels:
            d.tap("뒤로")
        else:
            raise RuntimeError("No known route back to wallet; refusing unexpected navigation")
    raise RuntimeError("Could not return to wallet within bounded back navigation")


def open_wallet(label, expected):
    home()
    click(label)
    d.wait_for(expected)


def fill_field(label, value):
    root = scroll_to(label)
    candidates = [node for node in root.iter("node")
                  if node.get("class") == "android.widget.EditText"
                  and label in d.visible_labels(node)]
    if len(candidates) != 1:
        raise RuntimeError("Expected one labelled input: " + label)
    node = candidates[0]
    if node.get("text"):
        raise RuntimeError("Refusing to overwrite an existing input: " + label)
    x1, y1, x2, y2 = bounds(node)
    d.adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    d.adb("shell", "input", "text", value)
    d.hide_keyboard()


def receipt_search():
    """Run before resetting the parent's completed fixture; no payment is submitted."""
    before = d.control("stats")
    assert before["authoritative_status"] == "COMPLETED", "Expected the completed fixture"
    scroll_to("상점 검색", direction="up")
    fill_field("상점 검색", "zzz")
    d.wait_for(["검색 결과가 없어요"])
    capture("receipt-search-nonmatching", ["상점 검색", "검색 결과가 없어요"])
    d.adb("shell", "am", "start", "-n", d.ACTIVITY,
          "--es", "approvalId", "11111111-1111-4111-8111-111111111111")
    d.wait_for(["결제가 완료되었어요", "기록에서 보기"])
    d.tap("기록에서 보기")
    d.wait_for(["승인 기록", "코펜하겐 스토어", "결제 완료 기록"], absent=["기록에서 보기", "검색 결과가 없어요"])
    fields = [n for n in d.nodes() if n.get("class") == "android.widget.EditText"]
    assert fields and all(not n.get("text") for n in fields), "Receipt arrival retained a search query"
    capture("receipt-arrival-clears-search", ["승인 기록", "코펜하겐 스토어", "결제 완료 기록"],
            absent=["검색 결과가 없어요", "기록에서 보기"])
    after = d.control("stats")
    assert before["approve_requests"] == after["approve_requests"]
    assert before["logical_approvals"] == after["logical_approvals"]


def populated():
    d.control("reset", {})
    open_wallet("카드", ["카드 관리", "기본 카드로 지정"])
    capture("cards-manager", ["카드 관리", "기본 카드로 지정"])
    click("카드 추가")
    capture("card-entry", ["새 카드 등록", "카드 번호", "CVC"])
    for label, value in (("카드 번호", "4111111111111111"), ("유효기간 (MM / YY)", "1230"), ("CVC", "123")):
        fill_field(label, value)
    click("다음")
    capture("card-consent", ["약관 동의", "전체 동의"])
    click("전체 약관 동의")
    click("[필수] 본인확인 서비스 이용약관")
    capture("card-consent-terms", ["본인확인 서비스 이용약관", "닫기"])
    d.tap("닫기")
    back(["새 카드 등록"])
    fields = [n for n in d.nodes() if n.get("class") == "android.widget.EditText"]
    assert all(not n.get("text") for n in fields), "Card secrets survived leaving consent"
    capture("card-entry-cleared", ["새 카드 등록", "카드 번호"])

    open_wallet("배송지", ["배송지", "테스트 사용자"])
    capture("addresses", ["배송지", "테스트 사용자", "수정"])
    click("수정")
    capture("address-editor", ["배송지 수정", "수령인", "주소 검색"])
    scroll_to("저장")
    capture("address-editor-actions", ["저장", "배송지 삭제", "기본 배송지로 지정"])

    open_wallet("상품", ["상품 탐색", "1개 상품"])
    capture("catalog", ["상품 탐색", "1개 상품", "에브리데이 토트백"])
    click("검색 필터")
    capture("catalog-filters", ["필터 접기", "판매처", "최저 가격 (원)"])
    click("필터 적용")
    click("상세 보기")
    capture("product-detail", ["상품 상세", "에브리데이 토트백", "42,800원"])
    scroll_to("상품 정보")
    capture("product-options", ["네이비", "구매 가능", "상품 정보"])
    click("상품 정보")
    scroll_to("소재")
    capture("product-information", ["상품 정보 접기", "소재", "코튼 100%"])

    open_wallet("장바구니", ["장바구니", "에브리데이 토트백"])
    capture("cart", ["장바구니", "1개", "42,800원"])
    click("에브리데이 토트백 수량 늘리기")
    d.wait_for(["2개", "85,600원"])
    capture("cart-quantity", ["2개", "85,600원"])
    click("에브리데이 토트백 수량 줄이기")
    d.wait_for(["1개", "42,800원"])
    scroll_to("할인과 배송비는 아직 확정되지 않았어요.")
    capture("cart-estimate-explanation", ["예상 금액", "예상 합계", "할인과 배송비는 아직 확정되지 않았어요."])

    home()
    click("기록")
    d.wait_for(["승인 기록", "주문·배송"])
    click("주문·배송")
    capture("orders", ["주문·배송", "에브리데이 토트백", "주문 확정"])
    click("에브리데이 토트백")
    capture("order-detail", ["주문 상세", "주문 번호", "42,800원"])
    scroll_to("배송 정보")
    capture("order-detail-delivery", ["배송 정보", "결제 정보", "테스트 카드 •••• 4242"])

    home()
    click("내 정보 및 설정")
    capture("settings", ["설정", "테스트 사용자", "PIN 변경"])
    click("PIN 변경")
    capture("pin-change-form", ["현재 PIN", "1/3 단계"])
    d.enter_pin("123456")
    click("다음")
    d.wait_for(["새 PIN", "2/3 단계"])
    assert d.pin_fingerprint()["character_count"] == 0
    d.enter_pin("654321")
    click("다음")
    d.wait_for(["새 PIN 확인", "3/3 단계"])
    assert d.pin_fingerprint()["character_count"] == 0
    d.enter_pin("654320")
    scroll_to("새 PIN이 일치하지 않아요.")
    capture("pin-change-validation", ["새 PIN이 일치하지 않아요.", "PIN 변경"])
    back(["설정"])
    click("연결된 AI 서비스")
    capture("connections", ["AI 서비스", "로컬 쇼핑 도우미", "연결 해제"])
    click("연결 정보")
    capture("connection-permissions", ["연결 정보 접기", "허용된 권한", "장바구니 조회 · 장바구니 추가·변경·삭제"])
    click("연결 해제")
    capture("connection-disconnect-confirmation", ["로컬 쇼핑 도우미 연결 해제", "취소", "해제"])
    d.tap("취소")
    back(["설정"])
    click("서버 설정")
    capture("server-form", ["서비스 서버", "결제 서버", "카드 등록 서버", "서버를 변경하면 로그아웃돼요."])
    home()
    stats = d.control("stats")
    assert stats["approve_requests"] == 0 and stats["logical_approvals"] == 0
    assert stats["requests"].get("POST /api/pg/v1/payment-methods/tokenize", 0) == 0
    assert stats["requests"].get("DELETE /api/core/v1/users/me/agent-connections/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 0) == 0


def empty():
    d.control("config", {"empty": True, "mode": "approved"})
    try:
        for label, heading, message, name in [
            ("카드", "카드 관리", "등록된 카드가 없어요.", "cards-empty"),
            ("배송지", "배송지", "등록된 배송지가 없어요", "addresses-empty"),
            ("상품", "상품 탐색", "검색 결과가 없어요", "catalog-empty"),
            ("장바구니", "장바구니", "장바구니가 비어 있어요", "cart-empty"),
        ]:
            open_wallet(label, [heading, message])
            capture(name, [heading, message])
        home()
        click("기록")
        click("주문·배송")
        capture("orders-empty", ["주문·배송", "아직 주문 기록이 없어요"])
        home()
        click("내 정보 및 설정")
        click("연결된 AI 서비스")
        capture("connections-empty", ["AI 서비스", "연결된 AI 서비스가 없어요"])
    finally:
        d.control("config", {"empty": False})
    home()


def errors():
    for label, heading, error, name, recovered in [
        ("상품", "상품 탐색", "검색 결과를 불러오지 못했어요.", "catalog-offline", "1개 상품"),
        ("장바구니", "장바구니", "장바구니를 불러오지 못했어요.", "cart-offline", "에브리데이 토트백"),
    ]:
        home()
        try:
            d.control("config", {"mode": "offline"})
            click(label)
            d.wait_for([NETWORK_ERROR, error], timeout=40)
            capture(name, [heading, error, "다시 불러오기"])
        finally:
            d.control("config", {"mode": "approved"})
        click("다시 불러오기")
        d.wait_for([recovered], timeout=25, absent=[error])
        capture(name + "-recovered", [heading, recovered], absent=[error])
    home()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("group", choices=["receipt_search", "populated", "empty", "errors"])
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    started = time.time()
    failure = None
    try:
        globals()[args.group]()
    except BaseException as error:
        failure = str(error)
        raise
    finally:
        (OUT / (args.group + "-results.json")).write_text(json.dumps(dict(
            group=args.group, started_at_unix=started, completed=failure is None,
            failure=failure, captures=RESULTS, fixture=d.control("stats")), ensure_ascii=False, indent=2))
