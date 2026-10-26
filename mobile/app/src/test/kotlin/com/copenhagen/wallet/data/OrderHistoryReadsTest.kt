package com.copenhagen.wallet.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OrderHistoryReadsTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val order = """{
      "orderId":"$id","title":"닌텐도 스위치 2","status":"SHIPPED",
      "amountMinor":648000,"currency":"KRW","createdAt":"2026-09-23T00:00:00Z",
      "items":[{"merchantId":"merchant-a","merchantProductId":"switch-2","merchantVariantId":"standard",
        "name":"닌텐도 스위치 2","option":"기본","quantity":1,"imageUrl":"https://images.example/switch.jpg"},
        {"merchantId":"merchant-b","merchantProductId":"case","name":"케이스","quantity":2}],
      "address":{"recipient":"테스트","phone":"010-0000-0000","postalCode":"00000",
        "address":"테스트 주소","detail":"","memo":"문 앞"},"paymentLabel":"테스트 카드"} """

    @Test fun v2ListAndDetailUseOneReadEachAndPreserveIdsImagesAndOptionalFields() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[$order],"nextCursor":"opaque+/=cursor"}"""))
            server.enqueue(MockResponse().setBody(order))
            val http = Http(log = {})
            val reads = OrderHistoryReads({ path, query ->
                http.request(server.url("/").toString(), "GET", path, token = "test-access", query = query)
            })
            val page = reads.list(mapOf("size" to "20", "cursor" to "opaque+/=cursor", "status" to "SHIPPED"))
            val detail = reads.detail(id)
            assertFalse(page.legacy); assertFalse(detail.legacy)
            assertEquals(page.value.items.single(), detail.value)
            assertEquals("opaque+/=cursor", page.value.nextCursor)
            val item = detail.value.items.first()
            assertEquals("merchant-a", item.merchantId)
            assertEquals("switch-2", item.merchantProductId)
            assertEquals("standard", item.merchantVariantId)
            assertEquals("https://images.example/switch.jpg", item.imageUrl)
            assertNull(detail.value.items.last().imageUrl)
            assertNull(detail.value.items.last().merchantVariantId)
            assertNull(detail.value.items.last().option)
            assertEquals("문 앞", detail.value.address!!.memo)
            val listRequest = server.takeRequest()
            assertEquals("/api/core/v2/orders", listRequest.requestUrl!!.encodedPath)
            assertEquals("opaque+/=cursor", listRequest.requestUrl!!.queryParameter("cursor"))
            assertEquals("SHIPPED", listRequest.requestUrl!!.queryParameter("status"))
            assertEquals("Bearer test-access", listRequest.getHeader("Authorization"))
            assertEquals("/api/core/v2/orders/$id", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun undeployedV2FallsBackTemporarilyAndAutomaticallyRetriesAfterDeployment() = runTest {
        var now = 0L
        var deployed = false
        val paths = mutableListOf<String>()
        val queries = mutableListOf<Map<String, String>>()
        val reads = OrderHistoryReads({ path, query ->
            paths += path; queries += query
            if (path.startsWith("/api/core/v2") && !deployed) throw ApiError(404, "")
            json.parseToJsonElement(if (path.endsWith("/orders")) """{"items":[$order]}""" else order)
        }, { now })
        val query = mapOf("size" to "20", "status" to "SHIPPED")
        assertTrue(reads.list(query).legacy)
        assertTrue(reads.detail(id).legacy)
        assertTrue(reads.list(query).legacy)
        assertEquals(listOf("/api/core/v2/orders", "/api/core/v1/orders", "/api/core/v1/orders/$id", "/api/core/v1/orders"), paths)
        assertEquals(query, queries[1])
        deployed = true; now = OrderHistoryReads.RETRY_V2_AFTER_MS
        assertFalse(reads.list(query).legacy)
        assertFalse(reads.detail(id).legacy)
        assertEquals("/api/core/v2/orders/$id", paths.last())
    }

    @Test fun missingOrderAuthenticationOutageAndInvalidResponsesNeverDowngrade() = runTest {
        for (error in listOf(ApiError(404, "ORDER_NOT_FOUND"), ApiError(401, "AUTH_TOKEN_INVALID"),
            ApiError(403, ""), ApiError(500, ""), IOException("offline"), CancellationException("cancelled"))) {
            val paths = mutableListOf<String>()
            val reads = OrderHistoryReads({ path, _ -> paths += path; throw error })
            assertSame(error, runCatching { reads.detail(id) }.exceptionOrNull())
            assertEquals(listOf("/api/core/v2/orders/$id"), paths)
        }
        var calls = 0
        val invalid = OrderHistoryReads({ _, _ -> calls++; buildJsonObject { put("unexpected", true) } })
        assertTrue(runCatching { invalid.list(emptyMap()) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun clearResetsTheVersionProbeAndLateFailuresCannotPoisonANewSession() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val paths = mutableListOf<String>()
        val reads = OrderHistoryReads({ path, _ ->
            paths += path
            if (first) { first = false; gate.await(); throw ApiError(404, "") }
            json.parseToJsonElement("""{"items":[]}""")
        })
        val old = async { reads.list(emptyMap()) }
        runCurrent(); reads.clear(); gate.complete(Unit); old.await()
        reads.list(emptyMap())
        assertEquals("/api/core/v2/orders", paths.last())
    }

    @Test fun invalidOrderIdsAreRejectedBeforeAnyRequest() = runTest {
        val reads = OrderHistoryReads({ _, _ -> error("must not request") })
        assertTrue(runCatching { reads.detail("../orders") }.exceptionOrNull() is IllegalArgumentException)
    }
}
