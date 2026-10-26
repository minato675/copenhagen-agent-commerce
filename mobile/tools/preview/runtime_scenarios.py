#!/usr/bin/env python3
"""Native fixture scenarios. Only run while this process owns emulator-5554."""
import json
import re
import time
from pathlib import Path
import device as d

OUT = Path("docs/redesign/evidence/runtime")
OUT.mkdir(parents=True, exist_ok=True)
ID = "11111111-1111-4111-8111-111111111111"
RESULTS = []


def save(name, value):
    (OUT / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2))


def open_request():
    d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", ID)
    d.wait_for(["요청 총액", "변경"], timeout=35)


def prepare(mode, latency, pin="123456"):
    d.control("reset", {})
    d.control("config", dict(mode=mode, latency_ms=latency))
    open_request()
    d.tap("42,800원 승인")
    d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
    d.enter_pin(pin)


def confirm(rapid=1):
    root = d.tree()
    nodes = [node for node in root.iter("node") if node.get("text") == "42,800원 승인"]
    if not nodes: raise RuntimeError("No amount-specific submit action")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[-1].get("bounds")))
    started = time.monotonic()
    for _ in range(rapid):
        d.adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    return started


def record(name, expected, started, capture=False, count=1):
    tree = d.wait_for([expected], timeout=40)
    labels = d.visible_labels(tree)
    stats = d.control("stats")
    assert stats["logical_approvals"] == count, stats
    result = dict(scenario=name, expected_label=expected, visible_labels=sorted(labels), fixture=stats,
                  authoritative_accept_after_input_ms=round((stats["events"][0]["at_monotonic"]-started)*1000, 1) if stats["events"] else None,
                  note="Acceptance metric is host input-command-to-server acceptance, not rendered input latency.")
    RESULTS.append(result)
    save(name, result)
    if capture: d.capture(str(OUT / (name + ".png")), [expected, "코펜하겐 스토어"])
    print(name, expected, "attempts", stats["approve_requests"], "logical", stats["logical_approvals"], flush=True)


def fast():
    for mode in ("approved", "completed"):
        for latency in (0, 80, 250, 800):
            prepare(mode, latency)
            started = confirm()
            expected = "승인을 전달했어요" if mode == "approved" else "결제가 완료되었어요"
            record(f"{mode}-{latency}ms", expected, started, capture=latency==0)


def slow():
    prepare("approved", 2000)
    started = confirm()
    record("approved-2000ms", "승인을 전달했어요", started)
    prepare("approved", 8000)
    started = confirm(rapid=4)
    processing = d.wait_for(["승인 전달 중"], timeout=12)
    save("processing-8000ms", dict(labels=sorted(d.visible_labels(processing)), fixture=d.control("stats")))
    record("approved-8000ms-rapid-taps", "승인을 전달했어요", started, capture=True)
    prepare("approved", 30000)
    started = confirm()
    d.wait_for(["승인 전달 중"], timeout=12)
    d.capture(str(OUT / "processing-30000ms.png"), ["승인 전달 중", "지갑에서 기다리기"])
    record("approved-30000ms-timeout", "처리 확인 필요", started, capture=True)
    d.tap("상태 확인")
    record("approved-30000ms-reconciled", "승인을 전달했어요", started, capture=True)


def truth():
    prepare("disconnect", 250)
    started = confirm()
    record("accepted-disconnect-unknown", "처리 확인 필요", started, capture=True)
    d.tap("상태 확인")
    record("accepted-disconnect-reconciled", "승인을 전달했어요", started)


def offline():
    prepare("approved", 250)
    d.control("config", {"mode":"offline"})
    started = confirm()
    expected = "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."
    record("offline-before-submit", expected, started, capture=True, count=0)
    assert "PIN으로 결제하기" in d.visible_labels(d.tree())
    assert d.control("stats")["approve_requests"] == 0
    d.control("config", {"mode":"approved"})


def navigation():
    prepare("approved", 8000)
    started = confirm()
    d.wait_for(["지갑에서 기다리기"], timeout=12)
    d.tap("지갑에서 기다리기")
    d.wait_for(["나의 지갑"], timeout=20)
    stats=d.control("stats")
    save("leave-during-processing", dict(fixture=stats, labels=sorted(d.visible_labels(d.tree()))))
    time.sleep(max(0, 8.5-(time.monotonic()-started)))
    d.adb("shell", "am", "start", "-n", d.ACTIVITY, "--es", "approvalId", ID)
    record("leave-return-reconciled", "승인을 전달했어요", started, capture=True)
    # Return within a route transition, then reopen. No mutation is allowed from navigation.
    d.control("reset", {})
    open_request()
    d.tap("변경")
    d.adb("shell", "input", "keyevent", "4")
    d.wait_for(["요청 총액", "변경"])
    d.tap("42,800원 승인")
    d.adb("shell", "input", "keyevent", "4")
    d.wait_for(["요청 총액", "변경"])
    stats=d.control("stats")
    assert stats["logical_approvals"] == 0
    save("interrupted-sheet-route", dict(fixture=stats, labels=sorted(d.visible_labels(d.tree()))))
    d.capture(str(OUT / "interrupted-sheet-route.png"), ["요청 총액", "변경"])


def resume():
    prepare("approved", 30000)
    started = confirm()
    d.wait_for(["닫아도 요청은 계속 처리돼요."], timeout=20)
    d.capture(str(OUT / "processing-over-8seconds.png"), ["닫아도 요청은 계속 처리돼요.", "지갑에서 기다리기"], timeout=12)
    save("process-restart-before", dict(fixture=d.control("stats")))
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    recovered = d.wait_for(["코펜하겐 스토어"], timeout=30)
    if "처리 상태 확인" in d.visible_labels(recovered):
        d.tap("처리 상태 확인")
    record("process-restart-reconciled", "승인을 전달했어요", started, capture=True)


def failure():
    prepare("approved", 0, pin="000000")
    started = confirm()
    record("invalid-pin-definitive-rejection", "PIN이 일치하지 않아요.", started, capture=True, count=0)


def pending():
    prepare("pending", 250)
    started = confirm()
    record("authoritative-pending", "처리 확인 필요", started, capture=True)
    d.tap("상태 확인")
    record("authoritative-pending-read", "처리 확인 필요", started)
    assert d.control("stats")["approve_requests"] == 1
    d.control("config", {"status":"APPROVED"})
    d.tap("상태 확인")
    record("pending-resolved-by-read", "승인을 전달했어요", started)


def changed_quote():
    prepare("approved", 0)
    d.control("config", {"amount_minor":42900})
    started = confirm()
    expected = "결제 정보가 변경되었어요. 다시 확인해 주세요."
    record("changed-quote-after-pin", expected, started, count=0)
    d.wait_for(["42,900원", "요청 총액", "변경"])
    assert d.control("stats")["approve_requests"] == 0
    d.capture(str(OUT / "changed-quote-after-pin.png"), [expected, "42,900원", "요청 총액"])
    d.control("reset", {})


def movie():
    """One labelled fixture journey; always stop our recorder and preserve failure evidence."""
    import subprocess
    existing = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", "screenrecord"], capture_output=True, text=True)
    if existing.stdout.strip():
        raise RuntimeError("Another screenrecord is active; do not interrupt another recording")
    d.control("reset", {})
    d.control("config", dict(mode="completed", latency_ms=250))
    d.adb("reverse", "tcp:8787", "tcp:8787")
    d.adb("shell", "am", "force-stop", d.PACKAGE)
    d.adb("shell", "am", "start", "-n", d.ACTIVITY)
    d.wait_for(["나의 지갑", "결제 확인"])
    apk_path = d.adb("shell", "pm", "path", d.PACKAGE).decode().strip().removeprefix("package:")
    apk_sha = d.adb("shell", "sha256sum", apk_path).decode().split()[0]
    (OUT / "gfxinfo-before-movie-reset.txt").write_bytes(d.adb("shell", "dumpsys", "gfxinfo", d.PACKAGE, "reset"))
    remote = f"/sdcard/copenhagen-motion-{time.time_ns()}.mp4"
    stderr_path = OUT / "motion-recorder.log"
    phases = []
    start = time.monotonic()
    def phase(name): phases.append(dict(name=name, seconds=round(time.monotonic()-start, 3)))
    error = None
    recorder_pid = None
    stats = None
    process = None
    with stderr_path.open("wb") as recorder_log:
        try:
            process = subprocess.Popen(["adb", "-s", "emulator-5554", "shell", "screenrecord", "--bit-rate", "5000000", "--time-limit", "90", remote], stdout=recorder_log, stderr=recorder_log)
            # Recorder readiness only; no financial request is dispatched or delayed here.
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("screenrecord exited before the journey; inspect motion-recorder.log")
                found = subprocess.run(["adb", "-s", "emulator-5554", "shell", "pidof", "screenrecord"], capture_output=True, text=True).stdout.strip()
                if found.isdigit():
                    recorder_pid = found
                    break
                time.sleep(0.05)
            if recorder_pid is None:
                raise RuntimeError("Could not identify the recorder process")
            phase("wallet-observed")
            phase("wallet-action-about-to-dispatch")
            d.tap("결제 확인")
            d.wait_for(["요청 총액", "변경"])
            phase("review-observed")
            d.tap("42,800원 승인")
            d.wait_for(["PIN으로 결제하기", "PIN 6자리"])
            phase("authorization-observed")
            d.enter_pin("123456")
            phase("authorization-action-about-to-dispatch")
            confirm()
            d.wait_for(["결제가 완료되었어요", "기록에서 보기"])
            phase("confirmed-completed-fixture-observed")
            stats = d.control("stats")
            assert stats["approve_requests"] == 1 and stats["logical_approvals"] == 1, stats
            assert stats["authoritative_status"] == "COMPLETED", stats
            d.tap("기록에서 보기")
            history_labels = ["기록", "승인 기록", "코펜하겐 스토어", "결제 완료 기록"]
            d.wait_for(history_labels, absent=["기록에서 보기"])
            phase("matching-activity-observed")
            d.capture(str(OUT / "receipt-in-history.png"), history_labels, absent=["기록에서 보기"])
            phase("history-evidence-captured")
        except BaseException as failure:
            error = failure
        finally:
            # Only the PID we created is signalled; failures cannot leave an orphan recorder.
            if process is not None and process.poll() is None:
                if recorder_pid is not None:
                    subprocess.run(["adb", "-s", "emulator-5554", "shell", "kill", "-INT", recorder_pid], capture_output=True)
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    if recorder_pid is not None:
                        subprocess.run(["adb", "-s", "emulator-5554", "shell", "kill", recorder_pid], capture_output=True)
                    process.terminate()
                    process.wait(timeout=3)
            try:
                d.adb("pull", remote, str(OUT / "wallet-approval-receipt-history.mp4"))
            except Exception as pull_failure:
                if error is None: error = pull_failure
            try:
                (OUT / "gfxinfo-movie.txt").write_bytes(d.adb("shell", "dumpsys", "gfxinfo", d.PACKAGE, "framestats"))
            except Exception as profile_failure:
                if error is None: error = profile_failure
            save("motion-recording", dict(complete=error is None, error=None if error is None else str(error),
                phases=phases, fixture=stats, installed_apk_sha256=apk_sha,
                timing_note="Phase timestamps follow native UIAutomator observations and are not animation durations or input-rendering latency. Profile includes screen recording and inspection overhead."))
    if error is not None:
        raise error


if __name__ == "__main__":
    import sys
    globals()[sys.argv[1]]()
    save("last-run", RESULTS)
