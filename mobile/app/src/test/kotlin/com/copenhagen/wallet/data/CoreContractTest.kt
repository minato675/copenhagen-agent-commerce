package com.copenhagen.wallet.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Core v5 OrderResponse / AddressSnapshot, taken from the specification rather than the old preview shape. */
class CoreContractTest {
    private val order = """{
        "orderId":"ffffffff-ffff-4fff-8fff-ffffffffffff",
        "title":"명세 검증 상품", "status":"CONFIRMED",
        "amountMinor":9007199254740993, "currency":"KRW", "createdAt":"2026-09-18T08:27:49Z",
        "items":[{"name":"명세 검증 상품","option":"네이비","quantity":2}],
        "address":{"recipient":"테스트 수령인","phone":"010-0000-0000","postalCode":"01234",
                   "address":"서울시 테스트로 12","detail":"101호","memo":"문 앞에 놓아 주세요"},
        "paymentLabel":"테스트 카드 •••• 1111"
    }"""

    @Test fun orderListAndDetailDecodeTheSameNestedAddressThroughHttp() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[$order],"nextCursor":"opaque-cursor"}"""))
            server.enqueue(MockResponse().setBody(order))
            val http = Http(log = {})
            val page = http.request(server.url("/").toString(), "GET", "/api/core/v1/orders",
                token = "test-access", query = mapOf("size" to "20", "status" to "CONFIRMED"))
                .read<Items<Order>>()
            val detail = http.request(server.url("/").toString(), "GET",
                "/api/core/v1/orders/${page.items.single().id}", token = "test-access").read<Order>()
            assertEquals(page.items.single(), detail)
            assertEquals(9007199254740993L, detail.amountMinor)
            assertEquals("opaque-cursor", page.nextCursor)
            assertEquals(OrderAddress("테스트 수령인", "010-0000-0000", "01234", "서울시 테스트로 12", "101호", "문 앞에 놓아 주세요"), detail.address)
            assertEquals("CONFIRMED", server.takeRequest().requestUrl!!.queryParameter("status"))
            assertEquals("Bearer test-access", server.takeRequest().getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun absentAddressDoesNotInventDeliveryInformation() {
        val response = json.parseToJsonElement(order).jsonObject
        assertNull(JsonObject(response - "address").read<Order>().address)
        assertNull(JsonObject(response + ("address" to JsonNull)).read<Order>().address)
    }

    @Test fun emptyOptionalDeliveryFieldsArePreserved() {
        val response = json.parseToJsonElement(order).jsonObject
        val address = response.getValue("address").jsonObject
        val parsed = JsonObject(response + ("address" to JsonObject(address + mapOf("detail" to JsonPrimitive(""), "memo" to JsonPrimitive(""))))).read<Order>()
        assertEquals("", parsed.address!!.detail)
        assertEquals("", parsed.address!!.memo)
        assertEquals("01234", parsed.address!!.postalCode)
    }

    @Test fun invalidMoneyAndOldStringAddressAreNotSilentlyCoerced() {
        val response = json.parseToJsonElement(order).jsonObject
        assertTrue(runCatching { JsonObject(response - "amountMinor").read<Order>() }.exceptionOrNull() is SerializationException)
        assertTrue(runCatching { JsonObject(response + ("address" to JsonPrimitive("legacy mock address"))).read<Order>() }.exceptionOrNull() is SerializationException)
    }

    @Test fun orderFiltersUseCoreStatesAndNeverPaymentCompletionState() {
        assertEquals(setOf("CREATED", "CONFIRMED", "FULFILLING", "SHIPPED", "DELIVERED", "CANCELLED", "FAILED"), orderStatuses.toSet())
        assertFalse(orderStatuses.contains("COMPLETED"))
        orderStatuses.forEach { assertNotEquals("상태 확인 필요", orderStatusLabel(it)) }
        assertEquals("상태 확인 필요", orderStatusLabel("FUTURE_STATUS"))
    }
}
