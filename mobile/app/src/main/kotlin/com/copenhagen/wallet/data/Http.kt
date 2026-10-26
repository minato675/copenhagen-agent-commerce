package com.copenhagen.wallet.data

import android.util.Log
import com.copenhagen.wallet.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class ApiError(val status: Int, val code: String) : Exception(code)
fun Throwable.userMessage(): String = when (this) {
    is ApiError -> when {
        code in setOf("INVALID_CARD_DATA") -> "카드 정보를 확인해 주세요."
        code == "IDEMPOTENCY_CONFLICT" -> "이전 요청과 내용이 달라요. 결과를 확인한 뒤 다시 진행해 주세요."
        code in setOf("IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID") -> "요청 정보를 확인하지 못했어요. 다시 시도해 주세요."
        code == "AUTH_EMAIL_DUPLICATED" -> "이미 사용 중인 이메일이에요."
        code == "AUTH_ACCOUNT_UNAVAILABLE" -> "이 계정은 사용할 수 없어요."
        code == "ADDRESS_LIMIT_EXCEEDED" -> "배송지는 20개까지 등록할 수 있어요."
        code == "ADDRESS_NOT_FOUND" -> "이 배송지는 사용할 수 없어요. 다시 선택해 주세요."
        code == "CART_VERSION_CONFLICT" -> "장바구니가 변경되었어요. 구매 요청을 다시 확인해 주세요."
        code == "PURCHASE_REQUEST_EXPIRED" -> "배송지 선택 요청이 만료되었어요."
        code == "PAYMENT_METHOD_ALREADY_REGISTERED" -> "이미 등록된 카드예요. 카드 목록을 확인해 주세요."
        code == "PAYMENT_METHOD_INACTIVE" -> "사용할 수 없는 카드예요. 다른 카드를 선택해 주세요."
        code == "PIN_ALREADY_REGISTERED" -> "이미 PIN이 등록되어 있어요. 설정에서 변경해 주세요."
        code == "PIN_NOT_REGISTERED" -> "결제 PIN을 먼저 등록해 주세요."
        code == "PIN_MUST_DIFFER" -> "현재 PIN과 다른 PIN을 입력해 주세요."
        code.contains("LOGIN_ID") || code.contains("DUPLICATE_LOGIN") -> "이미 사용 중인 아이디예요."
        code.contains("EMAIL") -> "이메일을 확인해 주세요."
        code.contains("PIN") && status == 422 -> "PIN이 일치하지 않아요."
        status == 401 -> "로그인 정보를 확인해 주세요."
        status == 423 -> "계정이 잠겼어요. 잠시 후 다시 시도해 주세요."
        status == 429 -> "인증이 일시적으로 잠겼어요. 잠시 후 다시 시도해 주세요."
        status == 410 -> "요청이 만료되었어요."
        status == 409 -> "요청 상태가 변경되었어요. 다시 확인해 주세요."
        status == 403 -> "요청에 접근할 수 없어요."
        status == 404 -> "요청을 찾을 수 없어요."
        else -> "요청을 처리하지 못했어요. 다시 시도해 주세요."
    }
    is IOException -> "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."
    is kotlinx.serialization.SerializationException -> "서버 응답을 확인할 수 없어요."
    is IllegalArgumentException, is IllegalStateException -> message ?: "입력 정보를 확인해 주세요."
    else -> "요청을 처리하지 못했어요. 다시 시도해 주세요."
}

/** 요청·응답 상세는 디버그 빌드에서 민감한 값을 가려 기록한다. */
class Http(client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false).build(),
    private val log: (String) -> Unit = { if (BuildConfig.DEBUG) Log.d("CopenhagenHttp", it) }
) {
    private val client = client.newBuilder().retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            chain.request().tag(ReadAttempts::class.java)?.next()
            val response = chain.proceed(chain.request())
            // OkHttp 4.12 otherwise replays 503 + Retry-After: 0 even with connection retries off.
            // Read connection recovery also implicitly retries 408 without a backoff hint.
            val retryAfter = response.header("Retry-After")
            val immediate = retryAfter?.trim()?.toLongOrNull() == 0L
            val oversized = retryAfter?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { (it.toLongOrNull() ?: Long.MAX_VALUE) > Int.MAX_VALUE } == true
            if (response.code in setOf(408, 503) && oversized)
                response.newBuilder().header("Retry-After", Int.MAX_VALUE.toString()).build()
            else if ((response.code == 503 && immediate) || (response.code == 408 && (retryAfter == null || immediate)))
                response.newBuilder().header("Retry-After", "1").build()
            else response
        }.build()
    // Reads can recover a stale pooled connection or try another DNS address. Writes cannot.
    private val readClient = this.client.newBuilder().retryOnConnectionFailure(true).build()

    private class ReadAttempts {
        var count = 0; private set
        fun next() {
            // Count actual HTTP sends too, including OkHttp's internal connection recovery.
            if (count >= ReadRetry.MAX_RETRIES + 1) throw ProtocolException("Read attempt limit reached")
            count++
        }
    }

    suspend fun request(
        base: String, method: String, path: String, body: JsonElement? = null,
        token: String? = null, key: String? = null,
        headers: Map<String, String> = emptyMap(), query: Map<String, String> = emptyMap()
    ): JsonElement = withContext(Dispatchers.IO) {
        require(base.isNotBlank()) { "서버 주소를 설정해 주세요." }
        val url = base.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).apply {
            header("Accept", "application/json")
            token?.let { header("Authorization", "Bearer $it") }
            key?.let { header("Idempotency-Key", it) }
            headers.forEach { (name, value) -> header(name, value) }
            val payload = if (method in setOf("POST", "PUT", "PATCH"))
                (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null
            method(method, payload)
        }.build()
        val loggedPath = if ("/notification-installations/" in url.encodedPath)
            url.encodedPath.substringBefore("/notification-installations/") + "/notification-installations/***"
        else url.encodedPath
        val endpoint = "$method $loggedPath"
        val startedAt = System.nanoTime()
        log("--> $endpoint")
        if (BuildConfig.DEBUG) {
            val requestHeaders = buildJsonObject {
                request.headers.forEach { (name, value) -> put(name, value) }
                (request.header("Content-Type") ?: request.body?.contentType()?.toString())?.let { put("Content-Type", it) }
            }
            val requestQuery = JsonObject(url.queryParameterNames.associateWith { name ->
                JsonArray(url.queryParameterValues(name).map { JsonPrimitive(it) })
            })
            log("--> $endpoint headers=${requestHeaders.redactedForLog()}")
            log("--> $endpoint query=${requestQuery.redactedForLog()}")
            if (request.body != null) log("--> $endpoint body=${body?.redactedForLog() ?: "<empty>"}")
        }
        execute(request, endpoint, startedAt, retryRead = ReadRetry.allows(method, path))
    }

    private suspend fun execute(request: Request, endpoint: String, startedAt: Long, retryRead: Boolean): JsonElement {
        // A key alone does not make PIN checks, credential enrollment or payments safe to replay.
        val attempts = if (retryRead) ReadAttempts() else null
        val boundedRequest = if (attempts != null) request.newBuilder().tag(ReadAttempts::class.java, attempts).build() else request
        val budgetMillis = client.callTimeoutMillis.toLong().takeIf { it > 0L }
            ?.coerceAtMost(ReadRetry.TIMEOUT_MILLIS) ?: ReadRetry.TIMEOUT_MILLIS
        val budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        fun remainingNanos() = budgetNanos - (System.nanoTime() - startedAt)
        var retries = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val call = (if (retryRead) readClient else client).newCall(boundedRequest)
            // All attempts and backoff share the original deadline, rather than multiplying it.
            if (retryRead) call.timeout().timeout(remainingNanos().coerceAtLeast(1L), TimeUnit.NANOSECONDS)
            var retryAfter: String? = null
            try {
                return call.await().use { response ->
                    retryAfter = response.header("Retry-After")
                    log("<-- $endpoint status=${response.code} ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}ms")
                    val text = response.body?.string().orEmpty()
                    if (BuildConfig.DEBUG) {
                        val loggedBody = if (text.isBlank()) "<empty>" else runCatching {
                            val value = json.parseToJsonElement(text)
                            require(value is JsonObject || value is JsonArray || value == JsonNull)
                            value.redactedForLog().toString()
                        }.getOrDefault("<unstructured response; ${text.length} chars>")
                        val parts = loggedBody.chunked(1000)
                        parts.forEachIndexed { index, part ->
                            val position = if (parts.size > 1) "[${index + 1}/${parts.size}] " else ""
                            log("<-- $endpoint response=$position$part")
                        }
                    }
                    if (!response.isSuccessful) {
                        val error = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                            ?: error?.get("errorCode")?.jsonPrimitive?.contentOrNull.orEmpty()
                        throw ApiError(response.code, code)
                    }
                    if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                // 예외 메시지/스택에는 URL·응답 원문이 포함될 수 있어 유형만 기록한다.
                log("<-- $endpoint failed=${failure.javaClass.simpleName} ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}ms")
                val usedRetries = maxOf(retries, (attempts?.count ?: 1) - 1)
                val waitMillis = if (retryRead) ReadRetry.delayMillis(failure, usedRetries, retryAfter) else null
                if (waitMillis == null || TimeUnit.MILLISECONDS.toNanos(waitMillis) >= remainingNanos()) throw failure
                log("--> $endpoint retry=${usedRetries + 1}/${ReadRetry.MAX_RETRIES} delay=${waitMillis}ms")
                delay(waitMillis)
                if (remainingNanos() <= 0L) throw failure
                retries++
            }
        }
    }
}
private val sensitiveLogFields = setOf(
    "authorization", "proxyauthorization", "cookie", "setcookie", "apikey",
    "password", "currentpassword", "newpassword", "cardpassword", "pin", "currentpin", "newpin",
    "cardnumber", "cvc", "cvv", "token", "accesstoken", "refreshtoken", "providertokenref",
    "assertion", "signature", "privatekey", "secret", "checkoutmandate", "fid", "deviceid", "xdeviceid"
)
private fun JsonElement.redactedForLog(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (name, value) ->
        if (name.filter { it.isLetterOrDigit() }.lowercase() in sensitiveLogFields) JsonPrimitive("***")
        else value.redactedForLog()
    })
    is JsonArray -> JsonArray(map { it.redactedForLog() })
    else -> this
}
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, resource, _ -> resource.close() }
        }
    })
}
