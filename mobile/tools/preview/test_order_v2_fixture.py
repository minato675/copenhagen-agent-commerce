"""HTTP route checks for the synthetic order v2 harness; no emulator required."""
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

import fixture_server as fixture
import history_image_fixture as history
import order_v2_fixture as v2
import product_image_fixture as images


class OrderV2FixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.previous_state = fixture.STATE
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), v2.Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)
        fixture.STATE = cls.previous_state

    def setUp(self):
        fixture.STATE = v2.OrderV2Fixtures()
        fixture.STATE.config["latency_ms"] = 0

    def request(self, path, body=None):
        request = Request(self.base + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json"})
        try:
            response = urlopen(request, timeout=2)
        except HTTPError as failure:
            response = failure
        with response:
            return response.code, json.loads(response.read())

    def read(self, path):
        status, result = self.request(path)
        self.assertEqual(status, 200)
        return result

    def product_path(self, item, **overrides):
        query = dict(merchantId=item["merchantId"], optionId=item["merchantVariantId"])
        query.update(overrides)
        return v2.PRODUCT_DETAIL + item["merchantProductId"] + "?" + urlencode(query)

    def test_snapshots_have_stable_ids_and_two_captured_images(self):
        orders = self.read(v2.ORDERS_V2)["items"]
        self.assertEqual([row["orderId"] for row in orders], [history.reference("order", i) for i in range(3)])
        self.assertEqual([row["items"][0]["imageUrl"] for row in orders], [images.IMAGE_URL, images.IMAGE_URL, None])
        for index, order in enumerate(orders):
            item = order["items"][0]
            self.assertEqual(item["merchantId"], history.MERCHANTS[index % 2]["merchantId"])
            self.assertEqual(item["merchantProductId"], history.PRODUCTS[index]["productId"])
            self.assertEqual(item["merchantVariantId"], v2.VARIANT_ID)
            self.assertEqual(order["address"]["recipient"], "테스트 사용자")
            self.assertEqual(order["paymentLabel"], "테스트 카드 •••• 4242")

    def test_details_match_full_list_snapshots_including_address_and_payment(self):
        for summary in self.read(v2.ORDERS_V2)["items"]:
            detail = self.read(v2.ORDERS_V2 + "/" + summary["orderId"])
            self.assertEqual({key: detail[key] for key in summary}, summary)
            self.assertEqual(detail["address"]["recipient"], "테스트 사용자")
            self.assertEqual(detail["paymentLabel"], "테스트 카드 •••• 4242")
        status, error = self.request(v2.ORDERS_V2 + "/00000000-0000-4000-8000-000000000000")
        self.assertEqual((status, error["code"]), (404, "ORDER_NOT_FOUND"))

    def test_pagination_and_status_filter_keep_order_identity(self):
        first = self.read(v2.ORDERS_V2 + "?size=2")
        second = self.read(v2.ORDERS_V2 + "?size=2&cursor=" + first["nextCursor"])
        self.assertEqual([row["orderId"] for row in first["items"] + second["items"]],
                         [history.reference("order", i) for i in range(3)])
        self.assertNotIn("nextCursor", second)
        filtered = self.read(v2.ORDERS_V2 + "?status=SHIPPED&size=1")
        self.assertEqual([row["orderId"] for row in filtered["items"]], [history.reference("order", 1)])
        self.assertNotIn("nextCursor", filtered)
        for query in ("size=0", "cursor=-1", "cursor=wrong"):
            self.assertEqual(self.request(v2.ORDERS_V2 + "?" + query)[0], 400)

    def test_old_snapshot_resolves_from_one_exact_product_route_without_search(self):
        item = self.read(v2.ORDERS_V2)["items"][2]["items"][0]
        self.assertIsNone(item["imageUrl"])
        result = self.read(self.product_path(item))["data"]
        self.assertEqual(result["status"], "FOUND")
        self.assertEqual(result["request"], dict(productId=item["merchantProductId"],
                         merchantId=item["merchantId"], optionId=item["merchantVariantId"]))
        product = result["product"]
        self.assertEqual(product["productId"], item["merchantProductId"])
        self.assertEqual(product["offer"]["merchantId"], item["merchantId"])
        self.assertEqual(product["selectedOptionId"], item["merchantVariantId"])
        self.assertEqual(product["selectionStatus"], "VALID")
        self.assertEqual(product["imageUrl"], images.IMAGE_URL)
        self.assertEqual(product["offer"]["price"], product["variants"][0]["price"])
        self.assertEqual(fixture.STATE.requests["GET " + v2.PRODUCT_DETAIL + item["merchantProductId"]], 1)
        self.assertEqual(fixture.STATE.requests["POST /api/core/v1/products/search"], 0)

    def test_unknown_product_or_merchant_never_returns_another_product(self):
        item = self.read(v2.ORDERS_V2)["items"][2]["items"][0]
        missing = dict(item, merchantProductId="no-such-product")
        result = self.read(self.product_path(missing))["data"]
        self.assertEqual(result["status"], "NOT_FOUND")
        self.assertEqual(result["request"]["productId"], "no-such-product")
        self.assertIsNone(result["product"])
        status, result = self.request(self.product_path(item, merchantId="unknown-store"))
        self.assertEqual((status, result["code"]), (400, "INVALID_REQUEST"))

    def test_option_selection_echoes_missing_and_invalid_options(self):
        item = self.read(v2.ORDERS_V2)["items"][2]["items"][0]
        for option, expected in (("", "REQUIRED"), ("missing-option", "INVALID")):
            result = self.read(self.product_path(item, optionId=option))["data"]
            self.assertEqual(result["request"]["optionId"], option or None)
            self.assertEqual(result["product"]["selectionStatus"], expected)
            self.assertIsNone(result["product"]["selectedOptionId"])
            self.assertIsNone(result["product"]["offer"]["price"])
            self.assertFalse(result["product"]["optionsComplete"])

    def test_legacy_mode_has_uncoded_route_404_and_unchanged_v1_orders(self):
        fixture.STATE.legacy_only = True
        for path in (v2.ORDERS_V2, v2.ORDERS_V2 + "/" + history.reference("order", 0)):
            status, result = self.request(path)
            self.assertEqual(status, 404)
            self.assertNotIn("code", result)
        orders = self.read("/api/core/v1/orders")["items"]
        self.assertEqual(len(orders), 3)
        for order in orders:
            self.assertNotIn("imageUrl", order["items"][0])
            self.assertNotIn("merchantProductId", order["items"][0])
        status, _ = self.request("/__control/reset", {})
        self.assertEqual(status, 200)
        self.assertEqual(self.request(v2.ORDERS_V2)[0], 404)

    def test_empty_orders_and_existing_purchase_routes_remain_available(self):
        purchases = self.read("/api/core/v1/purchase-requests")
        self.assertEqual(len(purchases), 3)
        self.assertEqual(len(self.read("/api/pay/v1/payment-approvals")["items"]), 1)
        fixture.STATE.config["empty"] = True
        self.assertEqual(self.read(v2.ORDERS_V2), dict(items=[]))

    def test_image_is_a_loopback_synthetic_png(self):
        self.assertEqual(urlsplit(images.IMAGE_URL).hostname, "127.0.0.1")
        with urlopen(self.base + urlsplit(images.IMAGE_URL).path, timeout=2) as response:
            self.assertEqual(response.headers["Content-Type"], "image/png")
            self.assertEqual(response.read(), images.PNG)


if __name__ == "__main__":
    unittest.main()
