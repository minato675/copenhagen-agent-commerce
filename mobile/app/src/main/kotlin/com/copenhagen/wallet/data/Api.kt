package com.copenhagen.wallet.data

import android.os.Build
import com.copenhagen.wallet.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class SessionExpired : Exception()
inline fun <reified T> JsonElement.read(): T = json.decodeFromJsonElement(this)

/** 인증 갱신은 여기 한 곳에서만 한다. PG 호출에는 Core 토큰을 붙이지 않는다. */
class Api(private val store: Store, private val http: Http = Http()) {
    val decorations = DecorationReads(http, { store.tokens?.accessToken }, { store.core }, { store.pay })
    suspend fun login(loginId: String, password: String) {
        val body = buildJsonObject {
            put("loginId", loginId); put("password", password)
            put("deviceId", store.deviceId); put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        val tokens = http.request(store.core, "POST", "/api/core/v1/auth/login", body).read<Tokens>()
        store.saveTokens(tokens, loginId)
    }
    suspend fun signup(loginId: String, password: String, email: String, name: String) {
        http.request(store.core, "POST", "/api/core/v1/auth/signup", buildJsonObject {
            put("loginId", loginId); put("password", password); put("email", email); put("name", name)
        })
    }
    suspend fun logout() {
        val tokens = store.tokens ?: return
        http.request(store.core, "POST", "/api/core/v1/auth/logout", buildJsonObject { put("refreshToken", tokens.refreshToken) })
    }
    suspend fun core(
        method: String, path: String, body: JsonElement? = null,
        query: Map<String, String> = emptyMap()
    ) = authenticated(store.core, method, "/api/core/v1$path", body, query = query)

    suspend fun coreCommand(method: String, path: String, body: JsonElement?, key: String) =
        authenticated(store.core, method, "/api/core/v1$path", body, key = key)

    /** Order history can negotiate v2 independently of the remaining Core v1 APIs. */
    suspend fun orderHistory(path: String, query: Map<String, String>): JsonElement {
        require(path.matches(Regex("/api/core/v[12]/orders(?:/[a-fA-F0-9-]{36})?")))
        return authenticated(store.core, "GET", path, null, query = query)
    }

    suspend fun pay(
        method: String, path: String, body: JsonElement? = null, key: String? = null,
        query: Map<String, String> = emptyMap(), deviceHeader: Boolean = false
    ) = authenticated(store.pay, method, "/api/pay/v1$path", body, key, query,
        if (deviceHeader) mapOf("X-Device-Id" to store.deviceId) else emptyMap())

    suspend fun tokenize(number: String, month: Int, year: Int, cvc: String, key: String): TokenizedCard {
        check(BuildConfig.DEBUG) { "카드 등록을 사용할 수 없어요." } // 제공된 PG Mock 계약은 개발 환경 전용이다.
        val result = http.request(store.pg, "POST", "/api/pg/v1/payment-methods/tokenize",
            tokenizeBody(number, month, year, cvc), key = key).read<TokenizedCard>()
        check(result.provider == "mock-pg" && result.providerTokenRef.isNotBlank()) { "카드 등록 응답을 확인할 수 없어요." }
        return result
    }
    private suspend fun authenticated(
        base: String, method: String, path: String, body: JsonElement?, key: String? = null,
        query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()
    ): JsonElement {
        val previous = store.tokens ?: throw SessionExpired()
        try {
            return http.request(base, method, path, body, previous.accessToken, key, headers, query)
        } catch (error: ApiError) {
            if (error.status != 401) throw error
        }
        val fresh = try {
            http.request(store.core, "POST", "/api/core/v1/auth/token/refresh", buildJsonObject {
                put("refreshToken", previous.refreshToken)
            }).read<Tokens>().also { store.saveTokens(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiError) {
            if (error.status == 401 || error.status == 403) throw SessionExpired() else throw error
        }
        return try {
            http.request(base, method, path, body, fresh.accessToken, key, headers, query)
        } catch (error: ApiError) {
            if (error.status == 401) throw SessionExpired() else throw error
        }
    }
}
