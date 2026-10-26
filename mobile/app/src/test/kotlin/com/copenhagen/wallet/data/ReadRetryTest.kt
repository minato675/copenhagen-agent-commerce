package com.copenhagen.wallet.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ReadRetryTest {
    @Test fun onlyReadsAndTheExactAuditedSearchPostAreAllowed() {
        assertTrue(ReadRetry.allows("GET", "/api/core/v1/addresses"))
        assertTrue(ReadRetry.allows("HEAD", "/read"))
        assertTrue(ReadRetry.allows("POST", "/api/core/v1/products/search"))
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertFalse(ReadRetry.allows(method, "/api/pay/v1/payment-approvals/id/approve"))
            assertFalse(ReadRetry.allows(method, "/api/core/v1/products/search/other"))
        }
    }

    @Test fun transientFailuresUseBoundedBackoff() {
        val failures = listOf(IOException(), UnknownHostException(), SocketTimeoutException()) +
            listOf(408, 500, 502, 503, 504).map { ApiError(it, "TEMPORARY") }
        for (failure in failures) {
            assertTrue(ReadRetry.delayMillis(failure, 0, null) in 400L..600L)
            assertTrue(ReadRetry.delayMillis(failure, 1, null) in 800L..1200L)
            assertNull(ReadRetry.delayMillis(failure, 2, null))
        }
    }

    @Test fun authenticationBusinessAndRateLimitErrorsAreNotRetried() {
        for (status in listOf(302, 400, 401, 403, 404, 409, 410, 422, 423, 425, 429, 501, 505)) {
            assertNull("Unexpected retry for $status", ReadRetry.delayMillis(ApiError(status, "PIN_LOCKED"), 0, "0"))
        }
    }

    @Test fun cancellationCertificateProtocolAndInvalidPayloadFailuresAreNotRetried() {
        for (failure in listOf(CancellationException(), InterruptedIOException(), ProtocolException(),
            SSLHandshakeException("certificate"), SSLPeerUnverifiedException("hostname"),
            SerializationException("bad json"), IllegalArgumentException())) {
            assertNull(ReadRetry.delayMillis(failure, 0, null))
        }
    }

    @Test fun retryAfterSecondsAndHttpDateAreMinimumWaits() {
        val failure = ApiError(503, "UNAVAILABLE")
        assertEquals(2000L, ReadRetry.delayMillis(failure, 0, "2"))
        val now = Instant.parse("2026-09-21T10:00:00Z")
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(3).atZone(ZoneOffset.UTC))
        assertEquals(3000L, ReadRetry.delayMillis(failure, 0, date, now.toEpochMilli()))
        assertNull(ReadRetry.delayMillis(failure, 0, "60"))
    }

    @Test fun oversizedRetryAfterNeverOverflowsIntoAnImmediateRetry() {
        for (header in listOf(Long.MAX_VALUE.toString(), "999999999999999999999999999999999")) {
            assertNull(ReadRetry.delayMillis(ApiError(503, "UNAVAILABLE"), 0, header))
        }
    }

    @Test fun invalidOrPastRetryAfterUsesNormalBackoff() {
        for (header in listOf("", "invalid", "-1", "Mon, 21 Sep 2020 10:00:00 GMT")) {
            assertTrue(ReadRetry.delayMillis(ApiError(503, "UNAVAILABLE"), 0, header) in 400L..600L)
        }
    }
}
