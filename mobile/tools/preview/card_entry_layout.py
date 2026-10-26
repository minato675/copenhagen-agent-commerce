#!/usr/bin/env python3
"""Focused card-input/IME and narrow/large-text checks; restores all display settings."""
import json
import hashlib
from pathlib import Path
import re

import card_entry_scenarios as c
import device as d
import secondary_sweep as s


def start():
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    d.wait_for(["나의 지갑"])
    c.open_entry()


def main():
    size = d.adb("shell", "wm", "size").decode()
    old_size = re.search(r"Override size: (\d+x\d+)", size)
    font = d.adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    reduced = d.adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
    before = d.control("stats")
    results = []
    try:
        start()
        c.tap_field(c.NUMBER)
        d.adb("shell", "input", "text", "4242424242424242")
        d.adb("shell", "input", "keyevent", "66")
        assert c.field(c.EXPIRY).get("focused") == "true"
        d.adb("shell", "input", "text", "1230")
        d.adb("shell", "input", "keyevent", "66")
        assert c.field(c.CVC).get("focused") == "true"
        d.adb("shell", "input", "text", "12")
        d.adb("shell", "input", "keyevent", "66")
        d.wait_for(["CVC를 3~4자리로 입력해 주세요."])
        assert c.field(c.CVC).get("focused") == "true"
        results.append(dict(case="keyboard_next_next_done_validates_without_touching_other_fields", passed=True))

        # Seed clipboard using ordinary (non-secret) expiry, then try to copy the masked PAN.
        c.replace(c.EXPIRY, "1230")
        d.adb("shell", "input", "keycombination", "113", "29")
        c.field(c.EXPIRY)
        d.adb("shell", "input", "keycombination", "113", "31")  # Ctrl+C
        c.tap_field(c.NUMBER)
        d.adb("shell", "input", "keycombination", "113", "29")
        c.field(c.NUMBER)
        d.adb("shell", "input", "keycombination", "113", "31")
        c.tap_field(c.EXPIRY)
        d.adb("shell", "input", "keycombination", "113", "29")
        c.field(c.EXPIRY)
        d.adb("shell", "input", "keyevent", "67")
        assert not c.field(c.EXPIRY).get("text")
        d.adb("shell", "input", "keycombination", "113", "50")  # Ctrl+V
        assert c.field(c.EXPIRY).get("text") == "12 / 30", "Masked PAN changed system clipboard"
        results.append(dict(case="masked_pan_copy_cannot_replace_clipboard_and_paste_still_works", passed=True))

        c.background_and_resume()
        results.append(dict(case="background_return_clears_card_secrets_and_validation_state", passed=True))
        native = c.OUT / "native-verification.json"
        report = json.loads(native.read_text())
        assert report["preview_apk_sha256"] == hashlib.sha256(Path("app/build/outputs/apk/preview/app-preview.apk").read_bytes()).hexdigest()
        if not any(item["case"] == results[-1]["case"] for item in report["checks"]):
            report["checks"].append(results[-1])
            native.write_text(json.dumps(report, ensure_ascii=False, indent=2))

        d.adb("shell", "wm", "size", "840x1995")  # 320 x 760 dp at the existing 420 dpi.
        d.adb("shell", "settings", "put", "system", "font_scale", "1.0")
        start()
        c.replace(c.NUMBER, "4242424242424242")
        c.replace(c.EXPIRY, "1230")
        c.replace(c.CVC, "123")
        d.hide_keyboard()
        d.capture(str(c.OUT / "narrow-320.png"), ["새 카드 등록", "4242 4242 •••• 4242", "12 / 30", "다음"])
        c.next_button(); d.wait_for(["약관 동의"])
        results.append(dict(case="320dp_form_keeps_all_groups_and_next_usable", passed=True))

        # Keep the tested form open across the font configuration change.
        # Large wallet content can be skipped by the generic driver's long swipes.
        s.back(["새 카드 등록"])
        d.adb("shell", "settings", "put", "system", "font_scale", "2.0")
        d.adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
        d.wait_for(["새 카드 등록"])
        c.next_button()
        d.hide_keyboard()
        s.scroll_to("카드 번호를 입력해 주세요.", direction="up")
        d.capture(str(c.OUT / "font200-errors.png"), ["카드 번호를 입력해 주세요."])
        c.scroll_form_to("다음")
        d.capture(str(c.OUT / "font200-next.png"), ["CVC를 3~4자리로 입력해 주세요.", "다음"])
        results.append(dict(case="320dp_font200_errors_wrap_and_next_remains_reachable_with_reduced_motion", passed=True))
        after = d.control("stats")
        for key in ("POST /api/pg/v1/payment-methods/tokenize", "POST /api/pay/v1/payment-methods/cards"):
            assert after["requests"].get(key, 0) == before["requests"].get(key, 0)
    finally:
        for namespace, key, value in [("system", "font_scale", font), ("global", "animator_duration_scale", reduced)]:
            if value == "null":
                d.adb("shell", "settings", "delete", namespace, key)
            else:
                d.adb("shell", "settings", "put", namespace, key, value)
        d.adb("shell", "wm", "size", old_size[1] if old_size else "reset")
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        (c.OUT / "layout-verification.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
