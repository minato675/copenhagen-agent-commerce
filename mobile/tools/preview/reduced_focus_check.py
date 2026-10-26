#!/usr/bin/env python3
"""Preview-only reduced-motion and native Tab checks, independent of software IME.

Own emulator-5554, sign into the isolated preview app, and run the loopback fixture
first. Each invocation creates a new evidence directory; existing evidence is
never overwritten. This submits one synthetic completed fixture approval.
"""
import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path

import device as d
import runtime_scenarios as scenarios


def installed_hash():
    paths = d.adb("shell", "pm", "path", d.PACKAGE).decode().splitlines()
    base = next(line.removeprefix("package:") for line in paths if line.endswith("/base.apk"))
    return d.adb("shell", "sha256sum", base).decode().split()[0]


def restore_setting(namespace, name, original):
    if original == "null":
        d.adb("shell", "settings", "delete", namespace, name)
    else:
        d.adb("shell", "settings", "put", namespace, name, original)


def run(output):
    output.mkdir(parents=True, exist_ok=False)
    report = dict(complete=False, package=d.PACKAGE, serial="emulator-5554", started_at_unix=time.time(),
                  ime=dict(result="not tested by this runner",
                           reason="This check does not establish software-keyboard visibility or occlusion. The earlier emulator probe reported its IME service shown while the native IME inset had zero height; a focused PIN screenshot is not evidence of an open software keyboard.",
                           historical_evidence="docs/redesign/evidence/accessibility/checks.json"))
    original = None
    restored = False
    failure = None
    try:
        report["installed_apk_sha256"] = installed_hash()
        original = d.adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
        report["original_animator_duration_scale"] = original
        d.adb("reverse", "tcp:8787", "tcp:8787")
        try:
            d.adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
            scale = d.adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
            assert float(scale) == 0, scale
            d.adb("shell", "am", "force-stop", d.PACKAGE)
            scenarios.prepare("completed", 0)
            scenarios.confirm(rapid=4)
            d.wait_for(["결제가 완료되었어요", "기록에서 보기"])
            stats = d.control("stats")
            assert stats["approve_requests"] == 1 and stats["logical_approvals"] == 1, stats
            assert stats["authoritative_status"] == "COMPLETED", stats
            artifact = "reduced-motion-completed.png"
            d.capture(str(output / artifact), ["결제가 완료되었어요", "42,800원"])
            report["reduced_motion"] = dict(result="pass", animator_duration_scale=scale,
                                            fixture_latency_ms=0, rapid_taps=4, fixture=stats, artifact=artifact,
                                            boundary="Verifies a correct reachable result and deduplication with animator scale zero; still images do not certify absence of every animated frame.")
        finally:
            restore_setting("global", "animator_duration_scale", original)
            actual = d.adb("shell", "settings", "get", "global", "animator_duration_scale").decode().strip()
            report["restored_animator_duration_scale"] = actual
            assert actual == original, (original, actual)
            restored = True

        # A fresh review and no text input keep this independent of emulator IME behavior.
        d.control("reset", {})
        d.adb("shell", "am", "force-stop", d.PACKAGE)
        scenarios.open_request()
        observations = []
        report["keyboard_focus"] = dict(method="ADB native KEYCODE_TAB ×5; no physical keyboard", observations=observations)
        for index in range(5):
            d.adb("shell", "input", "keyevent", "61")
            root = d.tree()
            focused = []
            for node in root.iter("node"):
                if node.get("package") == d.PACKAGE and node.get("focused") == "true":
                    value = dict(node.attrib)
                    value["descendant_labels"] = sorted({label for child in node.iter("node")
                                                         for label in (child.get("text"), child.get("content-desc")) if label})
                    focused.append(value)
            observations.append(dict(tab=index+1, nodes=focused))
            assert focused, f"No focused preview node after Tab {index+1}"
        stats = d.control("stats")
        assert stats["approve_requests"] == 0 and stats["logical_approvals"] == 0, stats
        assert not {route: count for route, count in stats["requests"].items()
                    if count and not route.startswith("GET ")}, stats
        artifact = "keyboard-focus.png"
        d.capture(str(output / artifact), ["승인 요청"], absent=["PIN 6자리"])
        report["keyboard_focus"].update(result="pass", fixture=stats, artifact=artifact,
                                         boundary="Records native focus after five Tab inputs, not complete traversal, TalkBack order, Escape behavior or physical-keyboard usability.")
        report["complete"] = True
    except BaseException as error:
        failure = error
        report["error"] = f"{type(error).__name__}: {error}"
    finally:
        if original is not None and not restored:
            try:
                restore_setting("global", "animator_duration_scale", original)
            except Exception as restore_error:
                report["restore_error"] = str(restore_error)
                report["complete"] = False
                if failure is None:
                    failure = restore_error
        (output / "reduced-focus.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    if failure is not None:
        raise failure
    print(f"Reduced motion and five native Tab observations passed; evidence: {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + f"-{time.time_ns()}"
    parser.add_argument("--output", type=Path,
                        default=Path("docs/redesign/evidence/accessibility-reruns") / run_id,
                        help="New directory only; refuses an existing directory")
    run(parser.parse_args().output)
