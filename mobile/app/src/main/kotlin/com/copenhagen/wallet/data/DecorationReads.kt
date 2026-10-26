package com.copenhagen.wallet.data

import kotlinx.serialization.json.JsonElement

/** Optional background reads may use the current access token, but never rotate session tokens. */
class DecorationReads(
    private val http: Http,
    private val accessToken: () -> String?,
    private val coreBase: () -> String,
    private val payBase: () -> String
) {
    suspend fun core(method: String, path: String, body: JsonElement?, query: Map<String, String>): JsonElement {
        val productRead = method == "GET" && body == null && path.startsWith("/products/") &&
            validDecorationProductId(path.removePrefix("/products/")) &&
            !query["merchantId"].isNullOrBlank() && query.keys.all { it in setOf("merchantId", "optionId") }
        require((method == "GET" && path == "/cart" && body == null) ||
            (method == "POST" && path == "/products/search") || productRead)
        return http.request(coreBase(), method, "/api/core/v1$path", body,
            token = accessToken() ?: throw SessionExpired(), query = query)
    }

    suspend fun approval(path: String): JsonElement {
        require(path.startsWith("/payment-approvals/") && validId(path.removePrefix("/payment-approvals/")))
        return http.request(payBase(), "GET", "/api/pay/v1$path", token = accessToken() ?: throw SessionExpired())
    }
}

/** The shared HTTP transport accepts raw segments and performs percent encoding itself. */
internal fun validDecorationProductId(value: String): Boolean = value.isNotBlank() && value !in setOf(".", "..") &&
    value.none { it == '/' || it == '\\' || it.isISOControl() }
