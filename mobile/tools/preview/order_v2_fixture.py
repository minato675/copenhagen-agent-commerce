#!/usr/bin/env python3
"""Loopback-only order v2 scenarios for the isolated preview APK.

The first two orders include image URLs captured in their item snapshots. The
third is an older snapshot with stable merchant/product/variant IDs and no image;
it can resolve its thumbnail with one exact product-detail read. All assets,
identities and orders are synthetic. Existing purchase/approval/v1 routes are
inherited from history_image_fixture without changing that fixture.

Run: python3 tools/preview/order_v2_fixture.py [--legacy-only]
The preview APK points at 127.0.0.1:8787 through the usual adb reverse mapping.
"""
import argparse
from http.server import ThreadingHTTPServer
from urllib.parse import unquote

import fixture_server as fixture
import history_image_fixture as history
import product_image_fixture as images


VARIANT_ID = "navy"
ORDERS_V2 = "/api/core/v2/orders"
PRODUCT_DETAIL = "/api/core/v1/products/"


class OrderV2Fixtures(history.HistoryFixtures):
    def __init__(self, legacy_only=False):
        self.legacy_only = legacy_only
        super().__init__()

    def order_v2(self, index):
        value = self.history_order(index)
        product = history.PRODUCTS[index]
        merchant = history.MERCHANTS[index % len(history.MERCHANTS)]
        value["items"] = [dict(item,
            merchantId=merchant["merchantId"], merchantProductId=product["productId"],
            merchantVariantId=VARIANT_ID, imageUrl=images.IMAGE_URL if index < 2 else None)
            for item in value["items"]]
        return value


def product_result(product_id, merchant, option_id):
    """Match Core's ProductDetailResult request echo and option-selection states."""
    request = dict(productId=product_id, merchantId=merchant["merchantId"], optionId=option_id)
    product = next((row for row in history.PRODUCTS if row["productId"] == product_id), None)
    detail = None
    if product is not None:
        selection = "REQUIRED" if option_id is None else "VALID" if option_id == VARIANT_ID else "INVALID"
        valid = selection == "VALID"
        detail = dict(
            productId=product_id, name=product["name"], brand="Copenhagen", category="가방",
            imageUrl=images.IMAGE_URL, description="로컬 화면 확인을 위한 합성 코튼 토트백",
            specs=[dict(name="소재", value="코튼 100%")],
            offer=dict(merchantId=merchant["merchantId"], merchantName=merchant["name"],
                       price=product["price"] if valid else None, currency="KRW", availability="IN_STOCK"),
            variants=[dict(optionId=VARIANT_ID, label="네이비 · 기본", price=product["price"],
                           availability="IN_STOCK", purchaseAllowed=True)],
            optionsComplete=valid, optionsRequired=True, selectionStatus=selection,
            selectedOptionId=VARIANT_ID if valid else None, stockQuantity=8,
            shipping=dict(status="AVAILABLE", fee=0, description="무료 배송"))
    return dict(data=dict(type="catalog.detail", source="LIVE", status="FOUND" if detail else "NOT_FOUND",
                          request=request, product=detail, checkedAt=fixture.STAMP))


class Handler(history.Handler):
    def route(self, method, path, query, body):
        state = fixture.STATE
        if method == "GET" and (path == ORDERS_V2 or path.startswith(ORDERS_V2 + "/")):
            if state.legacy_only:
                # A missing route has no domain error code; an unknown order below does.
                return dict(status=404, error="Not Found", path=path), 404
            if path == ORDERS_V2:
                values = [] if state.config["empty"] else [state.order_v2(i) for i in range(3)]
                if query.get("status"):
                    values = [value for value in values if value["status"] == query["status"]]
                try:
                    start, size = int(query.get("cursor", 0)), int(query.get("size", 20))
                except ValueError:
                    return dict(code="INVALID_REQUEST"), 400
                if start < 0 or not 1 <= size <= 100:
                    return dict(code="INVALID_REQUEST"), 400
                result = dict(items=values[start:start + size])
                if start + size < len(values):
                    result["nextCursor"] = str(start + size)
                return result, 200
            order_id = path.removeprefix(ORDERS_V2 + "/")
            index = next((i for i in range(3) if history.reference("order", i) == order_id), None)
            if index is None:
                return dict(code="ORDER_NOT_FOUND"), 404
            return state.order_v2(index), 200
        if method == "GET" and path.startswith(PRODUCT_DETAIL):
            merchant_id = query.get("merchantId")
            merchant = next((row for row in history.MERCHANTS if row["merchantId"] == merchant_id), None)
            if merchant is None:
                return dict(code="INVALID_REQUEST"), 400
            product_id = unquote(path.removeprefix(PRODUCT_DETAIL))
            return product_result(product_id, merchant, query.get("optionId") or None), 200
        return super().route(method, path, query, body)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-only", action="store_true",
                        help="Return uncoded v2 route 404s while keeping v1 history available.")
    args = parser.parse_args()
    fixture.STATE = OrderV2Fixtures(legacy_only=args.legacy_only)
    server = ThreadingHTTPServer(("127.0.0.1", 8787), Handler)
    print("Order v2 fixtures: http://127.0.0.1:8787 (isolated preview APK only)", flush=True)
    server.serve_forever()
