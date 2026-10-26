#!/usr/bin/env python3
"""Focused final-APK checks: constrained layout, text scaling, reduced motion, late return."""
import json
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
import shipping_notification_scenarios as s
import device as d
import notification_scenarios as n

OUT=s.OUT


def setting(namespace,key): return d.adb("shell","settings","get",namespace,key).decode().strip()


def restore_setting(namespace,key,value):
    d.adb("shell","settings","delete",namespace,key) if value=="null" else d.adb("shell","settings","put",namespace,key,value)


def main():
    original=s.preferences()
    apk_path=d.adb("shell","pm","path",d.PACKAGE).decode().strip().removeprefix("package:")
    apk_sha=d.adb("shell","sha256sum",apk_path).decode().split()[0]
    mapping=d.adb("reverse","--list").decode().splitlines()
    old=next((line.split()[-1] for line in mapping if " tcp:8787 " in line),None)
    size=d.adb("shell","wm","size").decode()
    old_size=next((line.split(": ")[1] for line in size.splitlines() if line.startswith("Override size:")),"reset")
    font=setting("system","font_scale")
    motion=setting("global","animator_duration_scale")
    server=ThreadingHTTPServer(("127.0.0.1",0),s.Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    cases=[]
    try:
        d.adb("reverse","tcp:8787",f"tcp:{server.server_port}")
        for name,width,scale,reduced in [("narrow-320dp","840x1800","1.0","1"),("font-200","1080x2400","2.0","1"),("reduced-motion","1080x2400","1.0","0")]:
            d.adb("shell","wm","size",width)
            d.adb("shell","settings","put","system","font_scale",scale)
            d.adb("shell","settings","put","global","animator_duration_scale",reduced)
            origin,host=s.begin(original,expected=["배송지 선택"])
            n.click("회사 수령인"); n.click("이 배송지로 선택")
            n.scroll_to("배송지 선택 완료",direction="up")
            d.wait_for(["배송지 선택 완료"])
            s.shot(name)
            s.explicit_return(origin,host)
            assert len(s.S.posts)==1 and s.f.STATE.approve_requests==0
            cases.append(dict(scenario=name,passed=True))
            print(name,"PASS",flush=True)
        d.adb("shell","wm","size",old_size)
        restore_setting("system","font_scale",font)
        restore_setting("global","animator_duration_scale",motion)
        origin,host=s.begin(original,latency=20)
        n.click("회사 수령인"); n.click("이 배송지로 선택")
        assert s.S.accepted.wait(3)
        assert s.S.status=="PENDING_ADDRESS"
        s.explicit_return(origin,host)
        assert s.S.status=="PENDING_ADDRESS","Fixture already replied before the return action"
        deadline=time.monotonic()+30
        while s.S.status=="PENDING_ADDRESS":
            assert time.monotonic()<deadline
            assert n.same_task(n.top(),origin),"Late result brought its task back"
            time.sleep(.25)
        assert n.same_task(n.top(),origin)
        cases.append(dict(scenario="return during pending write; late result keeps original context",passed=True))
        print("late return PASS",flush=True)
    finally:
        s.restore(original)
        d.adb("shell","wm","size",old_size)
        restore_setting("system","font_scale",font)
        restore_setting("global","animator_duration_scale",motion)
        if old: d.adb("reverse","tcp:8787",old)
        else: d.adb("reverse","--remove","tcp:8787")
        server.shutdown();server.server_close()
        settings_restored=(d.adb("shell","wm","size").decode()==size and
            setting("system","font_scale")==font and setting("global","animator_duration_scale")==motion)
        (OUT/"layout-verification.json").write_text(json.dumps(dict(cases=cases,passed=len(cases),complete=len(cases)==4,installed_apk_sha256=apk_sha,
            preview_preferences_restored=s.preferences()==original,device_settings_restored=settings_restored,
            original_size=size,original_font=font,original_motion=motion),ensure_ascii=False,indent=2))


if __name__=="__main__":main()
