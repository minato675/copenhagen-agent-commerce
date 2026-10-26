#!/usr/bin/env python3
"""UCP Merchant Mock 단독 E2E 스모크.

Core 와 Pay 없이 이 모듈 하나가 구매 전 구간을 도는지 실기동으로 확인한다.
Pay 청구와 Core 웹훅 수신을 한 프로세스의 스텁이 받아 호출 횟수를 센다.

DESIGN.md 12.2절, Jira S15P21A207-186.

    python tools/e2e_smoke.py --print-launch     # 앱 기동 명령만 찍는다
    python tools/e2e_smoke.py                    # 스텁을 띄우고 전 단계를 돈다

단계마다 대응하는 curl 을 같이 찍는다. 그 출력이 README 재현 절차가 된다.
어느 단계든 실패하면 비영 종료 코드로 끝난다.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric import utils as asym_utils
except ImportError:  # pragma: no cover - 환경 안내
    sys.exit("cryptography 가 필요하다. pip install cryptography")

# Windows 콘솔 기본 코드페이지가 cp949 라 한글 출력이 깨진다. 출력만 UTF-8 로 고정한다.
for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

SHOPPING_SERVICE = "dev.ucp.shopping"
AP2_LIKE_NAMESPACE = "com.copenhagen.payment.ap2_like"
PAYMENT_HANDLER_NAMESPACE = "com.copenhagen.pay"
PAYMENT_HANDLER_ID = "copenhagen-pay-handler"
WEBHOOK_PATH = "/webhooks/v1/ucp/orders"
SIGNED_COMPONENTS = '"@method" "@authority" "@path" "content-digest" "content-type"'
SIGNATURE_INPUT_RE = re.compile(
    r'^sig1=\((?P<components>[^)]*)\);created=(?P<created>\d+);keyid="(?P<keyid>[^"]+)"$'
)
SIGNATURE_RE = re.compile(r"^sig1=:(?P<value>[A-Za-z0-9+/=]+):$")


# --------------------------------------------------------------------------- 유틸


def b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def jwk_public_key(jwk: dict) -> ec.EllipticCurvePublicKey:
    x = int.from_bytes(b64url_decode(jwk["x"]), "big")
    y = int.from_bytes(b64url_decode(jwk["y"]), "big")
    return ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()


def verify_es256(public_key: ec.EllipticCurvePublicKey, signing_input: bytes, raw: bytes) -> bool:
    """R‖S 64바이트 서명을 DER 로 바꿔 확인한다. 실패는 예외가 아니라 False 다."""
    if len(raw) != 64:
        return False
    der = asym_utils.encode_dss_signature(
        int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big")
    )
    try:
        public_key.verify(der, signing_input, ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


class HttpError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body


def http_json(method: str, url: str, body=None, headers=None, timeout=30):
    data = None
    request_headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request_headers.update(headers or {})
    request = urllib.request.Request(url, data=data, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise HttpError(error.code, error.read().decode("utf-8", "replace")) from error


def curl_of(method: str, url: str, body=None) -> str:
    if body is None:
        return f"curl -s -X {method} {url}" if method != "GET" else f"curl -s {url}"
    payload = json.dumps(body, ensure_ascii=False, separators=(",", ":"))
    return f"curl -s -X {method} {url} -H 'Content-Type: application/json' -d '{payload}'"


# --------------------------------------------------------------------------- 스텁


class StubState:
    """Pay 청구와 Core 웹훅을 한 곳에서 센다. 나누면 합계가 틀려도 모른다."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.payments: list[dict] = []
        self.lookups: list[str] = []
        self.webhooks: list[dict] = []
        self.outcome = "SUCCESS"

    def add_payment(self, body: dict, headers: dict) -> dict:
        with self.lock:
            payment_id = f"pay-stub-{len(self.payments) + 1:03d}"
            record = {"paymentId": payment_id, "body": body, "headers": headers}
            self.payments.append(record)
            return record

    def find_payment(self, payment_id: str):
        with self.lock:
            self.lookups.append(payment_id)
            for record in self.payments:
                if record["paymentId"] == payment_id:
                    return record
            return None

    def add_webhook(self, path: str, headers: dict, body: bytes) -> None:
        with self.lock:
            self.webhooks.append({"path": path, "headers": headers, "body": body})

    @property
    def payment_count(self) -> int:
        with self.lock:
            return len(self.payments)

    @property
    def webhook_count(self) -> int:
        with self.lock:
            return len(self.webhooks)


class StubHandler(BaseHTTPRequestHandler):
    state: StubState = None  # 서버가 채운다
    protocol_version = "HTTP/1.1"

    def log_message(self, *args) -> None:  # 조용히
        pass

    def _send(self, status: int, payload=None) -> None:
        raw = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        if raw:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        if raw:
            self.wfile.write(raw)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 규약
        raw = self._read_body()
        headers = {k.lower(): v for k, v in self.headers.items()}
        if self.path == "/merchant/v1/payments":
            body = json.loads(raw) if raw else {}
            record = self.state.add_payment(body, headers)
            if self.state.outcome == "SUCCESS":
                self._send(200, {
                    "paymentId": record["paymentId"],
                    "status": "SUCCESS",
                    "amountMinor": body.get("amountMinor"),
                    "currency": body.get("currency"),
                    "pgTransactionId": f"pg-{record['paymentId']}",
                })
            else:
                self._send(200, {
                    "paymentId": record["paymentId"],
                    "status": "FAILED",
                    "amountMinor": body.get("amountMinor"),
                    "currency": body.get("currency"),
                    "errorCode": "PG_DECLINED",
                })
            return
        if self.path.startswith("/webhooks"):
            self.state.add_webhook(self.path, headers, raw)
            self._send(204)
            return
        self._send(404, {"code": "NOT_FOUND", "path": self.path})

    def do_GET(self) -> None:  # noqa: N802
        if self.path.startswith("/merchant/v1/payments/"):
            payment_id = self.path.rsplit("/", 1)[-1]
            record = self.state.find_payment(payment_id)
            if record is None:
                self._send(404, {"code": "RESOURCE_NOT_FOUND"})
                return
            self._send(200, {
                "paymentId": payment_id,
                "status": "SUCCESS",
                "amountMinor": record["body"].get("amountMinor"),
                "currency": record["body"].get("currency"),
            })
            return
        self._send(404, {"code": "NOT_FOUND", "path": self.path})


def start_stub(host: str, port: int) -> tuple[ThreadingHTTPServer, StubState]:
    state = StubState()
    handler = type("BoundStubHandler", (StubHandler,), {"state": state})
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, state


# --------------------------------------------------------------------------- 스모크


class Smoke:
    def __init__(self, args, state: StubState) -> None:
        self.args = args
        self.state = state
        self.base = args.base_url.rstrip("/")
        self.merchant = args.merchant
        self.failures: list[str] = []
        self.skipped: list[str] = []
        self.step_no = 0
        self.public_key = None
        self.kid = None

    # ---- 출력

    def step(self, title: str, curl: str = None) -> None:
        self.step_no += 1
        print(f"\n[{self.step_no:2d}] {title}")
        if curl:
            print(f"     {curl}")

    def ok(self, message: str) -> None:
        print(f"     OK   {message}")

    def fail(self, message: str) -> None:
        print(f"     FAIL {message}")
        self.failures.append(f"[{self.step_no}] {message}")

    def skip(self, message: str) -> None:
        print(f"     SKIP {message}")
        self.skipped.append(f"[{self.step_no}] {message}")

    def check(self, condition: bool, ok_message: str, fail_message: str) -> bool:
        if condition:
            self.ok(ok_message)
        else:
            self.fail(fail_message)
        return condition

    # ---- 주소

    def ucp(self, merchant: str, path: str) -> str:
        return f"{self.base}/api/ucp/v1/{merchant}{path}"

    # ---- 단계

    def wait_for_app(self) -> bool:
        url = f"{self.base}/actuator/health"
        deadline = time.time() + self.args.wait
        while time.time() < deadline:
            try:
                body = http_json("GET", url, timeout=3)
                if body and body.get("status") == "UP":
                    return True
            except Exception:
                pass
            time.sleep(2)
        return False

    def step_profile(self) -> dict:
        url = self.ucp(self.merchant, "/.well-known/ucp")
        self.step("Profile Discovery", curl_of("GET", url))
        profile = http_json("GET", url)
        keys = profile.get("keys") or []
        if not self.check(bool(keys), f"keys {len(keys)}개", "Profile 에 keys 가 없다"):
            return profile
        jwk = keys[0]
        signing = [k for k in keys if k.get("alg") == "ES256" and k.get("crv") == "P-256"]
        self.check(bool(signing), f"ES256 P-256 키 kid={jwk.get('kid')}",
                   f"ES256 P-256 공개키가 없다. keys={keys}")
        if signing:
            self.public_key = jwk_public_key(signing[0])
            self.kid = signing[0].get("kid")
        services = (profile.get("ucp") or {}).get("services") or {}
        rest = (services.get(SHOPPING_SERVICE) or [{}])[0]
        self.check(rest.get("transport") == "rest",
                   f"{SHOPPING_SERVICE} transport=rest endpoint={rest.get('endpoint')}",
                   f"{SHOPPING_SERVICE} 의 transport 가 rest 가 아니다. {rest}")
        self.check(self.merchant in (rest.get("endpoint") or ""),
                   "endpoint 에 몰 세그먼트가 있다",
                   f"endpoint 에 {self.merchant} 가 없다. {rest.get('endpoint')}")
        return profile

    def step_payment_handler(self, profile: dict) -> None:
        self.step("Payment Handler 광고", "(1번 응답의 ucp.payment_handlers)")
        handlers = (profile.get("ucp") or {}).get("payment_handlers") or {}
        declared = handlers.get(PAYMENT_HANDLER_NAMESPACE) or []
        self.check(bool(declared) and declared[0].get("id") == PAYMENT_HANDLER_ID,
                   f"{PAYMENT_HANDLER_NAMESPACE} → {declared}",
                   f"{PAYMENT_HANDLER_NAMESPACE} 광고가 없거나 id 가 다르다. {handlers}")

    def step_ap2_like(self, profile: dict) -> None:
        self.step("AP2-like Capability 광고", "(1번 응답의 ucp.capabilities)")
        capabilities = (profile.get("ucp") or {}).get("capabilities") or {}
        if AP2_LIKE_NAMESPACE in capabilities:
            self.ok(f"{AP2_LIKE_NAMESPACE} → {capabilities[AP2_LIKE_NAMESPACE]}")
        else:
            self.skip("AP2-like 가 꺼져 있다. MERCHANT_AP2_LIKE_ENABLED=true 로 띄우면 검증된다")
        missing = [c for c in ("dev.ucp.shopping.catalog.search", "dev.ucp.shopping.checkout",
                              "dev.ucp.shopping.order") if c not in capabilities]
        self.check(not missing, f"쇼핑 Capability {len(capabilities)}종",
                   f"빠진 Capability: {missing}")

    def step_catalog(self) -> dict:
        search_url = self.ucp(self.merchant, "/catalog/search")
        body = {"query": self.args.query}
        self.step("카탈로그 검색", curl_of("POST", search_url, body))
        items = (http_json("POST", search_url, body) or {}).get("items") or []
        if not self.check(bool(items), f"{len(items)}건", "검색 결과가 0건이다. 시드가 비어 있다"):
            return {}
        in_stock = [i for i in items if i.get("availability") == "IN_STOCK"]
        if not self.check(bool(in_stock), f"IN_STOCK {len(in_stock)}건", "구매 가능한 상품이 없다"):
            return {}

        product_url = self.ucp(self.merchant, "/catalog/product")
        for item in in_stock[:20]:
            request = {"productId": item["productId"]}
            detail = http_json("POST", product_url, request)
            options = detail.get("options") or []
            if options and (options[0].get("values") or []):
                self.step_no -= 1  # 같은 단계 안의 두 번째 호출이다
                self.step("카탈로그 상세", curl_of("POST", product_url, request))
                sku = options[0]["values"][0]["optionValueId"]
                self.ok(f"{detail['title']} {detail['price']['amount']}{detail['price']['currency']}"
                        f" option={options[0]['name']}/{sku}")
                return {"productId": detail["productId"], "sku": sku,
                        "price": detail["price"]["amount"], "title": detail["title"]}
        self.fail("옵션이 있는 IN_STOCK 상품을 찾지 못했다")
        return {}

    def checkout_body(self, product: dict, purchase_request_id: str = None,
                      postal_code: str = "06236") -> dict:
        body = {
            "line_items": [{
                "product_id": product["productId"],
                "option_value_ids": [product["sku"]],
                "quantity": 1,
            }],
            "fulfillment": {
                "type": "shipping",
                "destination": {
                    "recipient": "김호준",
                    "postal_code": postal_code,
                    "address_line1": "서울 강남구 테헤란로 212",
                    "address_line2": "멀티캠퍼스 역삼",
                },
            },
        }
        if purchase_request_id:
            body["purchase_request_id"] = purchase_request_id
        return body

    def step_create_checkout(self, product: dict) -> str:
        url = self.ucp(self.merchant, "/checkout-sessions")
        body = self.checkout_body(product)
        self.step("체크아웃 생성 (purchase_request_id 없이 → incomplete)", curl_of("POST", url, body))
        checkout = http_json("POST", url, body)
        codes = [m.get("code") for m in (checkout.get("messages") or [])]
        self.check(checkout.get("status") == "incomplete",
                   f"id={checkout.get('id')} status=incomplete messages={codes}",
                   f"status 가 incomplete 가 아니다. {checkout.get('status')} messages={codes}")
        return checkout.get("id")

    def step_update_checkout(self, checkout_id: str, product: dict) -> dict:
        url = self.ucp(self.merchant, f"/checkout-sessions/{checkout_id}")
        purchase_request_id = f"pr-{uuid.uuid4()}"
        body = self.checkout_body(product, purchase_request_id)
        self.step("체크아웃 수정 (purchase_request_id 추가 → ready_for_complete)",
                  curl_of("PUT", url, body))
        checkout = http_json("PUT", url, body)
        codes = [m.get("code") for m in (checkout.get("messages") or [])]
        self.check(checkout.get("status") == "ready_for_complete",
                   f"status=ready_for_complete total={checkout.get('totals')}",
                   f"status 가 ready_for_complete 가 아니다. {checkout.get('status')} messages={codes}")
        self.check(bool(checkout.get("merchant_authorization")),
                   "merchant_authorization 발급됨",
                   "merchant_authorization 이 없다")
        checkout["_purchaseRequestId"] = purchase_request_id
        return checkout

    def step_verify_authorization(self, checkout: dict) -> None:
        self.step("Merchant Authorization 서명 검증 (Profile 공개키로 ES256)",
                  "(6번 응답의 merchant_authorization 을 1번 응답의 keys[0] 로 검증)")
        jws = checkout.get("merchant_authorization")
        if not jws or self.public_key is None:
            self.fail("검증할 JWS 또는 공개키가 없다")
            return
        parts = jws.split(".")
        if not self.check(len(parts) == 3, "Compact JWS 세 조각", f"JWS 모양이 아니다. {jws[:40]}"):
            return
        header = json.loads(b64url_decode(parts[0]))
        claims = json.loads(b64url_decode(parts[1]))
        signing_input = f"{parts[0]}.{parts[1]}".encode("ascii")
        self.check(header.get("alg") == "ES256" and header.get("kid") == self.kid,
                   f"헤더 alg=ES256 kid={header.get('kid')}",
                   f"헤더가 다르다. {header}")
        self.check(verify_es256(self.public_key, signing_input, b64url_decode(parts[2])),
                   "ES256 서명 검증 통과",
                   "ES256 서명 검증 실패")
        total = next((t["amount"] for t in (checkout.get("totals") or [])
                      if t.get("type") == "total"), None)
        self.check(claims.get("merchantId") == self.merchant
                   and claims.get("checkoutId") == checkout.get("id")
                   and claims.get("amountMinor") == total
                   and claims.get("purchaseRequestId") == checkout["_purchaseRequestId"],
                   f"claim 대조 통과 amountMinor={claims.get('amountMinor')}"
                   f" currency={claims.get('currency')}",
                   f"claim 이 체크아웃과 다르다. claims={claims} total={total}")

    def step_no_payment_yet(self) -> None:
        self.step("Pay 호출 0회 (불변 조건 1: Pay 성공 전 주문 없음)", "(스텁 카운터)")
        self.check(self.state.payment_count == 0,
                   "결제 전까지 Pay 호출 0회",
                   f"결제 전에 Pay 를 {self.state.payment_count}회 불렀다")

    def step_complete(self, checkout_id: str) -> dict:
        url = self.ucp(self.merchant, f"/checkout-sessions/{checkout_id}/complete")
        credential_id = str(uuid.uuid4())
        body = {"payment": {"instrument": {"credential": {"credentialId": credential_id}}}}
        self.step("Checkout Complete", curl_of("POST", url, body))
        result = http_json("POST", url, body, headers={"Idempotency-Key": f"smoke-{credential_id}"})
        self.check(result.get("status") == "completed" and (result.get("order") or {}).get("id"),
                   f"status=completed order={(result.get('order') or {}).get('id')}"
                   f" payment={(result.get('payment') or {}).get('status')}",
                   f"Complete 가 완료되지 않았다. {result}")
        return result

    def step_payment_called_once(self, checkout: dict) -> None:
        self.step("Pay 호출 1회와 청구 본문 (불변 조건 2)", "(스텁이 받은 본문)")
        if not self.check(self.state.payment_count == 1,
                          "Pay 호출 1회",
                          f"Pay 호출이 {self.state.payment_count}회다"):
            return
        body = self.state.payments[0]["body"]
        total = next((t["amount"] for t in (checkout.get("totals") or [])
                      if t.get("type") == "total"), None)
        self.check(body.get("amountMinor") == total and body.get("merchantId") == self.merchant
                   and body.get("businessCheckoutId") == checkout.get("id"),
                   f"merchantId={body.get('merchantId')} amountMinor={body.get('amountMinor')}"
                   f" currency={body.get('currency')}",
                   f"청구 본문이 체크아웃과 다르다. body={body} total={total}")
        digest = body.get("checkoutHash") or ""
        self.check(digest.startswith("sha256:") and len(digest) == 50,
                   f"checkoutHash={digest}",
                   f"checkoutHash 모양이 다르다. {digest!r}")
        headers = self.state.payments[0]["headers"]
        self.check(bool(headers.get("idempotency-key")) and bool(headers.get("x-request-id")),
                   f"Idempotency-Key={headers.get('idempotency-key')}"
                   f" X-Request-Id={headers.get('x-request-id')}",
                   f"필수 헤더가 빠졌다. {sorted(headers)}")

    def step_order(self, order_id: str) -> None:
        url = self.ucp(self.merchant, f"/orders/{order_id}")
        self.step("주문 조회", curl_of("GET", url))
        order = http_json("GET", url)
        self.check(order.get("id") == order_id,
                   f"id={order.get('id')} status={order.get('status')}",
                   f"주문 조회 결과가 다르다. {order}")

    def step_webhook(self, order_id: str) -> None:
        self.step("주문 웹훅 수신과 RFC 9421 서명 검증", "(스텁이 받은 요청)")
        deadline = time.time() + self.args.webhook_wait
        while self.state.webhook_count == 0 and time.time() < deadline:
            time.sleep(0.5)
        if not self.check(self.state.webhook_count >= 1,
                          f"웹훅 {self.state.webhook_count}건 수신",
                          f"{self.args.webhook_wait}초 동안 웹훅이 오지 않았다. "
                          f"CORE_WEBHOOK_URL 이 스텁을 가리키는지 본다"):
            return
        record = self.state.webhooks[0]
        headers = record["headers"]
        body = record["body"]

        digest = "sha-256=:" + base64.b64encode(hashlib.sha256(body).digest()).decode() + ":"
        self.check(headers.get("content-digest") == digest,
                   "Content-Digest 가 본문과 일치",
                   f"Content-Digest 불일치. 헤더={headers.get('content-digest')} 계산={digest}")
        self.check(bool(headers.get("webhook-id")),
                   f"Webhook-Id={headers.get('webhook-id')}",
                   "Webhook-Id 가 없다")

        signature_input = headers.get("signature-input") or ""
        signature = headers.get("signature") or ""
        matched_input = SIGNATURE_INPUT_RE.match(signature_input)
        matched_signature = SIGNATURE_RE.match(signature)
        if not self.check(bool(matched_input) and bool(matched_signature),
                          "Signature-Input·Signature 형식 통과",
                          f"서명 헤더 형식이 다르다. input={signature_input!r} sig={signature!r}"):
            return
        self.check(matched_input.group("components") == SIGNED_COMPONENTS
                   and matched_input.group("keyid") == self.kid,
                   f"컴포넌트 다섯, keyid={matched_input.group('keyid')}",
                   f"컴포넌트 또는 keyid 가 다르다. {signature_input}")

        authority = f"{self.args.stub_host}:{self.args.stub_port}"
        base = "\n".join([
            '"@method": POST',
            f'"@authority": {authority}',
            f'"@path": {record["path"]}',
            f'"content-digest": {headers.get("content-digest")}',
            f'"content-type": {headers.get("content-type")}',
            f'"@signature-params": {signature_input.split("=", 1)[1]}',
        ])
        raw = base64.b64decode(matched_signature.group("value"))
        self.check(verify_es256(self.public_key, base.encode("utf-8"), raw),
                   "RFC 9421 서명 검증 통과",
                   f"RFC 9421 서명 검증 실패. base=\n{base}")
        payload = json.loads(body)
        self.check(order_id in json.dumps(payload, ensure_ascii=False),
                   f"본문에 orderId={order_id}",
                   f"웹훅 본문에 주문이 없다. {payload}")

    def step_complete_again(self, checkout_id: str, first: dict) -> None:
        url = self.ucp(self.merchant, f"/checkout-sessions/{checkout_id}/complete")
        body = {"payment": {"instrument": {"credential": {"credentialId": str(uuid.uuid4())}}}}
        self.step("Complete 재호출 (불변 조건 3: 기존 주문 반환, 재청구 없음)",
                  curl_of("POST", url, body))
        again = http_json("POST", url, body)
        self.check((again.get("order") or {}).get("id") == (first.get("order") or {}).get("id"),
                   f"같은 주문 {(again.get('order') or {}).get('id')} 재생",
                   f"다른 주문이 나왔다. 처음={first.get('order')} 다시={again.get('order')}")
        self.check(self.state.payment_count == 1,
                   "Pay 호출은 1회 그대로",
                   f"재호출이 Pay 를 다시 불렀다. 총 {self.state.payment_count}회")

    def set_scenario(self, name: str) -> None:
        url = f"{self.base}/internal/v1/scenarios"
        http_json("POST", url, {"scenario": name})

    def step_price_changed(self, product: dict) -> None:
        """PRICE_CHANGED 는 막는 시나리오가 아니다. PUT 에서 직전 단가와 비교해 경고를 얹는다(7.1절)."""
        url = self.ucp(self.merchant, "/checkout-sessions")
        self.step("시나리오 PRICE_CHANGED (막지 않는다. PUT 재계산에 경고를 얹는다)",
                  f"curl -s -X POST {self.base}/internal/v1/scenarios"
                  f" -H 'Content-Type: application/json' -d '{{\"scenario\":\"PRICE_CHANGED\"}}'")
        body = self.checkout_body(product, f"pr-{uuid.uuid4()}")
        first = http_json("POST", url, body)
        before_total = next((t["amount"] for t in (first.get("totals") or [])
                             if t.get("type") == "total"), None)
        try:
            self.set_scenario("PRICE_CHANGED")
            updated = http_json("PUT", f"{url}/{first['id']}", body)
        finally:
            self.set_scenario("NORMAL")
        messages = updated.get("messages") or []
        codes = [m.get("code") for m in messages]
        after_total = next((t["amount"] for t in (updated.get("totals") or [])
                            if t.get("type") == "total"), None)
        self.check("CHECKOUT_PRICE_CHANGED" in codes,
                   f"PUT 재계산에 CHECKOUT_PRICE_CHANGED codes={codes}",
                   f"가격 변경 경고가 없다. codes={codes}")
        self.check(all(m.get("severity") == "warning" for m in messages
                       if m.get("code") == "CHECKOUT_PRICE_CHANGED")
                   and updated.get("status") == "ready_for_complete",
                   f"severity=warning, status 는 ready_for_complete 유지",
                   f"경고가 아니라 막았다. status={updated.get('status')} messages={messages}")
        self.check(after_total is not None and before_total is not None
                   and after_total > before_total,
                   f"총액 {before_total} → {after_total}",
                   f"단가가 오르지 않았다. {before_total} → {after_total}")

    def step_blocking_scenarios(self, product: dict) -> None:
        url = self.ucp(self.merchant, "/checkout-sessions")
        cases = [
            ("OUT_OF_STOCK", "CHECKOUT_STOCK_INSUFFICIENT", "incomplete"),
            ("ADDRESS_NOT_SERVICEABLE", "ADDRESS_NOT_SERVICEABLE", "incomplete"),
            ("REQUIRES_ESCALATION", "MERCHANT_HANDOFF_REQUIRED", "requires_escalation"),
        ]
        before = self.state.payment_count
        self.step("막는 시나리오 셋. Pay 호출 증가 0",
                  f"curl -s -X POST {self.base}/internal/v1/scenarios"
                  f" -H 'Content-Type: application/json' -d '{{\"scenario\":\"OUT_OF_STOCK\"}}'")
        try:
            for scenario, expected, expected_status in cases:
                self.set_scenario(scenario)
                body = self.checkout_body(product, f"pr-{uuid.uuid4()}")
                checkout = http_json("POST", url, body)
                codes = [m.get("code") for m in (checkout.get("messages") or [])]
                status = checkout.get("status")
                self.check(status == expected_status and expected in codes,
                           f"{scenario} → status={status} codes={codes}",
                           f"{scenario} 가 막지 못했다. status={status} codes={codes}")
        finally:
            self.set_scenario("NORMAL")
        self.check(self.state.payment_count == before,
                   f"Pay 호출 증가 0 (총 {self.state.payment_count}회)",
                   f"실패 시나리오가 Pay 를 {self.state.payment_count - before}회 더 불렀다")

    def step_malls(self, product: dict) -> None:
        self.step("가상 몰 셋 (393). 같은 productId, 몰마다 다른 가격",
                  curl_of("POST", self.ucp("merchant-b", "/catalog/product"),
                          {"productId": product["productId"]}))
        prices = {}
        for mall in ("merchant-a", "merchant-b", "merchant-c"):
            try:
                detail = http_json("POST", self.ucp(mall, "/catalog/product"),
                                   {"productId": product["productId"]})
                prices[mall] = detail["price"]["amount"]
            except HttpError as error:
                prices[mall] = f"HTTP {error.status}"
        found = [m for m, p in prices.items() if isinstance(p, int)]
        self.check(len(found) >= 2, f"{prices}",
                   f"몰 하나에서만 잡힌다. {prices}. 몰 셋 시드로 띄웠는지 본다")
        try:
            http_json("POST", self.ucp("merchant-zzz", "/catalog/search"), {"query": ""})
            self.fail("모르는 몰이 404 가 아니다")
        except HttpError as error:
            self.check(error.status == 404, "모르는 몰은 404",
                       f"모르는 몰이 {error.status} 다")

    def step_legacy_alias(self) -> None:
        url = f"{self.base}/.well-known/ucp"
        self.step("옛 경로 별칭 (Core·Pay 가 아직 쓰는 주소)", curl_of("GET", url))
        profile = http_json("GET", url)
        endpoint = (((profile.get("ucp") or {}).get("services") or {})
                    .get(SHOPPING_SERVICE) or [{}])[0].get("endpoint")
        self.check("merchant-a" in (endpoint or ""),
                   f"/.well-known/ucp → merchant-a ({endpoint})",
                   f"별칭이 merchant-a 로 붙지 않는다. {endpoint}")
        search = f"{self.base}/ucp/v1/catalog/search"
        items = (http_json("POST", search, {"query": self.args.query}) or {}).get("items") or []
        self.check(bool(items), f"/ucp/v1/catalog/search → {len(items)}건",
                   "옛 카탈로그 경로가 비었다")

    # ---- 진행

    def run(self) -> int:
        print(f"대상 {self.base}  몰 {self.merchant}  스텁 "
              f"http://{self.args.stub_host}:{self.args.stub_port}")
        if not self.wait_for_app():
            print(f"\n앱이 {self.base} 에서 응답하지 않는다. 아래로 띄운 뒤 다시 돌린다.\n")
            print(launch_command(self.args))
            return 2

        product = {}
        try:
            profile = self.step_profile()
            self.step_payment_handler(profile)
            self.step_ap2_like(profile)
            product = self.step_catalog()
            if not product:
                raise RuntimeError("카탈로그에서 상품을 고르지 못해 더 진행할 수 없다")
            checkout_id = self.step_create_checkout(product)
            checkout = self.step_update_checkout(checkout_id, product)
            self.step_verify_authorization(checkout)
            self.step_no_payment_yet()
            result = self.step_complete(checkout_id)
            self.step_payment_called_once(checkout)
            order_id = (result.get("order") or {}).get("id")
            if order_id:
                self.step_order(order_id)
                self.step_webhook(order_id)
            self.step_complete_again(checkout_id, result)
            self.step_price_changed(product)
            self.step_blocking_scenarios(product)
            if not self.args.skip_malls:
                self.step_malls(product)
            self.step_legacy_alias()
        except HttpError as error:
            self.fail(f"HTTP 오류로 중단: {error}")
        except Exception as error:  # noqa: BLE001 - 스모크는 어떤 실패든 보고하고 끝낸다
            self.fail(f"{type(error).__name__}: {error}")

        print("\n" + "=" * 72)
        print(f"단계 {self.step_no}개, 실패 {len(self.failures)}개, 건너뜀 {len(self.skipped)}개")
        print(f"Pay 청구 {self.state.payment_count}회, 재조회 {len(self.state.lookups)}회, "
              f"웹훅 {self.state.webhook_count}건")
        for line in self.failures:
            print(f"  FAIL {line}")
        for line in self.skipped:
            print(f"  SKIP {line}")
        return 1 if self.failures else 0


def launch_command(args) -> str:
    stub = f"http://{args.stub_host}:{args.stub_port}"
    return (
        "# 1) DB 만 컨테이너로\n"
        "docker compose up -d merchant-db\n\n"
        "# 2) 앱은 호스트에서. backend/ 에서 친다\n"
        'export JAVA_HOME="/c/Users/SSAFY/.jdks/jdk-21.0.12+8"\n'
        'export PATH="$JAVA_HOME/bin:$PATH"\n'
        "MERCHANT_DB_URL=jdbc:postgresql://localhost:15434/merchant-db \\\n"
        "MERCHANT_DB_USER=merchant MERCHANT_DB_PASSWORD=merchant-dev-password \\\n"
        f"PAY_SERVICE_URL={stub} \\\n"
        f"CORE_SERVICE_URL={stub} \\\n"
        f"CORE_WEBHOOK_URL={stub}{WEBHOOK_PATH} \\\n"
        "PAY_MERCHANT_TOKEN=local-smoke-token \\\n"
        "MERCHANT_AP2_LIKE_ENABLED=true \\\n"
        "MERCHANT_CATALOG_POOL_FILE=classpath:catalog/pool.json.gz \\\n"
        "MERCHANT_CATALOG_POOL_LIMIT=5000 \\\n"
        "./gradlew :mocks:ucp-merchant-mock:bootRun"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="UCP Merchant Mock 단독 E2E 스모크")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--merchant", default="merchant-a")
    parser.add_argument("--stub-host", default="127.0.0.1")
    parser.add_argument("--stub-port", type=int, default=18099)
    parser.add_argument("--query", default="", help="카탈로그 검색어. 비우면 전량")
    parser.add_argument("--wait", type=int, default=90, help="앱 기동을 기다리는 초")
    parser.add_argument("--webhook-wait", type=int, default=30, help="웹훅 도착을 기다리는 초")
    parser.add_argument("--skip-malls", action="store_true", help="몰 셋 확인을 건너뛴다")
    parser.add_argument("--print-launch", action="store_true", help="앱 기동 명령만 찍고 끝낸다")
    args = parser.parse_args()

    if args.print_launch:
        print(launch_command(args))
        return 0

    server, state = start_stub(args.stub_host, args.stub_port)
    try:
        return Smoke(args, state).run()
    finally:
        server.shutdown()


if __name__ == "__main__":
    sys.exit(main())
