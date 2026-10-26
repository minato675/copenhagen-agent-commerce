package com.copenhagen.wallet.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.UUID

/** 실제 Http 전송 코드와 실제 JSON 빌더를 사용한다. 외부 서버·실카드는 사용하지 않는다. */
class HttpTest {
    private val server = MockWebServer()
    private val logs = mutableListOf<String>()
    private val http = Http(log = { logs.add(it) })
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun pgRequestHasNoBearerToken() = runBlocking {
        val key = newKey()
        server.enqueue(MockResponse().setBody("""{"provider":"mock-pg","providerTokenRef":"opaque","brand":"VISA","last4":"1111","expiryMonth":12,"expiryYear":2099}"""))
        val token = http.request(server.url("/").toString(), "POST", "/api/pg/v1/payment-methods/tokenize",
            tokenizeBody("4111111111111111", 12, 2099, "123"), key = key).read<TokenizedCard>()
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/pg/v1/payment-methods/tokenize", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals(key, UUID.fromString(request.getHeader("Idempotency-Key")).toString())
        assertEquals("mock-pg", token.provider)
    }
    @Test fun payRegistrationRetryKeepsTokenBodyAndKey() = runBlocking {
        val key = newKey()
        val body = registerCardBody(TokenizedCard("mock-pg", "opaque", "VISA", "1111", 12, 2099))
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"paymentMethodId":"id","isDefault":true,"status":"ACTIVE"}"""))
            http.request(server.url("/").toString(), "POST", "/api/pay/v1/payment-methods/cards", body,
                token = "user-access-token", key = key)
        }
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val retry = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer user-access-token", first.getHeader("Authorization"))
        assertEquals(key, UUID.fromString(first.getHeader("Idempotency-Key")).toString())
        assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), retry.body.readUtf8())
    }
    @Test fun selectionAndApprovalHaveDifferentPathsAndBodies() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
        http.request(server.url("/").toString(), "PATCH", "/api/pay/v1/payment-approvals/id/payment-method",
            buildJsonObject { put("paymentMethodId", "card-id") }, "user", "select-key")
        http.request(server.url("/").toString(), "POST", "/api/pay/v1/payment-approvals/id/approve",
            pinApprovalBody("123456"), "user", "approve-key")
        val selection = server.takeRequest(2, TimeUnit.SECONDS)!!
        val approval = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PATCH", selection.method)
        assertTrue(selection.body.readUtf8().contains("paymentMethodId"))
        assertFalse(approval.body.readUtf8().contains("paymentMethodId"))
        assertNotEquals(selection.getHeader("Idempotency-Key"), approval.getHeader("Idempotency-Key"))
    }
    @Test fun deviceHeaderAndQueryArePreserved() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        http.request(server.url("/").toString(), "GET", "/api/pay/v1/payment-approvals", token = "user",
            headers = mapOf("X-Device-Id" to "installation-id"), query = mapOf("status" to "PAYMENT_METHOD_REQUIRED"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("installation-id", request.getHeader("X-Device-Id"))
        assertTrue(request.path!!.endsWith("?status=PAYMENT_METHOD_REQUIRED"))
    }
    @Test fun requestLogsMaskSecretsWithoutChangingTheRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"accessToken":"response-secret"}"""))
        val body = json.parseToJsonElement("""{
            "loginId":"demo-user","password":"password-secret",
            "authentication":{"method":"PIN","pin":"pin-secret"},
            "tokenizationResult":{"provider":"mock-pg","providerTokenRef":"provider-secret"},
            "cards":[{"cardNumber":"card-secret","cvc":"cvc-secret"}]
        }""")
        val result = http.request(server.url("/?access_token=base-query-secret").toString(), "POST", "/api/test", body,
            token = "bearer-secret", key = "request-key",
            headers = mapOf("X-Device-Id" to "device-secret", "Cookie" to "cookie-secret"),
            query = mapOf("status" to "PENDING", "refreshToken" to "query-secret"))
        assertEquals("response-secret", result.jsonObject.getValue("accessToken").jsonPrimitive.content)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer bearer-secret", request.getHeader("Authorization"))
        assertEquals("device-secret", request.getHeader("X-Device-Id"))
        assertEquals("query-secret", request.requestUrl!!.queryParameter("refreshToken"))
        assertEquals(body, json.parseToJsonElement(request.body.readUtf8()))
        val output = logs.joinToString("\n")
        for (secret in listOf("password-secret", "pin-secret", "provider-secret", "card-secret", "cvc-secret",
            "bearer-secret", "device-secret", "cookie-secret", "base-query-secret", "query-secret", "response-secret")) {
            assertFalse("Leaked $secret", output.contains(secret))
        }
        assertTrue(output.contains("\"Authorization\":\"***\""))
        assertTrue(output.contains("\"Idempotency-Key\":\"request-key\""))
        assertTrue(output.contains("\"Content-Type\":\"application/json; charset=utf-8\""))
        assertTrue(output.contains("\"status\":[\"PENDING\"]"))
        assertTrue(output.contains("\"loginId\":\"demo-user\""))
        assertTrue(output.contains("\"authentication\":{\"method\":\"PIN\",\"pin\":\"***\"}"))
        assertTrue(output.contains("\"provider\":\"mock-pg\""))
        assertTrue(output.contains("response={\"accessToken\":\"***\"}"))
    }
    @Test fun getRequestDoesNotLogAnUnsentBody() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        http.request(server.url("/").toString(), "GET", "/api/test", buildJsonObject { put("unused", "not-sent") })
        assertEquals(0L, server.takeRequest(2, TimeUnit.SECONDS)!!.bodySize)
        assertFalse(logs.any { it.contains("body=") || it.contains("not-sent") })
    }
    @Test fun mandateAndPgCardPasswordAreMaskedWithoutChangingPayloads() = runBlocking {
        val response = """{"approvalId":"approval-id","status":"APPROVED","checkoutMandate":"signed-mandate-secret"}"""
        server.enqueue(MockResponse().setBody(response))
        val body = buildJsonObject { put("cardPassword", "pg-password-secret") }
        val result = http.request(server.url("/").toString(), "POST", "/api/test", body)
        assertEquals(body, json.parseToJsonElement(server.takeRequest().body.readUtf8()))
        assertEquals("signed-mandate-secret", result.jsonObject.getValue("checkoutMandate").jsonPrimitive.content)
        val output = logs.joinToString("\n")
        assertFalse(output.contains("signed-mandate-secret"))
        assertFalse(output.contains("pg-password-secret"))
        assertTrue(output.contains("\"checkoutMandate\":\"***\""))
        assertTrue(output.contains("\"cardPassword\":\"***\""))
    }
    @Test fun empty204DoesNotNeedJson() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(JsonNull, http.request(server.url("/").toString(), "DELETE", "/card"))
        assertTrue(logs.any { it.endsWith("response=<empty>") })
    }
    @Test fun biometricRevocationUsesDeviceAndUuidHeadersWithoutABody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val key = newKey()
        val result = http.request(server.url("/").toString(), "DELETE",
            "/api/pay/v1/users/me/biometric-credentials", token = "fixture-access", key = key,
            headers = mapOf("X-Device-Id" to "fixture-device"))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", request.method)
        assertEquals("/api/pay/v1/users/me/biometric-credentials", request.path)
        assertEquals("Bearer fixture-access", request.getHeader("Authorization"))
        assertEquals("fixture-device", request.getHeader("X-Device-Id"))
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals(key, java.util.UUID.fromString(key).toString())
        assertEquals(0L, request.bodySize)
        assertNull(request.getHeader("Content-Type"))
        assertEquals(JsonNull, result)
        assertFalse(logs.any { it.contains("fixture-access") || it.contains("fixture-device") })
    }
    @Test fun notificationDisconnectPreservesFidOnWireButMasksItsPathInLogs() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = http.request(server.url("/").toString(), "DELETE",
            "/api/pay/v1/notification-installations/private-fid", token = "old-account-token")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("DELETE", request.method)
        assertEquals("/api/pay/v1/notification-installations/private-fid", request.path)
        assertEquals("Bearer old-account-token", request.getHeader("Authorization"))
        assertEquals(JsonNull, result)
        assertFalse(logs.any { it.contains("private-fid") || it.contains("old-account-token") })
        assertTrue(logs.any { it.contains("/notification-installations/***") })
    }
    @Test fun errorBodyIsNotShownToUser() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"INVALID_CARD_DATA","message":"secret-card-payload","cardNumber":"sensitive-card-number"}"""))
        val failure = runCatching { http.request(server.url("/").toString(), "GET", "/card") }.exceptionOrNull()!!
        assertTrue(failure is ApiError)
        assertEquals("카드 정보를 확인해 주세요.", failure.userMessage())
        assertFalse(failure.userMessage().contains("secret-card-payload"))
        val responseLog = logs.single { it.contains("response=") }
        assertTrue(responseLog.contains("\"code\":\"INVALID_CARD_DATA\""))
        assertTrue(responseLog.contains("\"cardNumber\":\"***\""))
        assertFalse(responseLog.contains("sensitive-card-number"))
    }
    @Test fun nonJsonResponseLoggingDoesNotLeakRawTextOrChangeErrors() = runBlocking {
        for (raw in listOf("<html>private-token-value</html>", "{\"token\":\"private-token-value\"")) {
            for (status in listOf(200, 502)) {
                repeat(if (status == 502) 3 else 1) {
                    server.enqueue(MockResponse().setResponseCode(status).setBody(raw))
                }
                val result = runCatching { http.request(server.url("/").toString(), "GET", "/api/test") }
                if (status == 502) assertEquals(502, (result.exceptionOrNull() as ApiError).status)
                else if (raw.startsWith("<")) assertEquals(raw, result.getOrThrow().jsonPrimitive.content)
                else assertTrue(result.exceptionOrNull() is kotlinx.serialization.SerializationException)
            }
        }
        assertEquals(8, logs.count { it.contains("response=<unstructured response;") })
        assertFalse(logs.any { it.contains("private-token-value") })
    }
    @Test fun longJsonResponseIsLoggedInChunksWithNestedSecretsMasked() = runBlocking {
        val response = buildJsonObject {
            put("items", buildJsonArray {
                repeat(100) {
                    add(buildJsonObject { put("name", "결제 항목 $it"); put("refreshToken", "nested-secret") })
                }
            })
        }
        server.enqueue(MockResponse().setBody(response.toString()))
        assertEquals(response, http.request(server.url("/").toString(), "GET", "/api/test"))
        val parts = logs.filter { it.contains("response=") }.map { it.substringAfter("response=") }
        assertTrue(parts.size > 1)
        parts.forEachIndexed { index, part -> assertTrue(part.startsWith("[${index + 1}/${parts.size}] ")) }
        val logged = json.parseToJsonElement(parts.joinToString("") { it.substringAfter("] ") })
        val items = logged.jsonObject.getValue("items").jsonArray
        assertEquals(100, items.size)
        assertEquals("결제 항목 99", items.last().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(items.all { it.jsonObject.getValue("refreshToken").jsonPrimitive.content == "***" })
        assertFalse(logs.any { it.contains("nested-secret") })
    }
    @Test fun bearerIsNotForwardedOnRedirect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
        val failure = runCatching { http.request(server.url("/").toString(), "GET", "/original", token = "user") }.exceptionOrNull()
        assertTrue(failure is ApiError)
        assertEquals(1, server.requestCount)
    }
    @Test fun cancelStopsWaiting() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
        val call = async { http.request(server.url("/").toString(), "GET", "/slow") }
        delay(50)
        call.cancelAndJoin()
        assertTrue(call.isCancelled)
    }
}
