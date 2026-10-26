#!/usr/bin/env python3
"""Native read-retry checks using a temporary loopback fixture and the preview APK only."""
import copy
import hashlib
import json
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path

import device as d
import fixture_server as fixture
import secondary_sweep as s

OUT = Path("docs/redesign/evidence/request-retry")
ERROR = "요청을 처리하지 못했어요. 다시 시도해 주세요."


class Faults:
    def __init__(self):
        self.lock = threading.Lock()
        self.arm("", [])

    def arm(self, path, outcomes):
        with self.lock:
            self.path, self.remaining, self.events = path, list(outcomes), []

    def record(self, path):
        with self.lock:
            if path != self.path:
                return None
            outcome = self.remaining.pop(0) if self.remaining else 200
            self.events.append(outcome)
            return outcome

    def observed(self):
        with self.lock:
            return list(self.events)


FAULTS = Faults()


class Handler(fixture.Handler):
    def handle_request(self):
        outcome = FAULTS.record(self.path) if self.command == "GET" else None
        if outcome == "disconnect":
            return self.disconnect()
        if outcome == 503:
            return self.reply({"code": "TEMPORARILY_UNAVAILABLE"}, 503)
        return super().handle_request()

    do_GET = handle_request


def wait(expected, absent=()):
    # Emulator UIAutomator can briefly return no root while an activity starts.
    for attempt in range(4):
        try:
            return d.wait_for(expected, timeout=30, absent=absent)
        except RuntimeError:
            if attempt == 3:
                raise


def wait_outcomes(expected):
    deadline = time.monotonic() + 20
    while FAULTS.observed() != expected and time.monotonic() < deadline:
        d.tree()
    assert FAULTS.observed() == expected, FAULTS.observed()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    original = d.adb("reverse", "--list").decode().splitlines()
    old_target = next((line.split()[-1] for line in original if " tcp:8787 " in line), None)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        # Close preview-only pooled sockets before redirecting its loopback port.
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        d.adb("reverse", "tcp:8787", f"tcp:{server.server_port}")
        d.adb("shell", "am", "start", "-n", d.ACTIVITY)
        wait(["나의 지갑"])

        s.home()
        FAULTS.arm("/api/core/v1/addresses", [503, 503])
        s.click("배송지")
        wait_outcomes([503, 503, 200])
        wait(["배송지", "배송지 추가", "테스트 사용자"], absent=[ERROR])
        assert FAULTS.observed() == [503, 503, 200], FAULTS.observed()
        d.capture(str(OUT / "address-auto-recovered.png"), ["배송지 추가", "테스트 사용자"], absent=[ERROR])
        results.append(dict(case="address_read_recovers_without_refresh", outcomes=FAULTS.observed(), passed=True))
        print("Two failed address reads recovered automatically", flush=True)

        s.home()
        FAULTS.arm("/api/core/v1/addresses", ["disconnect"])
        s.click("배송지")
        wait_outcomes(["disconnect", 200])
        wait(["배송지 추가", "테스트 사용자"], absent=[ERROR])
        assert FAULTS.observed() == ["disconnect", 200], FAULTS.observed()
        results.append(dict(case="disconnected_read_recovers_without_refresh", outcomes=FAULTS.observed(), passed=True))
        print("A disconnected address read recovered automatically", flush=True)

        s.home()
        FAULTS.arm("/api/core/v1/addresses", [503, 503, 503])
        s.click("배송지")
        wait([ERROR, "새로고침"])
        assert FAULTS.observed() == [503, 503, 503], FAULTS.observed()
        d.capture(str(OUT / "retry-limit-error.png"), [ERROR, "새로고침"])
        assert FAULTS.observed() == [503, 503, 503], "Retries continued after the limit"
        d.tap("새로고침")
        wait_outcomes([503, 503, 503, 200])
        wait(["배송지 추가", "테스트 사용자"], absent=[ERROR])
        assert FAULTS.observed() == [503, 503, 503, 200], FAULTS.observed()
        results.append(dict(case="retry_limit_preserves_manual_recovery", outcomes=FAULTS.observed(), passed=True))
        print("Persistent failure stopped at three attempts; explicit refresh recovered", flush=True)

        with fixture.STATE.lock:
            requests = copy.deepcopy(dict(fixture.STATE.requests))
        assert not any(key.startswith(("POST ", "PUT ", "PATCH ", "DELETE ")) for key in requests), requests
        s.home()
        result = dict(package=d.PACKAGE, fixture_only=True, cases=results, mutations=0,
                      preview_apk_sha256=hashlib.sha256(Path("app/build/outputs/apk/preview/app-preview.apk").read_bytes()).hexdigest())
        (OUT / "native-verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        if old_target:
            d.adb("reverse", "tcp:8787", old_target)
        else:
            d.adb("reverse", "--remove", "tcp:8787")
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
