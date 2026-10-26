#!/usr/bin/env python3
"""Native card appearance checks using a private, loopback-only fixture instance.

Owns emulator-5554. No real account, card, approval, or backend is changed.
"""
import argparse
import hashlib
import json
import re
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path

from PIL import Image, ImageChops, ImageStat
import device as d
import fixture_server as fixture
import secondary_sweep as s
import default_selection_scenarios as motion
from loading_motion_check import wait_enabled

OUT = Path("docs/redesign/evidence/card-design")
CHECKS = []


def record(name, **values):
    CHECKS.append(dict(name=name, passed=True, **values))
    (OUT / "checks-live.json").write_text(json.dumps(CHECKS, ensure_ascii=False, indent=2))
    print("PASS", name, flush=True)


def launch():
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    deadline = time.monotonic() + 40
    while True:
        try:
            d.wait_for(["나의 지갑"], timeout=max(1, deadline - time.monotonic()))
            break
        except RuntimeError as error:
            if str(error) != "Refusing UI automation: isolated preview is not visible" or time.monotonic() > deadline:
                raise
    wait_enabled("내 정보 및 설정")
    s.click("카드")
    d.wait_for(["카드 관리"])
    wait_enabled("새로고침")


def capture(name, expected):
    d.capture(str(OUT / (name + ".png")), expected)
    return Image.open(OUT / (name + ".png")).convert("RGB")


def face(image, root, number):
    """Measure the actual painted card independently of its expected aspect ratio."""
    row = motion.item(root, number)
    group = motion.bounds(row)
    label = next(n for n in row.iter("node") if n.get("text") == number)
    text = motion.bounds(label)
    y = text[1] - 4
    runs, begin = [], None
    for x in range(image.width):
        dark = max(image.getpixel((x, y))) < 210
        if dark and begin is None:
            begin = x
        elif not dark and begin is not None:
            runs.append((begin, x)); begin = None
    x1, x2 = max(runs, key=lambda r: r[1] - r[0])
    assert x2 - x1 > image.width * .35
    bottom = text[3] + 4
    while bottom < image.height and max(image.getpixel(((x1+x2)//2, bottom))) < 210:
        bottom += 1
    bounds = (x1, group[1], x2, bottom)
    ratio = (x2-x1)/(bottom-group[1])
    assert abs(ratio - 85.60/53.98) < .015, (bounds, ratio)
    return image.crop(bounds), ratio


def art_sample(card):
    # Mid-face contains material only: no brand, default badge, number or expiry.
    return card.crop((int(card.width*.1), int(card.height*.34), int(card.width*.9), int(card.height*.58)))


def same_art(before, after):
    difference = ImageChops.difference(art_sample(before), art_sample(after))
    # Native gradient dithering varies by the final screen coordinate (observed ±2/255).
    assert max(high for low, high in difference.getextrema()) <= 3
    assert max(ImageStat.Stat(difference).mean) < 1


def continuity():
    fixture.STATE.reset(); launch()
    labels = ["카드 관리", "••••  4242", "••••  1881"]
    before = capture("cards", labels)
    root = d.tree()
    first, ratio = face(before, root, labels[1])
    second, _ = face(before, root, labels[2])
    first.save(OUT / "card-first.png"); second.save(OUT / "card-second.png")
    delta = sum(ImageStat.Stat(ImageChops.difference(art_sample(first), art_sample(second))).mean) / 3
    assert delta > 12, delta
    record("physical ratio and visibly distinct cards", ratio=ratio, mean_material_difference=delta)
    motion.OUT = OUT
    with motion.recording("default-change"):
        motion.tap(motion.target(root, motion.CARD, labels[2]))
        root = motion.settled(motion.CARD, labels[2])
        after = capture("cards-reordered", labels)
    for number, prior in ((labels[1], first), (labels[2], second)):
        current, _ = face(after, root, number)
        same_art(prior, current)
    assert fixture.STATE.requests["PATCH /api/pay/v1/payment-methods/cards/44444444-4444-4444-8444-444444444444/default"] == 1
    record("same artwork after default promotion", one_patch=True)
    launch()
    current, _ = face(capture("after-restart", labels), d.tree(), labels[2])
    same_art(second, current)
    record("same artwork after process restart")
    d.tap("지갑"); s.scroll_to("••••  1881")
    capture("wallet", ["테스트 카드", "••••  1881"])
    d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", "11111111-1111-4111-8111-111111111111")
    s.scroll_to("변경"); d.tap("변경")
    s.scroll_to("테스트 카드 •••• 1881")
    capture("payment-picker", ["테스트 카드 •••• 4242", "테스트 카드 •••• 1881"])
    d.tap("테스트 카드 •••• 1881")
    d.wait_for(["테스트 카드 •••• 1881"], absent=["테스트 카드 •••• 4242"])
    capture("payment-selected", ["테스트 카드 •••• 1881"])
    assert fixture.STATE.selected_card.endswith("444444444444")
    assert fixture.STATE.approve_requests == 0
    record("payment thumbnails preserve card identity and selection without approval")


def variants():
    fixture.STATE.reset(); launch()
    cards = []
    for palette in range(8):
        for i in range(10000):
            identity = f"appearance-fixture-{palette}-{i}"
            digest = hashlib.sha256(("copenhagen-card-v1:"+identity).encode()).digest()
            if digest[0] % 8 == palette and digest[1] % 6 == palette % 6:
                break
        value = dict(fixture.STATE.cards[0], paymentMethodId=identity, last4=str(1000+palette))
        with fixture.STATE.lock:
            fixture.STATE.cards = [value]
        d.tap("새로고침")
        number = "••••  " + value["last4"]
        image = capture(f"variant-{palette}", [number])
        card, _ = face(image, d.tree(), number)
        card.save(OUT / f"variant-{palette}-card.png")
        cards.append(card)
    thumb_width = 440
    thumb_height = round(thumb_width / (85.60/53.98))
    sheet = Image.new("RGB", (thumb_width*2+72, thumb_height*4+120), "#F5F6F8")
    for i, card in enumerate(cards):
        sheet.paste(card.resize((thumb_width, thumb_height), Image.Resampling.LANCZOS),
                    (24+(i%2)*(thumb_width+24),24+(i//2)*(thumb_height+24)))
    sheet.save(OUT / "designs.png")
    record("all 8 palettes and 6 native compositions rendered", native_crops=8)


def layouts(names=None):
    fixture.STATE.reset()
    with fixture.STATE.lock:
        fixture.STATE.cards = [dict(fixture.STATE.cards[0],
            brand="코펜하겐 프리미엄 라이프스타일 멤버십 신용카드", status="REVOKED")]
    for name, size, scale in (("narrow", "840x1866", "1.0"), ("large-text", "900x2000", "2.0"), ("wide", "2100x1600", "1.0")):
        if names and name not in names:
            continue
        # Enter the target at normal settings, then exercise actual configuration
        # changes on that screen. Wallet shortcut scrolling is a separate journey.
        d.adb("shell", "wm", "size", "reset")
        d.adb("shell", "settings", "put", "system", "font_scale", "1.0")
        launch()
        d.adb("shell", "wm", "size", size)
        d.adb("shell", "settings", "put", "system", "font_scale", scale)
        d.wait_for(["카드 관리"])
        s.scroll_to("••••  4242")
        capture(name, ["••••  4242"])
        s.scroll_to("코펜하겐 프리미엄 라이프스타일 멤버십 신용카드")
        capture(name+"-details", ["코펜하겐 프리미엄 라이프스타일 멤버십 신용카드"])
        s.scroll_to("사용 중지됨 · 12 / 2030")
        capture(name+"-status", ["사용 중지됨 · 12 / 2030"])
        s.scroll_to("삭제")
        wait_enabled("삭제")
        record(name+" keeps full identity, status, and actions reachable", size_px=size, font_scale=scale)


def registration():
    fixture.STATE.reset(); launch()
    s.click("카드 추가")
    for label, value in (("카드 번호", "4242424242424242"), ("유효기간 (MM / YY)", "1230"), ("CVC", "123")):
        s.fill_field(label, value)
    s.click("다음"); d.wait_for(["약관 동의"])
    s.click("전체 약관 동의"); s.click("동의하고 등록")
    capture("registered", ["카드 등록 완료", "••••  4242", "계속"])
    assert fixture.STATE.requests["POST /api/pay/v1/payment-methods/cards"] == 1
    assert fixture.STATE.requests["POST /api/pg/v1/payment-methods/tokenize"] == 1
    assert fixture.STATE.approve_requests == 0
    record("newly registered fixture card receives its own artwork")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["continuity", "variants", "layouts", "registration", "all"], default="all", nargs="?")
    parser.add_argument("--layout", choices=["narrow", "large-text", "wide"], action="append")
    args = parser.parse_args()
    mode = args.mode
    OUT.mkdir(parents=True, exist_ok=True)
    reverse = d.adb("reverse", "--list").decode().splitlines()
    old_target = next((line.split()[-1] for line in reverse if " tcp:8787 " in line), None)
    font = d.adb("shell", "settings", "get", "system", "font_scale").decode().strip()
    size = d.adb("shell", "wm", "size").decode()
    override = re.search(r"Override size: (\d+x\d+)", size)
    server = ThreadingHTTPServer(("127.0.0.1", 0), fixture.Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    error = None
    try:
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        d.adb("reverse", "tcp:8787", f"tcp:{server.server_port}")
        for name, fn in (("continuity", continuity), ("variants", variants), ("registration", registration), ("layouts", lambda: layouts(args.layout))):
            if mode in (name, "all"):
                fn()
    except BaseException as reason:
        error = repr(reason)
        raise
    finally:
        try:
            d.adb("shell", "am", "force-stop", d.PACKAGE)
            d.adb("shell", "wm", "size", override[1] if override else "reset")
            if font == "null":
                d.adb("shell", "settings", "delete", "system", "font_scale")
            else:
                d.adb("shell", "settings", "put", "system", "font_scale", font)
            if old_target:
                d.adb("reverse", "tcp:8787", old_target)
            else:
                d.adb("reverse", "--remove", "tcp:8787")
        except BaseException as cleanup_error:
            error = error or repr(cleanup_error)
            raise
        finally:
            server.shutdown(); server.server_close()
            (OUT / (mode+".json")).write_text(json.dumps(dict(package=d.PACKAGE, complete=error is None,
                error=error, checks=CHECKS, preview_apk_sha256=hashlib.sha256(Path("app/build/outputs/apk/preview/app-preview.apk").read_bytes()).hexdigest()), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
