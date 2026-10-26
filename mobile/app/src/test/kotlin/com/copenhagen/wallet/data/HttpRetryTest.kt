package com.copenhagen.wallet.data

import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Real HTTP attempts against loopback; neither Android accounts nor payment services are used. */
class HttpRetryTest {
    private val server = MockWebServer()
    private val http = Http(log = {})
    private val base get() = server.url("/").toString()
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun transientReadsRecoverWithUnchangedIdentityAndQuery() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"code\":\"UNAVAILABLE\"}"))
        server.enqueue(MockResponse().setResponseCode(502).setBody("gateway unavailable"))
        server.enqueue(MockResponse().setBody("{\"items\":[]}"))
        assertEquals(buildJsonObject { put("items", JsonArray(emptyList())) },
            http.request(base, "GET", "/api/pay/v1/payment-approvals", token = "fixture-token",
                headers = mapOf("X-Device-Id" to "fixture-device"), query = mapOf("status" to "PENDING")))
        assertEquals(3, server.requestCount)
        repeat(3) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/pay/v1/payment-approvals?status=PENDING", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertEquals("fixture-device", request.getHeader("X-Device-Id"))
        }
    }

    @Test fun catalogSearchReadPostRecoversWithTheExactOriginalBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("{\"data\":{\"products\":[]}}"))
        val body = buildJsonObject { put("query", "신발"); put("page", 2) }
        http.request(base, "POST", "/api/core/v1/products/search", body, token = "fixture-token")
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/core/v1/products/search", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertEquals(body, json.parseToJsonElement(request.body.readUtf8()))
        }
    }

    @Test fun disconnectedReadRecoversAutomatically() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("{\"items\":[]}"))
        assertTrue(http.request(base, "GET", "/api/core/v1/addresses").jsonObject.containsKey("items"))
        assertEquals(2, server.requestCount)
    }

    @Test fun repeatedDisconnectsCannotBypassTheHttpSendLimit() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        assertTrue(runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() is IOException)
        assertTrue(server.requestCount <= 3)
    }

    @Test fun readTimeoutCanRecoverWithinTheSharedDeadline() = runBlocking {
        val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().setHeadersDelay(350, TimeUnit.MILLISECONDS).setBody("{}"))
        server.enqueue(MockResponse().setBody("{\"recovered\":true}"))
        assertTrue(Http(client, log = {}).request(base, "GET", "/read").jsonObject.getValue("recovered").jsonPrimitive.boolean)
        assertEquals(2, server.requestCount)
    }

    @Test fun persistentFailureStopsAfterThreeAttemptsAndKeepsTheFinalError() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("{\"code\":\"STILL_UNAVAILABLE\"}")) }
        server.enqueue(MockResponse().setBody("{}"))
        val failure = runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() as ApiError
        assertEquals(503, failure.status)
        assertEquals("STILL_UNAVAILABLE", failure.code)
        assertEquals(3, server.requestCount)
    }

    @Test fun immediateServerRetryHintCannotBypassOurAttemptLimit() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) }
        assertTrue(runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() is ApiError)
        assertEquals(3, server.requestCount)
    }

    @Test fun serverRetryAfterIsRespected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("{}"))
        val started = System.nanoTime()
        http.request(base, "GET", "/read")
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 1000)
        assertEquals(2, server.requestCount)
    }

    @Test fun longServerBackoffReturnsTheErrorWithoutEarlyRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "60"))
        assertTrue(runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() is ApiError)
        assertEquals(1, server.requestCount)
    }

    @Test fun oversizedServerRetryAfterCannotOverflowInsideOkHttp() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "999999999999999999999999999999"))
        assertTrue(runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() is ApiError)
        assertEquals(1, server.requestCount)
    }

    @Test fun failedWritesAreNeverReplayedEvenWithAnIdempotencyKeyAndImmediateRetryHint() = runBlocking {
        for ((method, path) in listOf("POST" to "/api/pay/v1/payment-approvals/id/approve",
            "POST" to "/api/pay/v1/payment-approvals/id/reject", "POST" to "/api/core/v1/auth/token/refresh",
            "POST" to "/api/core/v1/addresses", "POST" to "/api/pg/v1/payment-methods/tokenize",
            "PUT" to "/api/pay/v1/users/me/payment-pin", "PATCH" to "/api/core/v1/addresses/id/default",
            "DELETE" to "/api/core/v1/addresses/id")) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            val failure = runCatching { http.request(base, method, path, buildJsonObject { put("value", "fixture") }, key = newKey()) }.exceptionOrNull()
            assertTrue("$method $path", failure is ApiError)
            assertEquals("$method $path was replayed", before + 1, server.requestCount)
        }
    }

    @Test fun disconnectedPaymentPostIsNotReplayed() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val failure = runCatching { http.request(base, "POST", "/api/pay/v1/payment-approvals/id/approve",
            pinApprovalBody("123456"), key = newKey()) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test fun authenticationAndBusinessErrorsReturnImmediately() = runBlocking {
        for (status in listOf(401, 403, 409, 422, 423, 429)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"code\":\"AUTH_ERROR\"}"))
            val failure = runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() as ApiError
            assertEquals(status, failure.status)
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun malformedSuccessIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"items\":"))
        assertTrue(runCatching { http.request(base, "GET", "/read") }.exceptionOrNull() is SerializationException)
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDuringBackoffStopsBeforeAnotherAttempt() = runBlocking {
        val retryScheduled = CompletableDeferred<Unit>()
        val transport = Http(log = { if (it.contains("retry=")) retryScheduled.complete(Unit) })
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "5"))
        val call = async { transport.request(base, "GET", "/read") }
        withTimeout(3000) { retryScheduled.await() }
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun backoffCannotRestartAnExpiredRequestBudget() = runBlocking {
        val client = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { Http(client, log = {}).request(base, "GET", "/read") }.exceptionOrNull() is ApiError)
        assertEquals(1, server.requestCount)
    }

    @Test fun secondAttemptUsesOnlyRemainingTime() = runBlocking {
        val timeouts = mutableListOf<Long>()
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).addInterceptor { chain ->
            timeouts.add(chain.call().timeout().timeoutNanos())
            chain.proceed(chain.request())
        }.build()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("{}"))
        Http(client, log = {}).request(base, "GET", "/read")
        assertEquals(2, timeouts.size)
        assertTrue(timeouts[1] < timeouts[0] - TimeUnit.MILLISECONDS.toNanos(350))
    }
}
