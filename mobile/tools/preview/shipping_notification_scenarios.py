#!/usr/bin/env python3
"""Shipping notification journeys on the isolated native fixture APK only.

The server is loopback-only and ephemeral. Saved preview preferences and adb port
mapping are restored; the installed production account is never used or changed.
"""
import copy
import json
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from http.server import ThreadingHTTPServer
from pathlib import Path

import device as d
import fixture_server as f
import notification_scenarios as n
from biometric_revocation_scenarios import preferences, write_preferences

OUT = Path("docs/redesign/evidence/shipping-notification")
PID = "abababab-abab-4bab-8bab-abababababab"
BASE = "/api/core/v1/purchase-requests"
OTHER = "99999999-9999-4999-8999-999999999999"
TITLE = "로컬 테스트 · 배송지 선택 요청"


class Shipping:
    def reset(self, outcome="ok", latency=0, empty=False, expired=False):
        f.STATE.reset()
        self.outcome, self.latency = outcome, latency
        self.status = "EXPIRED" if expired else "PENDING_ADDRESS"
        self.posts, self.keys = [], {}
        self.accepted = threading.Event()
        if empty:
            f.STATE.addresses = []
        else:
            other = dict(f.STATE.addresses[0], addressId=OTHER, recipient="회사 수령인", address="서울시 업무로 24", detail="5층", isDefault=False)
            f.STATE.addresses.append(other)

    def view(self):
        value = dict(purchaseRequestId=PID, cartId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", cartVersion=1,
                     status=self.status, expiresAt=f.STATE.expiry, failureCode=None,
                     nextAction={"PENDING_ADDRESS":"SELECT_SHIPPING_ADDRESS", "CREATING_CHECKOUTS":"WAIT",
                                 "AWAITING_APPROVAL":"OPEN_APPROVAL"}.get(self.status,"SHOW_RESULT"), merchantCheckouts=[])
        if self.status == "AWAITING_APPROVAL":
            value["merchantCheckouts"] = [dict(merchantId="store", approvalId=n.ID, status="AWAITING_APPROVAL", totalAmount=42800, currency="KRW")]
        return value


S = Shipping()


class Handler(f.Handler):
    def route(self, method, path, query, body):
        if path == BASE:
            return [S.view()], 200
        if path == BASE + "/" + PID:
            return S.view(), 200
        return super().route(method, path, query, body)

    def do_POST(self):
        if self.path != BASE + "/" + PID + "/resume":
            return super().handle_request()
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        key = self.headers.get("Idempotency-Key", "")
        try:
            uuid.UUID(key)
            assert self.headers.get("Authorization") == "Bearer fixture-access-only"
            assert set(body) == {"addressId", "cartVersion"} and body["cartVersion"] == 1
            assert body["addressId"] in [a["addressId"] for a in f.STATE.addresses]
        except (AssertionError, ValueError):
            return self.reply(dict(errorCode="INVALID_REQUEST"), 400)
        S.posts.append(dict(key=key, body=body))
        S.accepted.set()
        time.sleep(S.latency)
        if S.outcome == "offline":
            return self.disconnect()
        if S.outcome == "conflict":
            return self.reply(dict(errorCode="CART_VERSION_CONFLICT"),409)
        if key in S.keys:
            assert S.keys[key] == body, "An idempotency key was reused for different content"
        S.keys[key] = body
        S.status = "CREATING_CHECKOUTS"
        if S.outcome == "lost_response":
            return self.disconnect()
        return self.reply(S.view())


def post(kind="purchaseRequestId", id=PID):
    result=d.adb("shell","am","broadcast","-n",n.RECEIVER,"-a",n.NOTIFY_ACTION,"--es",kind,id).decode()
    assert "result=-1" in result,result


def tap_notification(title=TITLE, expected=("이 배송지로 선택",)):
    d.adb("shell","cmd","statusbar","expand-notifications")
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        try:
            root=n.native_tree(n.SYSTEM_UI)
        except (RuntimeError,ET.ParseError,subprocess.CalledProcessError):
            continue
        matches=[node for node in root.iter("node") if node.get("package")==n.SYSTEM_UI and node.get("text")==title]
        if matches:
            n.tap_node(matches[-1]); break
    else:
        raise AssertionError("Notification title absent")
    n.wait_top(lambda top: top["component"]==n.HOST,"shipping notification host")
    return d.wait_for(list(expected),timeout=40)


def restore(raw):
    d.adb("shell","am","force-stop",d.PACKAGE)
    write_preferences(raw)


def launch():
    d.adb("shell","am","start","-n",d.ACTIVITY)
    d.wait_for(["나의 지갑"],timeout=40)


def begin(raw, context="settings", expected=None, **config):
    restore(raw); S.reset(**config); launch()
    if context=="settings":
        d.adb("shell","am","start","-a","android.settings.SETTINGS")
        origin=n.wait_top(lambda t:t["component"].startswith("com.android.settings/"),"Settings")
    else:
        d.adb("shell","input","keyevent","3")
        origin=n.wait_top(lambda t:t["component"] not in [n.HOST,d.ACTIVITY],"Home")
    post()
    expected=expected or (["요청이 만료되었어요"] if config.get("expired") else ["등록된 배송지가 없어요"] if config.get("empty") else ["이 배송지로 선택"])
    tap_notification(expected=expected)
    return origin,n.top()


def shot(name):
    (OUT/(name+".png")).write_bytes(d.adb("exec-out","screencap","-p"))


def explicit_return(origin,host):
    assert n.same_task(n.top(),host),"Result returned automatically"
    n.click("돌아가기")
    current=n.wait_top(lambda top:n.same_task(top,origin),"original context after explicit return")
    n.wait_notification_task_gone(host)
    return current


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    mapping=d.adb("reverse","--list").decode().splitlines()
    old=next((line.split()[-1] for line in mapping if " tcp:8787 " in line),None)
    d.adb("shell","am","force-stop",d.PACKAGE)
    original=preferences()
    apk_path=d.adb("shell","pm","path",d.PACKAGE).decode().strip().removeprefix("package:")
    apk_sha=d.adb("shell","sha256sum",apk_path).decode().split()[0]
    assert any(node.get("name")=="tokens" for node in ET.fromstring(original)),"Preview login required"
    d.adb("shell","pm","grant",d.PACKAGE,"android.permission.POST_NOTIFICATIONS")
    server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    cases=[]
    recorder=None
    def passed(name,**data):
        assert f.STATE.approve_requests==0,"Shipping triggered payment approval"
        cases.append(dict(scenario=name,passed=True,posts=copy.deepcopy(S.posts),**data))
        print(name,"PASS",flush=True)
    try:
        d.adb("reverse","tcp:8787",f"tcp:{server.server_port}")
        origin,host=begin(original,latency=8)
        root=d.tree()
        assert any((node.get("selected")=="true" or node.get("checked")=="true")
                   and "테스트 사용자" in d.visible_labels(node) for node in root.iter("node")),"Default address not selected"
        assert not S.posts,"Default preselection sent a command without confirmation"
        shot("selection")
        recorder=subprocess.Popen(["adb","-s","emulator-5554","shell","screenrecord","--time-limit","60",
            "/sdcard/copenhagen-shipping-notification.mp4"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        n.click("회사 수령인")
        n.scroll_to("이 배송지로 선택")
        root=d.tree(); button=next(node for node in root.iter("node") if node.get("text")=="이 배송지로 선택")
        n.tap_node(button)
        assert S.accepted.wait(3),"No resume request"
        n.tap_node(button)
        d.wait_for(["선택을 보내는 중"]); shot("sending")
        assert len(S.posts)==1
        d.wait_for(["배송지 선택 완료"],timeout=35)
        shot("selected")
        assert S.posts[0]["body"]["addressId"]==OTHER
        assert f.STATE.addresses[0]["isDefault"] and not f.STATE.addresses[1]["isDefault"]
        explicit_return(origin,host)
        passed("other app -> explicit address selection -> explicit return; rapid taps deduplicated",origin=origin)

        origin,host=begin(original,context="home",latency=.08)
        n.click("이 배송지로 선택")
        d.wait_for(["배송지 선택 완료"])
        assert S.posts[0]["body"]["addressId"]=="88888888-8888-4888-8888-888888888888"
        explicit_return(origin,host)
        passed("preselected default submits only on confirmation; home returns only on user action")

        origin,host=begin(original)
        n.click("회사 수령인"); post(); tap_notification()
        assert "회사 수령인" in d.visible_labels(d.tree())
        n.click("이 배송지로 선택"); d.wait_for(["배송지 선택 완료"])
        S.status="AWAITING_APPROVAL"
        d.wait_for(["결제 요청 보기"])
        shot("approval-ready")
        n.click("결제 요청 보기"); d.wait_for(["요청 총액","42,800원 승인"])
        n.click("42,800원 승인"); d.wait_for(["PIN으로 결제하기"])
        d.enter_pin("123456")
        fingerprint=n.pin_fingerprint()
        post(); tap_notification(expected=["PIN으로 결제하기"])
        assert n.pin_fingerprint()==fingerprint
        post("approvalId",n.ID); tap_notification(n.NOTIFICATION_TITLE,expected=["PIN으로 결제하기"])
        assert n.pin_fingerprint()==fingerprint
        n.click("닫고 돌아가기")
        n.wait_top(lambda t:n.same_task(t,origin),"origin after approval close")
        passed("duplicate notifications retain selection and PIN; server-linked approval uses existing auth")

        for outcome in ("lost_response","offline"):
            origin,host=begin(original,outcome=outcome)
            n.click("회사 수령인"); n.click("이 배송지로 선택")
            d.wait_for(["선택 결과 확인 필요"],timeout=35)
            shot(outcome)
            before=copy.deepcopy(S.posts)
            d.adb("shell","am","force-stop",d.PACKAGE)
            post(); tap_notification(expected=["결제 요청 준비 중"] if outcome=="lost_response" else ["선택 결과 확인 필요"])
            assert len(S.posts)==1,"Process restart resent a mutation"
            if outcome=="offline":
                S.outcome="ok"; n.click("같은 선택 다시 보내기"); d.wait_for(["배송지 선택 완료"])
                assert len(S.posts)==2 and S.posts[0]==S.posts[1]
            else:
                assert len(S.posts)==1 and S.posts==before
            n.click("돌아가기")
            passed(outcome+" survives process restart; read before exact replay")

        origin,host=begin(original,expired=True)
        assert "이 배송지로 선택" not in d.visible_labels(d.tree())
        shot("expired"); explicit_return(origin,host); assert not S.posts
        passed("expired request cannot select")

        origin,host=begin(original,empty=True)
        shot("empty"); n.click("배송지 추가")
        d.wait_for(["수령인","주소 검색"])
        for index,value in enumerate(["Preview","01000000000","03000","Preview%sstreet","Suite%s1"]):
            inputs=[node for node in d.tree().iter("node") if node.get("class")=="android.widget.EditText"]
            if index>=len(inputs): n.scroll_to("저장")
            # Fields remain in order in the semantic tree; scroll to each label before typing.
            label=["수령인","연락처","우편번호","주소","상세주소"][index]
            n.scroll_to(label)
            nodes=[node for node in d.tree().iter("node") if node.get("class")=="android.widget.EditText" and label in d.visible_labels(node)]
            n.tap_node(nodes[0]); d.adb("shell","input","text",value); d.hide_keyboard()
        n.click("저장"); d.wait_for(["이 배송지로 선택","Preview"])
        assert not S.posts,"Creating an address auto-confirmed it"
        shot("added-address")
        n.click("이 배송지로 선택"); d.wait_for(["배송지 선택 완료"])
        explicit_return(origin,host)
        passed("empty list -> add address -> explicit confirmation -> return")

        origin,host=begin(original,outcome="conflict")
        n.click("회사 수령인"); n.click("이 배송지로 선택")
        d.wait_for(["장바구니가 변경되었어요. 구매 요청을 다시 확인해 주세요.","이 배송지로 선택"])
        assert "배송지 선택 완료" not in d.visible_labels(d.tree())
        explicit_return(origin,host)
        passed("cart conflict never shows confirmed selection")

        restore(original); S.reset(); launch()
        n.click("결제"); d.wait_for(["요청 abababab"])
        n.click("요청 abababab"); d.wait_for(["이 배송지로 선택"])
        assert n.top()["component"]==d.ACTIVITY
        assert not S.posts
        passed("missed notification recoverable from payment tab")

        # Login gate is exercised with only the disposable preview token removed.
        restore(original); S.reset()
        root=ET.fromstring(original)
        for node in list(root):
            if node.get("name") in {"tokens","unresolvedApproval","unresolvedPurchase"}: root.remove(node)
        write_preferences(ET.tostring(root,encoding="utf-8",xml_declaration=True))
        post(); tap_notification(expected=["반가워요","아이디"])
        assert not S.posts
        shot("login-gate")
        n.click("다음"); d.wait_for(["비밀번호"])
        d.fill("preview-password"); n.click("로그인")
        d.wait_for(["이 배송지로 선택"],timeout=40)
        assert not S.posts
        n.click("돌아가기")
        passed("signed-out notification requires existing login and resumes the same purchase")
    finally:
        if recorder is not None:
            recorder.wait(timeout=60)
            d.adb("pull","/sdcard/copenhagen-shipping-notification.mp4",str(OUT/"selection-to-result.mp4"))
        restore(original)
        if old: d.adb("reverse","tcp:8787",old)
        else: d.adb("reverse","--remove","tcp:8787")
        server.shutdown(); server.server_close()
        (OUT/"native-verification.json").write_text(json.dumps(dict(cases=cases,passed=len(cases),expected_scenarios=10,
            complete=len(cases)==10,installed_apk_sha256=apk_sha,
            environment="emulator-5554 API 36; isolated .preview APK; native SystemUI notifications; local fixture only",
            preview_preferences_restored=preferences()==original),ensure_ascii=False,indent=2))


if __name__=="__main__": main()
