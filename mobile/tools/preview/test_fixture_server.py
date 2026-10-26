"""HTTP-level checks for safe, authoritative stress fixtures (no emulator required)."""
import json
import base64
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import fixture_server as fixture


class FixtureContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), fixture.Handler)
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        fixture.STATE.reset()
        fixture.STATE.config["latency_ms"] = 0

    def request(self, path, body=None, key=None, device=None, method=None):
        headers = {"Content-Type": "application/json"}
        if key: headers["Idempotency-Key"] = key
        if device: headers["X-Device-Id"] = device
        request = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=2) as response:
            return json.loads(response.read() or b"null")

    def enroll(self, device="preview-device"):
        private_key = subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256"],
            capture_output=True, check=True, timeout=5).stdout
        public_key = subprocess.run(["openssl", "pkey", "-pubout", "-outform", "DER"],
            input=private_key, capture_output=True, check=True, timeout=5).stdout
        response = self.request("/api/pay/v1/users/me/biometric-credentials", dict(deviceId=device,
            publicKey=base64.urlsafe_b64encode(public_key).decode().rstrip("="), credentialType="PLATFORM_BIOMETRIC"))
        return private_key, response

    def biometric_body(self, private_key, challenge):
        with tempfile.TemporaryDirectory(prefix="copenhagen-fixture-test-") as directory:
            key_path = Path(directory) / "test-key.pem"
            key_path.write_bytes(private_key)
            signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(key_path)],
                input=challenge["challenge"].encode(), capture_output=True, check=True, timeout=5).stdout
        return dict(authentication=dict(method="BIOMETRIC", challengeId=challenge["challengeId"],
            assertion=base64.urlsafe_b64encode(signature).decode().rstrip("=")))

    def approve(self, key="logical-operation-1"):
        return self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111/approve", {"authentication": {"method": "PIN", "pin": "123456"}}, key)

    def test_duplicate_key_is_one_logical_operation(self):
        self.assertEqual(self.approve(), self.approve())
        self.assertEqual(fixture.STATE.approve_requests, 2)
        self.assertEqual(fixture.STATE.logical_approvals, 1)
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.approve("new-key-after-success")
        self.assertEqual(error.exception.code, 409)
        error.exception.close()
        self.assertEqual(fixture.STATE.logical_approvals, 1)

    def test_approved_is_not_completed(self):
        self.assertEqual(self.approve()["status"], "APPROVED")
        self.assertEqual(self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111")["status"], "APPROVED")

    def test_unfiltered_history_includes_all_approval_states(self):
        rows = self.request("/api/pay/v1/payment-approvals")["items"]
        self.assertEqual({row["status"] for row in rows}, {"PENDING", "APPROVED"})
        self.assertEqual(len(rows), 2)
        self.assertTrue(all("totalAmountMinor" in row and "merchantCount" in row for row in rows))

    def test_pay_history_outage_preserves_core_outcome(self):
        fixture.STATE.current_status = "APPROVED"
        fixture.STATE.config.update(merchant_count=2, checkout_statuses=["COMPLETED", "FAILED"], history_pay_error=True)
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request("/api/pay/v1/payment-approvals")
        self.assertEqual(error.exception.code, 503)
        error.exception.close()
        rows = self.request("/api/core/v1/purchase-requests")
        self.assertEqual(rows[0]["status"], "PARTIALLY_COMPLETED")
        self.assertEqual(sum(c["totalAmount"] for c in rows[0]["merchantCheckouts"] if c["status"] == "COMPLETED"), 24300)

    def test_recent_history_empty_and_failure_are_distinct(self):
        fixture.STATE.current_status = "APPROVED"
        fixture.STATE.config["empty"] = True
        self.assertEqual(self.request("/api/core/v1/purchase-requests"), [])
        fixture.STATE.config["history_core_error"] = True
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request("/api/core/v1/purchase-requests")
        self.assertEqual(error.exception.code, 503)
        error.exception.close()

    def test_disconnect_after_acceptance_reconciles(self):
        fixture.STATE.config["mode"] = "disconnect"
        with self.assertRaises(Exception):
            self.approve()
        self.assertEqual(self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111")["status"], "APPROVED")
        self.assertEqual(fixture.STATE.logical_approvals, 1)

    def test_offline_never_accepts_an_operation(self):
        fixture.STATE.config["mode"] = "offline"
        with self.assertRaises(Exception):
            self.approve()
        self.assertEqual(fixture.STATE.logical_approvals, 0)

    def test_invalid_pin_does_not_mutate(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111/approve", {"authentication": {"method": "PIN", "pin": "000000"}}, "bad-pin")
        self.assertEqual(error.exception.code, 422)
        error.exception.close()
        self.assertEqual(fixture.STATE.current_status, "PENDING")
        self.assertEqual(fixture.STATE.logical_approvals, 0)

    def test_definitive_failure_does_not_accept_a_payment(self):
        fixture.STATE.config["mode"] = "failed"
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.approve()
        self.assertEqual(error.exception.code, 422)
        error.exception.close()
        self.assertEqual(fixture.STATE.current_status, "PENDING")
        self.assertEqual(fixture.STATE.logical_approvals, 0)

    def test_activity_and_detail_preserve_identity_and_amount(self):
        row = self.request("/api/pay/v1/payment-approvals?status=APPROVED")["items"][0]
        detail = self.request("/api/pay/v1/payment-approvals/" + row["approvalId"])
        for field in ("approvalId", "totalAmountMinor", "currency", "status"):
            self.assertEqual(row[field], detail[field])
        self.assertNotIn("checkoutId", row)
        self.assertNotIn("merchantName", detail)
        self.assertEqual(row["merchantName"], detail["merchants"][0]["merchantName"])
        self.assertEqual(row["merchantCount"], len(detail["merchants"]))

    def test_grouped_payment_completion_does_not_complete_the_approval_session(self):
        fixture.STATE.config.update(merchant_count=2, checkout_statuses=["COMPLETED", "FAILED"])
        self.assertEqual(self.approve()["status"], "APPROVED")
        detail = self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111")
        self.assertEqual(detail["status"], "APPROVED")
        self.assertEqual(sum(m["subtotalAmountMinor"] for m in detail["merchants"]), detail["totalAmountMinor"])
        purchase = self.request("/api/core/v1/purchase-requests")[0]
        self.assertEqual(purchase["status"], "PARTIALLY_COMPLETED")
        self.assertEqual(len({c["approvalId"] for c in purchase["merchantCheckouts"]}), 1)
        self.assertEqual(purchase, self.request("/api/core/v1/purchase-requests/" + purchase["purchaseRequestId"]))

    def test_unimplemented_routes_do_not_fabricate_success(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request("/api/pay/v1/unsupported")
        self.assertEqual(error.exception.code, 404)
        error.exception.close()

    def test_order_fixture_matches_core_address_snapshot_and_status_filters(self):
        row = self.request("/api/core/v1/orders")["items"][0]
        detail = self.request("/api/core/v1/orders/" + row["orderId"])
        self.assertEqual(row, detail)
        self.assertEqual(row["status"], "CONFIRMED")
        self.assertIsInstance(row["address"], dict)
        self.assertEqual(set(row["address"]), {"recipient", "phone", "postalCode", "address", "detail", "memo"})
        self.assertEqual(self.request("/api/core/v1/orders?status=CONFIRMED")["items"], [row])
        self.assertEqual(self.request("/api/core/v1/orders?status=SHIPPED")["items"], [])

    def test_device_biometric_registration_status_and_idempotent_revocation(self):
        path = "/api/pay/v1/users/me/biometric-credentials"
        self.assertFalse(self.request(path, device="preview-device")["registered"])
        _, registered = self.enroll()
        status = self.request(path, device="preview-device")
        self.assertTrue(status["registered"])
        self.assertEqual(status["credentialId"], registered["credentialId"])
        self.assertEqual(status["status"], "ACTIVE")
        self.assertFalse(self.request(path, device="other-device")["registered"])
        self.request(path, device="preview-device", method="DELETE")
        self.request(path, device="preview-device", method="DELETE")
        self.assertFalse(self.request(path, device="preview-device")["registered"])

    def test_real_ecdsa_signature_approves_once_without_device_header(self):
        private_key, _ = self.enroll()
        path = "/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111"
        detail = self.request(path, device="preview-device")
        self.assertEqual(detail["requiredAction"], "BIOMETRIC")
        challenge = detail["biometricChallenge"]
        self.assertEqual(challenge, self.request(path, device="preview-device")["biometricChallenge"])
        body = self.biometric_body(private_key, challenge)
        result = self.request(path + "/approve", body, "signed-operation")
        self.assertEqual(result["status"], "APPROVED")
        self.assertEqual(result, self.request(path + "/approve", body, "signed-operation"))
        stats = self.request("/__control/stats")
        self.assertEqual(stats["authentication_methods"], {"BIOMETRIC": 1})
        self.assertEqual(stats["logical_approvals"], 1)
        self.assertEqual(stats["events"][0]["authentication_method"], "BIOMETRIC")
        serialized = json.dumps(stats)
        for secret in (body["authentication"]["assertion"], challenge["challenge"], "publicKey", "private_key"):
            self.assertNotIn(secret, serialized)

    def test_bad_biometric_signature_never_approves_and_pin_fallback_succeeds(self):
        self.enroll()
        path = "/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111"
        challenge = self.request(path, device="preview-device")["biometricChallenge"]
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request(path + "/approve", dict(authentication=dict(method="BIOMETRIC", challengeId=challenge["challengeId"], assertion="invalid")), "bad-signature")
        self.assertEqual(error.exception.code, 422)
        error.exception.close()
        self.assertEqual(fixture.STATE.logical_approvals, 0)
        self.assertEqual(self.approve()["status"], "APPROVED")
        self.assertEqual(fixture.STATE.authentication_methods, {"PIN": 1})

    def test_cancelled_prompt_or_unregistered_device_never_approves(self):
        self.enroll()
        path = "/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111"
        self.assertIsNotNone(self.request(path, device="preview-device")["biometricChallenge"])
        self.assertIsNone(self.request(path, device="other-device")["biometricChallenge"])
        self.assertEqual(self.request(path, device="other-device")["requiredAction"], "PIN")
        self.assertEqual(fixture.STATE.approve_requests, 0)
        self.assertEqual(fixture.STATE.logical_approvals, 0)
        self.assertEqual(fixture.STATE.current_status, "PENDING")

    def test_revoked_credential_invalidates_issued_challenge(self):
        private_key, _ = self.enroll()
        path = "/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111"
        body = self.biometric_body(private_key, self.request(path, device="preview-device")["biometricChallenge"])
        self.request("/api/pay/v1/users/me/biometric-credentials", device="preview-device", method="DELETE")
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request(path + "/approve", body, "revoked-signature")
        self.assertEqual(error.exception.code, 422)
        error.exception.close()
        self.assertEqual(fixture.STATE.logical_approvals, 0)

    def test_multi_item_shipping_snapshot_matches_approval_amounts(self):
        self.request("/__control/config", dict(multi_item=True, merchant_count=2, purchase_needs_address=True))
        purchase = self.request("/api/core/v1/purchase-requests")[0]
        self.assertEqual(purchase["status"], "PENDING_ADDRESS")
        self.assertEqual(purchase["nextAction"], "SELECT_SHIPPING_ADDRESS")
        self.assertEqual(purchase["merchantCheckouts"], [])
        cart = self.request("/api/core/v1/cart")
        detail = self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111")
        self.assertEqual(cart["version"], purchase["cartVersion"])
        self.assertEqual(cart["cartId"], purchase["cartId"])
        self.assertEqual(len(cart["items"]), 4)
        self.assertEqual(len(detail["merchants"][0]["summary"]), 3)
        for merchant in detail["merchants"]:
            self.assertEqual(sum(i["amountMinor"] * i["quantity"] for i in merchant["summary"]), merchant["subtotalAmountMinor"])
        self.assertEqual(cart["summary"]["estimatedTotal"], detail["totalAmountMinor"])

    def test_pin_registration_and_change_update_only_private_fixture_state(self):
        path = "/api/pay/v1/users/me/payment-pin"
        self.request("/__control/config", dict(pin_status="NOT_REGISTERED"))
        self.assertEqual(self.request(path), dict(registered=False, status="NOT_REGISTERED"))
        self.request(path, dict(pin="654321"), key="pin-register")
        self.assertEqual(self.request(path), dict(registered=True, status="ACTIVE"))
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.request(path, dict(currentPin="123456", newPin="246810"), key="wrong-pin-change", method="PUT")
        self.assertEqual(error.exception.code, 422)
        error.exception.close()
        self.request(path, dict(currentPin="654321", newPin="246810"), key="pin-change", method="PUT")
        self.assertEqual(self.request("/api/pay/v1/payment-approvals/11111111-1111-4111-8111-111111111111/approve",
            dict(authentication=dict(method="PIN", pin="246810")), key="changed-pin-payment")["status"], "APPROVED")
        stats = json.dumps(self.request("/__control/stats"))
        for value in ("123456", "654321", "246810", "currentPin", "newPin"):
            self.assertNotIn(value, stats)


if __name__ == "__main__":
    unittest.main(verbosity=2)
