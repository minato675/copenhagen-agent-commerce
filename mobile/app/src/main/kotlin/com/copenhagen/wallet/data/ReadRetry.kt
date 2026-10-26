package com.copenhagen.wallet.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlin.random.Random

/** Only transport-level reads opt in. Mutation recovery remains owned by each feature. */
internal object ReadRetry {
    const val MAX_RETRIES = 2
    const val TIMEOUT_MILLIS = 35_000L
    private const val MAX_DELAY_MILLIS = 5_000L

    fun allows(method: String, path: String): Boolean = method in setOf("GET", "HEAD") ||
        // Core's authenticated catalog search is a read despite using a JSON POST body.
        (method == "POST" && path == "/api/core/v1/products/search")

    fun delayMillis(failure: Exception, retries: Int, retryAfter: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (retries >= MAX_RETRIES || !transient(failure)) return null
        val base = 400L shl retries
        val backoff = base + Random.nextLong(base / 2 + 1)
        val requested = retryAfterMillis(retryAfter, nowMillis) ?: 0L
        // Do not retry before the server permits it, or keep a screen waiting on a long outage.
        if (requested > MAX_DELAY_MILLIS) return null
        return maxOf(backoff, requested)
    }

    private fun transient(failure: Exception): Boolean = when (failure) {
        is ApiError -> failure.status in setOf(408, 500, 502, 503, 504)
        is SSLException, is ProtocolException -> false
        is InterruptedIOException -> failure is SocketTimeoutException
        is IOException -> true
        else -> false
    }

    private fun retryAfterMillis(value: String?, nowMillis: Long): Long? {
        val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (header.all { it in '0'..'9' }) {
            // Saturate oversized values so an overflow can never cause an early retry.
            return header.toLongOrNull()?.coerceAtMost(Long.MAX_VALUE / 1000)?.times(1000) ?: Long.MAX_VALUE
        }
        return runCatching {
            (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
                .coerceAtLeast(0L)
        }.getOrNull()
    }
}
