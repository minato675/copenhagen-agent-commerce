package com.copenhagen.wallet.data

import kotlinx.serialization.json.JsonElement

/** A short-lived compatibility bridge for servers where the v2 routes are not deployed yet. */
class OrderHistoryReads(
    private val request: suspend (String, Map<String, String>) -> JsonElement,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    data class Result<T>(val value: T, val legacy: Boolean)
    private var legacyUntil: Long? = null
    private var generation = 0L

    suspend fun list(query: Map<String, String>): Result<Items<Order>> {
        val response = read("/orders", query)
        return Result(response.value.read(), response.legacy)
    }

    suspend fun detail(id: String): Result<Order> {
        require(validId(id))
        val response = read("/orders/$id", emptyMap())
        return Result(response.value.read(), response.legacy)
    }

    private suspend fun read(path: String, query: Map<String, String>): Result<JsonElement> {
        val readGeneration = generation
        if (legacyUntil?.let { clockMillis() < it } != true) {
            try {
                val value = request("/api/core/v2$path", query)
                if (readGeneration == generation) legacyUntil = null
                return Result(value, legacy = false)
            } catch (failure: ApiError) {
                // An order's own 404, authentication failure, timeout or 5xx is not a version signal.
                if (failure.status != 404 || failure.code.isNotBlank()) throw failure
                if (readGeneration == generation) legacyUntil = clockMillis() + RETRY_V2_AFTER_MS
            }
        }
        return Result(request("/api/core/v1$path", query), legacy = true)
    }

    fun clear() { generation++; legacyUntil = null }

    companion object { internal const val RETRY_V2_AFTER_MS = 60_000L }
}
