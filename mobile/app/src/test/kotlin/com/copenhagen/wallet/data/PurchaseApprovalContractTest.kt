package com.copenhagen.wallet.data

import com.copenhagen.wallet.features.Approvals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Shapes from backend fd273b3 AuthorizationSessionController, not serialized app models. */
class PurchaseApprovalContractTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val listRow = """{"approvalId":"$id","merchantName":"첫 판매처","merchantCount":2,
        "totalAmountMinor":9007199254740993,"currency":"KRW","status":"PENDING","requiredAction":"BIOMETRIC",
        "expiresAt":"2099-01-01T00:00:00Z"}"""
    private val detail = """{"approvalId":"$id","merchants":[
        {"merchantId":"merchant-a","merchantName":"첫 판매처","checkoutId":"checkout-a","checkoutHash":"sha256:a",
         "summary":[{"name":"상품 A","option":null,"quantity":2,"amountMinor":9007199254730000}],
         "subtotalAmountMinor":9007199254740000},
        {"merchantId":"merchant-b","merchantName":"두 번째 판매처","checkoutId":"checkout-b","checkoutHash":"sha256:b",
         "summary":[{"name":"상품 B","option":"파랑","quantity":1,"amountMinor":993}],"subtotalAmountMinor":993}],
        "totalAmountMinor":9007199254740993,"currency":"KRW","selectedPaymentMethodId":"card-id",
        "status":"PENDING","requiredAction":"BIOMETRIC","expiresAt":"2099-01-01T00:00:00Z","biometricChallenge":null}"""

    @Test fun currentListDetailAndOnePinApprovalWorkThroughHttp() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[$listRow]}"""))
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            server.enqueue(MockResponse().setBody(detail))
            server.enqueue(MockResponse().setBody("""{"approvalId":"$id","status":"APPROVED","requiredAction":null,
                "authMethod":"PIN","credentialCount":2,"nextAction":"WAIT_FOR_PAYMENT"}"""))
            val http = Http(log = {})
            val approvals = Approvals(request = { method, path, body, key, query, device ->
                http.request(server.url("/").toString(), method, "/api/pay/v1$path", body,
                    token = "fixture-access", key = key, query = query,
                    headers = if (device) mapOf("X-Device-Id" to "fixture-device") else emptyMap())
            })
            approvals.loadInbox()
            assertEquals("첫 판매처 외 1곳", approvals.inbox!!.single().displayName)
            assertEquals(9007199254740993L, approvals.inbox!!.single().amountMinor)
            approvals.open(id)
            val review = approvals.current!!
            assertEquals(2, review.merchants.size)
            assertEquals(3, review.displaySummary.sumOf { it.quantity })
            assertNull(review.merchants.first().summary.single().option)
            assertEquals(9007199254740000L, review.merchants.first().subtotalAmountMinor)
            assertEquals("APPROVED", approvals.approve(pinApprovalBody("123456")))
            assertFalse(approvals.uncertain)
            assertEquals("PENDING", server.takeRequest().requestUrl!!.queryParameter("status"))
            assertEquals("PAYMENT_METHOD_REQUIRED", server.takeRequest().requestUrl!!.queryParameter("status"))
            assertEquals("fixture-device", server.takeRequest().getHeader("X-Device-Id"))
            val sent = server.takeRequest()
            assertEquals("/api/pay/v1/payment-approvals/$id/approve", sent.path)
            assertTrue(validId(sent.getHeader("Idempotency-Key")!!))
            assertEquals(pinApprovalBody("123456"), json.parseToJsonElement(sent.body.readUtf8()))
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun everyMerchantTermParticipatesInConsentRevalidation() {
        val original = json.decodeFromString<Approval>(detail)
        val second = original.merchants[1]
        val changes = listOf(second.copy(merchantId = "other"), second.copy(merchantName = "다른 판매처"),
            second.copy(checkoutId = "changed"), second.copy(checkoutHash = "sha256:changed"),
            second.copy(subtotalAmountMinor = 992), second.copy(summary = listOf(ItemSummary("다른 상품"))),
            second.copy(summary = second.summary.map { it.copy(amountMinor = 990) }))
        changes.forEach { assertFalse(original.sameReviewAs(original.copy(merchants = listOf(original.merchants[0], it)))) }
        assertFalse(original.sameReviewAs(original.copy(merchants = original.merchants.take(1))))
        assertTrue(original.sameReviewAs(original.copy(biometricChallenge = Challenge("new", "new", "2099-01-01T00:00:00Z"))))
    }

    @Test fun brokenTotalsMissingMerchantDataAndListRowsCannotBeAuthorized() {
        val source = json.parseToJsonElement(detail).jsonObject
        assertTrue(runCatching { JsonObject(source - "totalAmountMinor").read<Approval>() }.exceptionOrNull() is SerializationException)
        val original = source.read<Approval>()
        val invalid = listOf(original.copy(amountMinor = original.amountMinor - 1), original.copy(merchants = emptyList()),
            original.copy(merchants = listOf(original.merchants.first(), original.merchants.first())),
            original.copy(merchants = listOf(original.merchants.first().copy(checkoutHash = ""))),
            json.decodeFromString<Approval>(listRow))
        invalid.forEach { assertTrue(runCatching { it.requireDetail() }.isFailure) }
    }

    @Test fun sharedApprovalAppearsOnceAfterShippingSelection() {
        val purchase = json.decodeFromString<Purchase>("""{"purchaseRequestId":"$id","status":"AWAITING_APPROVAL",
            "cartId":"$id","cartVersion":2,"expiresAt":"2099-01-01T00:00:00Z","nextAction":"OPEN_APPROVAL",
            "merchantCheckouts":[{"merchantId":"a","approvalId":"$id","status":"AWAITING_APPROVAL","totalAmount":100,"currency":"KRW"},
            {"merchantId":"b","approvalId":"$id","status":"AWAITING_APPROVAL","totalAmount":200,"currency":"KRW"}]}""")
        assertEquals(listOf(id), purchase.approvalIds)
    }

    @Test fun serverBiometricRegistrationDoesNotImplyPossessionOfItsPrivateKey() {
        val registered = json.decodeFromString<BiometricStatus>("""{"registered":true,"status":"ACTIVE","credentialId":"$id","registeredAt":"2026-09-22T01:00:00Z"}""")
        registered.requireValid()
        assertTrue(registered.matches(id, true))
        assertFalse(registered.matches(id, false))
        assertFalse(registered.matches(null, true))
        assertFalse(registered.matches("different-key", true))
        val absent = json.decodeFromString<BiometricStatus>("""{"registered":false,"status":null,"credentialId":null,"registeredAt":null}""")
        absent.requireValid()
        assertFalse(absent.matches(id, true))
        assertTrue(runCatching { registered.copy(credentialId = null).requireValid() }.isFailure)
    }
}
