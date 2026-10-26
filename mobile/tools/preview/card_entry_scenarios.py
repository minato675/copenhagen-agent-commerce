#!/usr/bin/env python3
"""Card entry checks on emulator-5554; only the isolated loopback preview can be used."""
import hashlib
import json
import threading
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer
from pathlib import Path

import device as d
import fixture_server as fixture
import secondary_sweep as s

OUT = Path("docs/redesign/evidence/card-entry")
NUMBER = "카드 번호"
EXPIRY = "유효기간 (MM / YY)"
CVC = "CVC"
CHECKS = []
CONTRACT = []


class Handler(fixture.Handler):
    def route(self, method, path, query, body):
        if method == "POST" and path == "/api/pg/v1/payment-methods/tokenize":
            valid = body == dict(cardNumber="4242424242424242", expiryMonth=12, expiryYear=2030, cvc="123")
            CONTRACT.append(dict(endpoint="tokenize", payload_matches=valid))
            if not valid:
                return dict(code="INVALID_CARD_DATA"), 422
        if method == "POST" and path == "/api/pay/v1/payment-methods/cards":
            valid = set(body) == {"tokenizationResult"} and body["tokenizationResult"]["expiryYear"] == 2030
            CONTRACT.append(dict(endpoint="register", token_only=valid))
            if not valid:
                return dict(code="INVALID_CARD_DATA"), 422
        return super().route(method, path, query, body)


def record(name):
    CHECKS.append(dict(case=name, passed=True))
    print(name, flush=True)


def field(label, root=None):
    root = root if root is not None else d.tree()
    matches = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"
               and label in d.visible_labels(n)]
    assert len(matches) == 1, f"Expected one editable field for {label}"
    return matches[0]


def tap_field(label):
    # Keep the same IME open while switching fields, as an actual user would.
    # Read back focus before injecting keys; stale bounds during IME/inset changes
    # must not silently type into the previous field.
    for _ in range(3):
        s.scroll_to(label, direction="up" if label == NUMBER else "down")
        node = field(label)
        if node.get("focused") == "true" and d.keyboard_visible():
            return
        x1, y1, x2, y2 = s.bounds(node)
        d.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        if field(label).get("focused") == "true":
            return
    raise AssertionError("Could not focus " + label)


def replace(label, value):
    tap_field(label)
    d.adb("shell", "input", "keycombination", "113", "29")  # Ctrl+A, supported by native field.
    # Wait for the native selection event before the next injected text event.
    # Back-to-back adb processes can otherwise race the emulator's Compose frame.
    assert field(label).get("focused") == "true"
    d.adb("shell", "input", "text", value)


def next_button():
    d.hide_keyboard()
    scroll_form_to("다음")
    root = d.tree()
    labels = [n for n in root.iter("node") if n.get("text") == "다음"]
    assert labels and labels[-1].get("enabled") == "true"
    d.tap("다음")


def scroll_form_to(label):
    for _ in range(7):
        root = d.tree()
        if label in d.visible_labels(root):
            return
        areas = [n for n in root.iter("node") if n.get("package") == d.PACKAGE and n.get("scrollable") == "true"]
        assert len(areas) == 1
        x1, y1, x2, y2 = s.bounds(areas[0])
        # Use the actual ScrollView viewport; full-window swipes hit the IME at 200% text.
        d.adb("shell", "input", "swipe", str((x1+x2)//2), str(int(y1+(y2-y1)*.8)),
              str((x1+x2)//2), str(int(y1+(y2-y1)*.3)), "300")
    raise AssertionError("Form label not reachable: " + label)


def open_entry():
    s.home(); s.click("카드"); d.wait_for(["카드 관리"])
    s.click("카드 추가"); d.wait_for(["새 카드 등록", EXPIRY])


def no_card_requests():
    with fixture.STATE.lock:
        for path in ("/api/pg/v1/payment-methods/tokenize", "/api/pay/v1/payment-methods/cards"):
            assert fixture.STATE.requests["POST " + path] == 0


def background_and_resume():
    d.adb("shell", "input", "keyevent", "3")
    # A same-frame HOME/am-start pair can cancel Android's pending onStop.
    # Wait until the launcher is actually presented before testing a return.
    d.adb("shell", "uiautomator", "dump", "/sdcard/preview-background.xml")
    root = ET.fromstring(d.adb("shell", "cat", "/sdcard/preview-background.xml"))
    assert not any(n.get("package") == d.PACKAGE for n in root.iter("node"))
    d.adb("shell", "rm", "/sdcard/preview-background.xml")
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    d.wait_for(["새 카드 등록"])
    assert all(not field(label).get("text") for label in (NUMBER, EXPIRY, CVC))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    reverse = d.adb("reverse", "--list").decode().splitlines()
    old_target = next((line.split()[-1] for line in reverse if " tcp:8787 " in line), None)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        d.adb("reverse", "tcp:8787", f"tcp:{server.server_port}")
        d.adb("shell", "am", "start", "-n", d.ACTIVITY)
        d.wait_for(["나의 지갑"])
        open_entry()
        root = d.tree()
        assert len([n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]) == 3
        assert not any("확인해 주세요" in label or "입력해 주세요" in label for label in d.visible_labels(root))
        next_button()
        d.wait_for(["카드 번호를 입력해 주세요.", "유효기간을 입력해 주세요."])
        assert field(NUMBER).get("focused") == "true"
        no_card_requests()
        d.hide_keyboard()
        d.capture(str(OUT / "empty-validation.png"), ["새 카드 등록", "카드 번호를 입력해 주세요.", "다음"])
        record("empty_next_is_enabled_and_shows_inline_errors_without_request")

        tap_field(NUMBER)
        for group, display in [("4242", "4242"), ("4242", "4242 4242"),
                               ("4242", "4242 4242 ••••"), ("4242", "4242 4242 •••• 4242")]:
            d.adb("shell", "input", "text", group)
            node = field(NUMBER)
            assert node.get("text") == display, node.get("text")
            assert node.get("focused") == "true" and node.get("password") == "true"
        record("four_groups_entered_with_one_focus_and_only_third_group_masked")
        d.capture(str(OUT / "number-keyboard.png"), [NUMBER, "4242 4242 •••• 4242"])
        for _ in range(9):
            d.adb("shell", "input", "keyevent", "67")
        assert field(NUMBER).get("text") == "4242 424"
        d.adb("shell", "input", "text", "242424242")
        assert field(NUMBER).get("text") == "4242 4242 •••• 4242"
        record("backspace_crosses_masked_and_group_boundaries_without_another_tap")

        replace(EXPIRY, "1330"); replace(CVC, "12")
        next_button()
        d.wait_for(["유효기간을 확인해 주세요."])
        assert field(EXPIRY).get("focused") == "true"
        no_card_requests()
        d.hide_keyboard()
        d.capture(str(OUT / "invalid-validation.png"), ["유효기간을 확인해 주세요.", "CVC를 3~4자리로 입력해 주세요.", "4242 4242 •••• 4242"])
        record("invalid_month_and_cvc_keep_number_and_focus_first_invalid_field")

        replace(EXPIRY, "0120"); next_button()
        d.wait_for(["유효기간이 지난 카드예요."])
        no_card_requests()
        record("expired_card_explained_on_next_without_api_call")

        replace(EXPIRY, "1230"); replace(CVC, "123")
        d.hide_keyboard()
        d.capture(str(OUT / "filled.png"), ["새 카드 등록", "4242 4242 •••• 4242", "12 / 30", "다음"])
        next_button(); d.wait_for(["약관 동의", "전체 동의"])
        no_card_requests()
        record("corrected_mmyy_reaches_existing_consent_without_early_tokenization")
        s.click("전체 약관 동의"); s.click("동의하고 등록")
        d.wait_for(["카드 등록 완료", "계속"])
        assert CONTRACT == [dict(endpoint="tokenize", payload_matches=True), dict(endpoint="register", token_only=True)]
        with fixture.STATE.lock:
            assert fixture.STATE.requests["POST /api/pg/v1/payment-methods/tokenize"] == 1
            assert fixture.STATE.requests["POST /api/pay/v1/payment-methods/cards"] == 1
            assert fixture.STATE.approve_requests == 0
        record("fixture_registration_uses_original_number_month_and_four_digit_year_once")

        s.click("계속"); open_entry()
        replace(NUMBER, "4111-1111%s1111%s1111")
        assert field(NUMBER).get("text") == "4111 1111 •••• 1111"
        record("formatted_number_input_is_normalized_before_length_limit")
        replace(EXPIRY, "1230"); replace(CVC, "123")
        background_and_resume()
        record("background_return_clears_card_secrets_and_validation_state")
        s.home()
    finally:
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        if old_target:
            d.adb("reverse", "tcp:8787", old_target)
        else:
            d.adb("reverse", "--remove", "tcp:8787")
        server.shutdown(); server.server_close()
        (OUT / "native-verification.json").write_text(json.dumps(dict(
            package=d.PACKAGE, checks=CHECKS, contract=CONTRACT,
            preview_apk_sha256=hashlib.sha256(Path("app/build/outputs/apk/preview/app-preview.apk").read_bytes()).hexdigest(),
            real_financial_requests=0), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
