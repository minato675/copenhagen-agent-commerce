#!/usr/bin/env python3
"""Loopback-only deterministic API fixtures for the isolated Copenhagen preview APK.

No real identity, credential, merchant, provider or financial data is used. All mutations
are in-memory and resettable. Do not expose this server on a network interface.
"""
import argparse
import base64
import copy
import json
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import uuid
from collections import Counter
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

LATENCIES = (0, 80, 250, 800, 2000, 8000, 30000)
MODES = ("approved", "completed", "pending", "failed", "disconnect", "offline")
STAMP = "2026-09-21T09:30:00Z"

class Fixtures:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.config = dict(mode="approved", latency_ms=250, read_latency_ms=0, empty=False, long=False, amount_minor=42800,
                               merchant_count=1, checkout_statuses=[], history_pay_error=False, history_core_error=False,
                               multi_item=False, purchase_needs_address=False, pin_status="ACTIVE")
            self.requests = Counter()
            self.approve_requests = 0
            self.logical_approvals = 0
            self.keys = {}
            self.events = []
            self.credentials = {}
            self.challenges = {}
            self.authentication_methods = Counter()
            self.pin = "123456"
            self.current_status = "PENDING"
            self.expiry = (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat().replace("+00:00", "Z")
            self.cards = [dict(paymentMethodId="33333333-3333-4333-8333-333333333333", brand="테스트 카드", last4="4242", expiryMonth=12, expiryYear=2030, isDefault=True, status="ACTIVE"),
                          dict(paymentMethodId="44444444-4444-4444-8444-444444444444", brand="테스트 카드", last4="1881", expiryMonth=8, expiryYear=2031, isDefault=False, status="ACTIVE")]
            self.selected_card = "33333333-3333-4333-8333-333333333333"
            self.addresses = [dict(addressId="88888888-8888-4888-8888-888888888888", recipient="테스트 사용자", phone="010-0000-0000", postalCode="03000", address="서울시 테스트로 12", detail="테스트동 301호", memo="문 앞에 놓아 주세요", isDefault=True)]
            self.connections = [dict(connectionId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", clientName="로컬 쇼핑 도우미", scopes=["cart:read", "cart:write"], connectedAt=STAMP, lastUsedAt=STAMP)]
            self.cart_version = 1
            self.cart_items = [dict(cartItemId="cccccccc-cccc-4ccc-8ccc-cccccccccccc", merchantId="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", productId="dddddddd-dddd-4ddd-8ddd-dddddddddddd", optionValueIds=["navy"], productName="에브리데이 토트백", quantity=1, unitPrice=dict(amount=42800, currency="KRW"), lineTotal=42800)]

    def approval(self, id="11111111-1111-4111-8111-111111111111", status=None):
        c = self.config
        return dict(approvalId=id, merchantName="코펜하겐 라이프스타일 스튜디오 한정판 컬렉션 온라인 스토어" if c["long"] else "코펜하겐 스토어", checkoutId="66666666-6666-4666-8666-666666666666", amountMinor=c["amount_minor"], currency="KRW", status=status or self.current_status, requiredAction="PIN", expiresAt=self.expiry, selectedPaymentMethodId=self.selected_card, checkoutHash="fixture-checkout-hash-1", summary=[dict(name="에브리데이 토트백", option="네이비 · 기본", quantity=1)])

    def history_approval(self):
        value = self.approval("22222222-2222-4222-8222-222222222222", "APPROVED")
        value.update(merchantName="노르딕 문구점", amountMinor=18500, checkoutId="77777777-7777-4777-8777-777777777777", summary=[dict(name="위클리 노트", quantity=1)])
        return value

    def approval_wire(self, value, detail=False, device_id=None):
        """Current Pay list/detail shapes; the legacy object is only fixture state."""
        count = self.config.get("merchant_count", 1)
        total = value["amountMinor"]
        merchants = [dict(merchantId="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", merchantName=value["merchantName"],
            checkoutId=value["checkoutId"], checkoutHash=value["checkoutHash"],
            summary=[dict(item, amountMinor=total if count == 1 else total - 18500) for item in value["summary"]],
            subtotalAmountMinor=total if count == 1 else total - 18500)]
        if count == 2:
            merchants.append(dict(merchantId="merchant-two", merchantName="노르딕 문구점", checkoutId="checkout-two",
                checkoutHash="fixture-hash-two", summary=[dict(name="위클리 노트", option=None, quantity=1, amountMinor=18500)],
                subtotalAmountMinor=18500))
        if self.config["multi_item"]:
            subtotal = merchants[0]["subtotalAmountMinor"]
            part = subtotal // 4
            merchants[0]["summary"] = [dict(name="에브리데이 토트백", option="네이비 · 기본", quantity=1, amountMinor=subtotal - part * 3),
                dict(name="미니 파우치", option="아이보리", quantity=2, amountMinor=part),
                dict(name="코튼 키링", option="차콜", quantity=1, amountMinor=part)]
        common = {key: value[key] for key in ("approvalId", "currency", "status", "requiredAction", "expiresAt")}
        common["totalAmountMinor"] = total
        challenge = None
        credential = self.credentials.get(device_id)
        if credential and value["status"] == "PENDING":
            common["requiredAction"] = "BIOMETRIC"
            if detail:
                challenge_key = (value["approvalId"], device_id)
                if challenge_key not in self.challenges:
                    self.challenges[challenge_key] = dict(challengeId=str(uuid.uuid4()), challenge=secrets.token_urlsafe(32),
                        expiresAt=self.expiry, credentialId=credential["credentialId"], consumed=False)
                challenge = {k: self.challenges[challenge_key][k] for k in ("challengeId", "challenge", "expiresAt")}
        if detail:
            return dict(common, merchants=merchants, selectedPaymentMethodId=value["selectedPaymentMethodId"], biometricChallenge=challenge)
        return dict(common, merchantName=value["merchantName"], merchantCount=count)

    def purchase(self):
        if self.config["purchase_needs_address"]:
            return dict(purchaseRequestId="99999999-9999-4999-8999-999999999999", status="PENDING_ADDRESS",
                cartId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", cartVersion=self.cart_version, expiresAt=self.expiry,
                nextAction="SELECT_SHIPPING_ADDRESS", failureCode=None, merchantCheckouts=[])
        approval = self.approval_wire(self.approval(), detail=True)
        statuses = self.config.get("checkout_statuses") or ["COMPLETING"] * len(approval["merchants"])
        status = "COMPLETED" if all(s == "COMPLETED" for s in statuses) else (
            "COMPLETING" if any(s in ("COMPLETING", "AWAITING_APPROVAL") for s in statuses) else (
                "PARTIALLY_COMPLETED" if "COMPLETED" in statuses else "FAILED"))
        return dict(purchaseRequestId="99999999-9999-4999-8999-999999999999", status=status,
            cartId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", cartVersion=1, expiresAt=self.expiry,
            nextAction="WAIT" if status == "COMPLETING" else "SHOW_RESULT", failureCode=None,
            merchantCheckouts=[dict(merchantId=m["merchantId"], approvalId=approval["approvalId"],
                status=statuses[i], totalAmount=m["subtotalAmountMinor"], currency="KRW") for i, m in enumerate(approval["merchants"])])

    def stats(self):
        return dict(config=self.config, requests=dict(self.requests), approve_requests=self.approve_requests, logical_approvals=self.logical_approvals, idempotency_keys=list(self.keys), authoritative_status=self.current_status, events=self.events,
            authentication_methods=dict(self.authentication_methods), biometric_credential_count=len(self.credentials))

    def configure_cart(self):
        """Matching snapshot for a multi-item address review, with exact line totals."""
        if not self.config["multi_item"]:
            return
        merchants = self.approval_wire(self.approval(), detail=True)["merchants"]
        self.cart_items = [dict(cartItemId=str(uuid.uuid5(uuid.NAMESPACE_URL, "fixture-cart-" + str(index))),
            merchantId=merchant["merchantId"], productId=str(uuid.uuid5(uuid.NAMESPACE_URL, "fixture-product-" + str(index))),
            optionValueIds=[item["option"]] if item.get("option") else [], productName=item["name"], quantity=item["quantity"],
            unitPrice=dict(amount=item["amountMinor"], currency="KRW"), lineTotal=item["amountMinor"] * item["quantity"])
            for index, (merchant, item) in enumerate((m, i) for m in merchants for i in m["summary"])]


def decode_base64url(value):
    return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)


def verify_biometric(public_key, challenge, assertion):
    """Verify the same DER EC key / UTF-8 challenge / DER ECDSA signature as Pay.

    OpenSSL is a local fixture dependency; no private key or authentication value is logged.
    Temporary public-key and signature files are deleted immediately after verification.
    """
    try:
        with tempfile.TemporaryDirectory(prefix="copenhagen-fixture-biometric-") as directory:
            public_path = Path(directory) / "public.pem"
            signature_path = Path(directory) / "signature.der"
            converted = subprocess.run(["openssl", "pkey", "-pubin", "-inform", "DER", "-out", str(public_path)],
                input=decode_base64url(public_key), capture_output=True, timeout=5)
            if converted.returncode:
                return False
            signature_path.write_bytes(decode_base64url(assertion))
            verified = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public_path), "-signature", str(signature_path)],
                input=challenge.encode("utf-8"), capture_output=True, timeout=5)
            return verified.returncode == 0
    except (OSError, ValueError, TypeError, subprocess.SubprocessError):
        return False

STATE = Fixtures()

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, fmt, *args):
        pass  # Never log request payloads, authentication or PINs.
    def reply(self, data=None, status=200):
        raw = b"" if status == 204 else json.dumps(data if data is not None else {}, ensure_ascii=False).encode()
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass
    def disconnect(self):
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.connection.close()
    def handle_request(self):
        parsed = urlsplit(self.path)
        path, method = parsed.path, self.command
        query = {k: values[0] for k, values in parse_qs(parsed.query).items()}
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}") if length else {}
        if path == "/__control/reset" and method == "POST":
            STATE.reset()
            return self.reply(STATE.stats())
        if path == "/__control/config" and method == "POST":
            if body.get("mode", "approved") not in MODES:
                return self.reply(dict(error="Invalid mode"), 400)
            if any(int(body.get(k, 0)) < 0 or int(body.get(k, 0)) > 30000 for k in ("latency_ms", "read_latency_ms")):
                return self.reply(dict(error="Delay must be 0..30000 ms"), 400)
            with STATE.lock:
                STATE.config.update({k: v for k, v in body.items() if k in STATE.config})
                STATE.configure_cart()
                if "status" in body:
                    STATE.current_status = str(body["status"])
            return self.reply(STATE.stats())
        if path == "/__control/stats":
            return self.reply(STATE.stats())
        with STATE.lock:
            STATE.requests[f"{method} {path}"] += 1
            config = copy.deepcopy(STATE.config)
        if config["mode"] == "offline":
            return self.disconnect()
        if path.endswith("/approve") and method == "POST":
            return self.approve(path, body, config)
        # Snapshot before delaying reads makes intentionally stale responses possible.
        with STATE.lock:
            result, status = self.route(method, path, query, body)
        if method == "GET" and config["read_latency_ms"]:
            time.sleep(config["read_latency_ms"] / 1000)
        return self.reply(result, status)

    def approve(self, path, body, config):
        with STATE.lock:
            STATE.approve_requests += 1
            key = self.headers.get("Idempotency-Key")
            if not key:
                return self.reply(dict(code="IDEMPOTENCY_KEY_REQUIRED"), 400)
            # A retry with the same idempotency key returns its accepted result even
            # after a biometric challenge has been consumed.
            if key in STATE.keys:
                return self.reply(STATE.keys[key])
            authentication = body.get("authentication", {})
            method = authentication.get("method")
            challenge = None
            if method == "PIN":
                if config["pin_status"] != "ACTIVE" or authentication.get("pin") != STATE.pin:
                    return self.reply(dict(code="INVALID_PIN"), 422)
            elif method == "BIOMETRIC":
                # Pay's approve request binds the challenge to the approval and
                # credential itself; unlike detail/status it needs no device header.
                match = next(((device_id, value) for (approval_id, device_id), value in STATE.challenges.items()
                    if approval_id == path.split("/")[-2] and value["challengeId"] == authentication.get("challengeId")), None)
                credential = STATE.credentials.get(match[0]) if match else None
                challenge = match[1] if match else None
                if not credential or not challenge or challenge["consumed"] or (
                        challenge["credentialId"] != credential["credentialId"] or
                        challenge["challengeId"] != authentication.get("challengeId") or
                        datetime.fromisoformat(challenge["expiresAt"].replace("Z", "+00:00")) <= datetime.now(timezone.utc)):
                    return self.reply(dict(code="BIOMETRIC_CHALLENGE_INVALID"), 422)
                if not verify_biometric(credential["publicKey"], challenge["challenge"], authentication.get("assertion")):
                    return self.reply(dict(code="BIOMETRIC_CHALLENGE_INVALID", nextAction="PIN"), 422)
            else:
                return self.reply(dict(code="INVALID_REQUEST"), 400)
            if config["mode"] == "failed":
                return self.reply(dict(code="PAYMENT_DECLINED"), 422)
            if key in STATE.keys:
                result = STATE.keys[key]
            else:
                if STATE.current_status != "PENDING":
                    return self.reply(dict(code="APPROVAL_STATE_CONFLICT"), 409)
                STATE.logical_approvals += 1
                STATE.authentication_methods[method] += 1
                if challenge:
                    challenge["consumed"] = True
                status = dict(approved="APPROVED", completed="COMPLETED", pending="PENDING", failed="FAILED", disconnect="APPROVED")[config["mode"]]
                STATE.current_status = status
                result = dict(approvalId=path.split("/")[-2], status=status)
                STATE.keys[key] = result
                STATE.events.append(dict(event="approval_accepted", at_monotonic=time.monotonic(), key=key, status=status,
                    authentication_method=method))
        time.sleep(config["latency_ms"] / 1000)
        if config["mode"] == "disconnect":
            return self.disconnect()
        return self.reply(result)

    def route(self, method, path, query, body):
        s = STATE
        if path == "/api/core/v1/auth/login":
            if body.get("loginId") != "preview" or body.get("password") != "preview-password":
                return dict(code="INVALID_CREDENTIALS"), 401
            return dict(accessToken="fixture-access-only", refreshToken="fixture-refresh-only", expiresIn=3600), 200
        if path.endswith("/auth/token/refresh"):
            return dict(accessToken="fixture-access-only", refreshToken="fixture-refresh-only", expiresIn=3600), 200
        if path.endswith(("/auth/signup", "/auth/logout")):
            return {}, 200
        if path.endswith("/users/me/payment-pin"):
            if method in ("POST", "PUT"):
                value = body.get("pin" if method == "POST" else "newPin")
                if not isinstance(value, str) or len(value) != 6 or not value.isascii() or not value.isdigit():
                    return dict(code="INVALID_REQUEST"), 400
                if method == "PUT" and body.get("currentPin") != s.pin:
                    return dict(code="INVALID_PIN"), 422
                s.pin = value
                s.config["pin_status"] = "ACTIVE"
            return dict(registered=s.config["pin_status"] != "NOT_REGISTERED", status=s.config["pin_status"]), 200
        if path.endswith("/users/me/biometric-credentials"):
            device_id = body.get("deviceId") if method == "POST" else self.headers.get("X-Device-Id")
            # Preserve the original unregistered fixture response for headerless reads.
            if method == "GET":
                credential = s.credentials.get(device_id)
                return (dict(registered=True, status="ACTIVE", credentialId=credential["credentialId"], registeredAt=credential["registeredAt"])
                    if credential else dict(registered=False, status=None, credentialId=None, registeredAt=None)), 200
            if not isinstance(device_id, str) or not device_id.strip() or len(device_id) > 150:
                return dict(code="BIOMETRIC_CREDENTIAL_INVALID"), 400
            if method == "POST":
                try:
                    public_key = body["publicKey"]
                    if (body.get("credentialType") != "PLATFORM_BIOMETRIC" or not isinstance(public_key, str) or
                            len(public_key) > 8192 or not decode_base64url(public_key)):
                        raise ValueError()
                except (KeyError, ValueError, TypeError):
                    return dict(code="BIOMETRIC_CREDENTIAL_INVALID"), 400
                if device_id in s.credentials:
                    return dict(code="BIOMETRIC_CREDENTIAL_ALREADY_REGISTERED"), 409
                credential = dict(credentialId=str(uuid.uuid4()), registeredAt=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                    publicKey=public_key)
                s.credentials[device_id] = credential
                return {k: credential[k] for k in ("credentialId", "registeredAt")}, 201
            if method == "DELETE":
                s.credentials.pop(device_id, None)
                s.challenges = {k: v for k, v in s.challenges.items() if k[1] != device_id}
                return None, 204
        if "/notification-installations" in path:
            return {}, 200
        if path == "/api/pay/v1/payment-approvals":
            if s.config.get("history_pay_error"):
                return dict(code="SERVICE_UNAVAILABLE"), 503
            wanted = query.get("status")
            values = []
            if not s.config["empty"]:
                if wanted is None or s.current_status == wanted:
                    values.append(s.approval())
                if wanted is None or wanted == "APPROVED":
                    values.append(s.history_approval())
            return dict(items=[s.approval_wire(value, device_id=self.headers.get("X-Device-Id")) for value in values]), 200
        if path.startswith("/api/pay/v1/payment-approvals/"):
            if method == "GET" and s.config.get("history_pay_error"):
                return dict(code="SERVICE_UNAVAILABLE"), 503
            id = path.split("/")[5]
            if path.endswith("/payment-method"):
                s.selected_card = body["paymentMethodId"]
                s.challenges = {k: v for k, v in s.challenges.items() if k[0] != id}
                return {}, 200
            if path.endswith("/reject"):
                s.current_status = "REJECTED"
                return dict(approvalId=id, status="REJECTED"), 200
            return s.approval_wire(s.history_approval() if id == "22222222-2222-4222-8222-222222222222" else s.approval(id), detail=True,
                device_id=self.headers.get("X-Device-Id")), 200
        if path == "/api/core/v1/purchase-requests" and method == "GET":
            if s.config.get("history_core_error"):
                return dict(code="SERVICE_UNAVAILABLE"), 503
            return ([s.purchase()] if (s.current_status == "APPROVED" or s.config["purchase_needs_address"]) and not s.config["empty"] else []), 200
        if path == "/api/core/v1/purchase-requests/99999999-9999-4999-8999-999999999999" and method == "GET":
            if s.config.get("history_core_error"):
                return dict(code="SERVICE_UNAVAILABLE"), 503
            return s.purchase(), 200
        if path == "/api/pay/v1/payment-methods/cards":
            if method == "GET":
                return dict(items=[] if s.config["empty"] else s.cards), 200
            card = dict(s.cards[0] if s.cards else dict(brand="테스트 카드", last4="4242", expiryMonth=12, expiryYear=2030, isDefault=True, status="ACTIVE"), paymentMethodId="55555555-5555-4555-8555-555555555555")
            s.cards.append(card)
            return card, 200
        if path.startswith("/api/pay/v1/payment-methods/cards/"):
            id = path.split("/")[6]
            if method == "DELETE":
                s.cards = [c for c in s.cards if c["paymentMethodId"] != id]
            elif path.endswith("/default"):
                for card in s.cards:
                    card["isDefault"] = card["paymentMethodId"] == id
            return {}, 200
        if path == "/api/pg/v1/payment-methods/tokenize":
            return dict(provider="mock-pg", providerTokenRef="fixture-provider-token", brand="테스트 카드", last4=body.get("cardNumber", "4242")[-4:], expiryMonth=body.get("expiryMonth", 12), expiryYear=body.get("expiryYear", 2030)), 200
        if path == "/api/core/v1/users/me":
            return dict(name="테스트 사용자", loginId="preview", email="preview@example.invalid"), 200
        if path.startswith("/api/core/v1/users/me/agent-connections"):
            if method == "DELETE":
                s.connections = [c for c in s.connections if c["connectionId"] != path.split("/")[-1]]
                return {}, 200
            return [] if s.config["empty"] else s.connections, 200
        if path == "/api/core/v1/addresses":
            if method == "POST":
                value = dict(body, addressId="99999999-9999-4999-8999-999999999999", isDefault=False)
                s.addresses.append(value)
                return value, 200
            return dict(items=[] if s.config["empty"] else s.addresses), 200
        if path.startswith("/api/core/v1/addresses/"):
            id = path.split("/")[5]
            value = next((a for a in s.addresses if a["addressId"] == id), None)
            if not value:
                return dict(code="NOT_FOUND"), 404
            if method == "DELETE":
                s.addresses.remove(value)
            elif method == "PUT":
                value.update(body)
            elif path.endswith("/default"):
                for address in s.addresses:
                    address["isDefault"] = address["addressId"] == id
            return value, 200
        if path.startswith("/api/core/v1/orders"):
            value = dict(orderId="ffffffff-ffff-4fff-8fff-ffffffffffff", title="에브리데이 토트백", status="CONFIRMED", amountMinor=42800, currency="KRW", createdAt=STAMP, items=[dict(name="에브리데이 토트백", option="네이비", quantity=1)], address=dict(recipient="테스트 사용자", phone="010-0000-0000", postalCode="01234", address="서울시 테스트로 12", detail="101호", memo="문 앞에 놓아 주세요"), paymentLabel="테스트 카드 •••• 4242")
            items = [] if s.config["empty"] or query.get("status", "CONFIRMED") != value["status"] else [value]
            return (dict(items=items) if path.endswith("/orders") else value), 200
        if path == "/api/core/v1/cart":
            items = [] if s.config["empty"] else s.cart_items
            total = sum(i["lineTotal"] for i in items)
            return dict(cartId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", version=s.cart_version, items=items, summary=dict(itemsTotal=total, discountTotal=0, shippingFee=0, estimatedTotal=total, currency="KRW")), 200
        if path.startswith("/api/core/v1/cart/items/"):
            id = path.split("/")[-1]
            if int(body.get("cartVersion", query.get("cartVersion", -1))) != s.cart_version:
                return dict(code="CART_VERSION_CONFLICT"), 409
            if method == "DELETE":
                s.cart_items = [i for i in s.cart_items if i["cartItemId"] != id]
            else:
                for item in s.cart_items:
                    if item["cartItemId"] == id:
                        item["quantity"] = body["quantity"]
                        item["lineTotal"] = item["quantity"] * item["unitPrice"]["amount"]
            s.cart_version += 1
            return dict(cartVersion=s.cart_version), 200
        offer = dict(merchantId="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", merchantName="코펜하겐 스토어", price=42800, currency="KRW", availability="IN_STOCK")
        product = dict(productId="dddddddd-dddd-4ddd-8ddd-dddddddddddd", name="에브리데이 토트백", brand="Copenhagen", category="가방")
        if path == "/api/core/v1/products/search":
            products = [] if s.config["empty"] else [dict(product, offers=[offer])]
            return dict(data=dict(status="COMPLETE", products=products, categories=["가방"], merchants=[dict(merchantId="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", name="코펜하겐 스토어")], failedMerchants=[], total=len(products), hasNext=False, checkedAt=STAMP)), 200
        if path.startswith("/api/core/v1/products/"):
            detail = dict(product, description="가벼운 일상을 위한 코튼 토트백", specs=[dict(name="소재", value="코튼 100%")], offer=offer, variants=[dict(optionId="navy", label="네이비", price=42800, availability="IN_STOCK", purchaseAllowed=True)], optionsComplete=True, optionsRequired=True, selectionStatus="RESOLVED", selectedOptionId=query.get("optionId", "navy"), stockQuantity=8, shipping=dict(status="AVAILABLE", fee=0, description="무료 배송"))
            return dict(data=dict(status="FOUND", request=dict(productId=product["productId"], merchantId=offer["merchantId"], optionId=query.get("optionId")), product=detail, checkedAt=STAMP)), 200
        return dict(code="FIXTURE_ROUTE_NOT_IMPLEMENTED", path=path), 404

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_request

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Local fixtures only: http://127.0.0.1:{args.port}; preview / preview-password; PIN 123456", flush=True)
    server.serve_forever()
