#!/usr/bin/env python3
"""History image regressions for the isolated preview APK, using synthetic assets.

Three purchases reference three approvals, but the Pay list exposes only the latest
approval. The other two must be read by their IDs without opening purchase detail.
Orders deliberately carry only name/option/quantity, matching the current Core
order API. Catalog results repeat product IDs across two different merchants.

Run with --related-results 100 to return 100 products per merchant for each exact
title query: 200 results / ten public API pages for an unscoped order lookup.
"""
import argparse
import uuid
from http.server import ThreadingHTTPServer

import fixture_server as fixture
import product_image_fixture as images


MERCHANTS = (
    dict(merchantId="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", name="코펜하겐 스토어"),
    dict(merchantId="merchant-two", name="노르딕 문구점"),
)
PRODUCTS = (
    dict(productId="dddddddd-dddd-4ddd-8ddd-dddddddddddd", name="에브리데이 토트백", price=42800),
    dict(productId="portrait-two", name="롱 스트랩 코튼 토트백", price=38500),
    dict(productId="portrait-three", name="미니 코튼 토트백", price=24900),
)
RELATED_RESULTS = 0


def reference(kind, index):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"copenhagen-preview/history-images/{kind}/{index}"))


class HistoryFixtures(fixture.Fixtures):
    def reset(self):
        super().reset()
        self.current_status = "APPROVED"

    def history_detail(self, index):
        product = PRODUCTS[index]
        merchant = MERCHANTS[index % len(MERCHANTS)]
        return dict(
            approvalId=reference("approval", index), totalAmountMinor=product["price"],
            currency="KRW", status="APPROVED", requiredAction="PIN", expiresAt=self.expiry,
            selectedPaymentMethodId=self.selected_card, biometricChallenge=None,
            merchants=[dict(
                merchantId=merchant["merchantId"], merchantName=merchant["name"],
                checkoutId=reference("checkout", index), checkoutHash=f"fixture-history-hash-{index}",
                subtotalAmountMinor=product["price"],
                summary=[dict(name=product["name"], option="네이비 · 기본", quantity=1,
                              amountMinor=product["price"])])])

    def history_list_row(self, index):
        value = self.history_detail(index)
        row = {key: value[key] for key in (
            "approvalId", "totalAmountMinor", "currency", "status", "requiredAction", "expiresAt")}
        return dict(row, merchantName=value["merchants"][0]["merchantName"], merchantCount=1)

    def history_purchase(self, index):
        detail = self.history_detail(index)
        merchant = detail["merchants"][0]
        return dict(
            purchaseRequestId=reference("purchase", index), status="COMPLETED",
            cartId=reference("cart", index), cartVersion=index + 1, expiresAt=self.expiry,
            nextAction="SHOW_RESULT", failureCode=None,
            merchantCheckouts=[dict(
                merchantId=merchant["merchantId"], approvalId=detail["approvalId"],
                status="COMPLETED", totalAmount=merchant["subtotalAmountMinor"], currency="KRW")])

    def history_order(self, index):
        product = PRODUCTS[index]
        return dict(
            orderId=reference("order", index), title=product["name"],
            status=("CONFIRMED", "SHIPPED", "DELIVERED")[index], amountMinor=product["price"],
            currency="KRW", createdAt=f"2026-09-{23 - index:02d}T09:30:00Z",
            items=[dict(name=product["name"], option="네이비 · 기본", quantity=1)],
            address=dict(recipient="테스트 사용자", phone="010-0000-0000", postalCode="03000",
                         address="서울시 테스트로 12", detail="테스트동 301호", memo="문 앞에 놓아 주세요"),
            paymentLabel="테스트 카드 •••• 4242")


def catalog_product(product, merchant):
    return dict(
        productId=product["productId"], name=product["name"], brand="Copenhagen", category="가방",
        imageUrl=images.IMAGE_URL,
        offers=[dict(merchantId=merchant["merchantId"], merchantName=merchant["name"],
                     price=product["price"], currency="KRW", availability="IN_STOCK")])


class Handler(images.Handler):
    def route(self, method, path, query, body):
        state = fixture.STATE
        if path == "/api/core/v1/purchase-requests" and method == "GET":
            if state.config["history_core_error"]:
                return dict(code="SERVICE_UNAVAILABLE"), 503
            return ([] if state.config["empty"] else [state.history_purchase(i) for i in range(3)]), 200
        if path.startswith("/api/core/v1/purchase-requests/") and method == "GET":
            if state.config["history_core_error"]:
                return dict(code="SERVICE_UNAVAILABLE"), 503
            index = next((i for i in range(3) if reference("purchase", i) == path.rsplit("/", 1)[1]), None)
            return ((state.history_purchase(index), 200) if index is not None else (dict(code="NOT_FOUND"), 404))
        if path == "/api/pay/v1/payment-approvals" and method == "GET":
            if state.config["history_pay_error"]:
                return dict(code="SERVICE_UNAVAILABLE"), 503
            latest = state.history_list_row(0)
            values = [] if state.config["empty"] or query.get("status", "APPROVED") != "APPROVED" else [latest]
            return dict(items=values), 200
        if path.startswith("/api/pay/v1/payment-approvals/") and method == "GET":
            if state.config["history_pay_error"]:
                return dict(code="SERVICE_UNAVAILABLE"), 503
            index = next((i for i in range(3) if reference("approval", i) == path.rsplit("/", 1)[1]), None)
            return ((state.history_detail(index), 200) if index is not None else (dict(code="NOT_FOUND"), 404))
        if path == "/api/core/v1/orders" and method == "GET":
            values = [] if state.config["empty"] else [state.history_order(i) for i in range(3)]
            if query.get("status"):
                values = [value for value in values if value["status"] == query["status"]]
            start, size = int(query.get("cursor", 0)), int(query.get("size", 20))
            response = dict(items=values[start:start + size])
            if start + size < len(values):
                response["nextCursor"] = str(start + size)
            return response, 200
        if path.startswith("/api/core/v1/orders/") and method == "GET":
            index = next((i for i in range(3) if reference("order", i) == path.rsplit("/", 1)[1]), None)
            return ((state.history_order(index), 200) if index is not None else (dict(code="NOT_FOUND"), 404))
        if path == "/api/core/v1/products/search" and method == "POST":
            search, merchant_id = body.get("query", ""), body.get("merchantId", "")
            merchants = [merchant for merchant in MERCHANTS if not merchant_id or merchant["merchantId"] == merchant_id]
            matches = [product for product in PRODUCTS if not search or search in product["name"]]
            products = []
            if not state.config["empty"]:
                for merchant in merchants:
                    rows = [catalog_product(product, merchant) for product in matches]
                    if search and rows and RELATED_RESULTS:
                        first = rows[0]
                        rows += [dict(first, productId=f"related-{index}", name=f"연관 토트 상품 {index}")
                                 for index in range(1, RELATED_RESULTS - len(rows) + 1)]
                    products.extend(rows)
            size, page = int(body.get("pageSize", 10)), int(body.get("page", 1))
            visible = products[(page - 1) * size:page * size]
            return dict(data=dict(
                status="OK" if visible else "EMPTY", products=visible, categories=["가방"],
                merchants=merchants, failedMerchants=[], total=len(products),
                hasNext=page * size < len(products), checkedAt=fixture.STAMP)), 200
        return super().route(method, path, query, body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--related-results", type=int, choices=(0, 100), default=0)
    RELATED_RESULTS = parser.parse_args().related_results
    fixture.STATE = HistoryFixtures()
    server = ThreadingHTTPServer(("127.0.0.1", 8787), Handler)
    print("History image fixtures: http://127.0.0.1:8787 (isolated preview APK only)", flush=True)
    server.serve_forever()
