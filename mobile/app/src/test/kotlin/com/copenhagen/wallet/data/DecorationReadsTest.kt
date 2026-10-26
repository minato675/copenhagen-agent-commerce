package com.copenhagen.wallet.data

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DecorationReadsTest {
    @Test fun simultaneousUnauthorizedDecorationReadsNeverRotateTokens() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(4) { server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"AUTH_TOKEN_EXPIRED"}""")) }
            val base = server.url("/").toString()
            val reads = DecorationReads(Http(log = {}), { "fixture-expired-access" }, { base }, { base })
            val results = listOf(
                async { runCatching { reads.core("GET", "/cart", null, emptyMap()) } },
                async { runCatching { reads.core("POST", "/products/search", buildJsonObject { put("query", "상품") }, emptyMap()) } },
                async { runCatching { reads.core("GET", "/products/switch", null, mapOf("merchantId" to "shop-a")) } },
                async { runCatching { reads.approval("/payment-approvals/11111111-1111-4111-8111-111111111111") } }
            ).awaitAll()
            results.forEach { assertEquals(401, (it.exceptionOrNull() as ApiError).status) }
            assertEquals(4, server.requestCount)
            repeat(4) {
                val request = server.takeRequest()
                assertEquals("Bearer fixture-expired-access", request.getHeader("Authorization"))
                assertFalse(request.path!!.contains("refresh"))
            }
        } finally { server.shutdown() }
    }

    @Test fun decorationTransportCannotSendPurchaseMutationsOrReadWithoutAToken() = runBlocking {
        val reads = DecorationReads(Http(log = {}), { null }, { "https://fixture.invalid" }, { "https://fixture.invalid" })
        assertTrue(runCatching { reads.core("GET", "/cart", null, emptyMap()) }.exceptionOrNull() is SessionExpired)
        assertTrue(runCatching { reads.core("DELETE", "/cart", null, emptyMap()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { reads.core("GET", "/products/switch", null, mapOf("merchantId" to "shop-a")) }.exceptionOrNull() is SessionExpired)
        for (path in listOf("/products/", "/products/..", "/products/.", "/products/a/b", "/products/a\\b", "/products/a\nb")) {
            assertTrue(path, runCatching { reads.core("GET", path, null, mapOf("merchantId" to "shop-a")) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { reads.core("POST", "/products/switch", null, mapOf("merchantId" to "shop-a")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { reads.core("GET", "/products/switch", buildJsonObject {}, mapOf("merchantId" to "shop-a")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { reads.core("GET", "/products/switch", null, emptyMap()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { reads.core("GET", "/products/switch", null, mapOf("merchantId" to "shop-a", "action" to "buy")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { reads.approval("/payment-approvals/11111111-1111-4111-8111-111111111111/approve") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun productIdentityIsEncodedExactlyOnceAndQueryValuesStaySeparate() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val base = server.url("/").toString()
            val reads = DecorationReads(Http(log = {}), { "fixture-access" }, { base }, { base })
            val product = "상품 %20?#"
            reads.core("GET", "/products/$product", null, mapOf("merchantId" to "shop & a", "optionId" to "검정 + 1"))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(listOf("api", "core", "v1", "products", product), request.requestUrl!!.pathSegments)
            assertEquals("shop & a", request.requestUrl!!.queryParameter("merchantId"))
            assertEquals("검정 + 1", request.requestUrl!!.queryParameter("optionId"))
            assertEquals(2, request.requestUrl!!.querySize)
            assertEquals("Bearer fixture-access", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
