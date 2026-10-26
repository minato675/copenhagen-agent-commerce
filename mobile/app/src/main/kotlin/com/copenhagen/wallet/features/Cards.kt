package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*

/** 한 카드 등록 흐름의 메모리 상태. 두 서버의 멱등성 키를 별개로 유지한다. */
class Cards(private val api: Api) {
    var items by mutableStateOf<List<Card>?>(null); private set
    var registered by mutableStateOf<Card?>(null); private set
    var consents by mutableStateOf(setOf<Int>())
    private var number = ""
    private var month = 0
    private var year = 0
    private var cvc = ""
    private var token: TokenizedCard? = null
    private var tokenizeKey = ""
    private var registerKey = ""
    val awaitingPay get() = token != null

    suspend fun load() { items = api.pay("GET", "/payment-methods/cards").read<Items<Card>>().items }
    fun prepare(number: String, month: Int, year: Int, cvc: String) {
        tokenizeBody(number, month, year, cvc) // 같은 입력 검증을 요청 직전에도 사용한다.
        clearDraft()
        this.number = normalizeCardNumber(number); this.month = month; this.year = year; this.cvc = cvc
        tokenizeKey = newKey(); registerKey = newKey()
    }
    suspend fun register() {
        require(consents.size == 5) { "필수 약관에 동의해 주세요." }
        // Pay 응답만 유실된 재시도는 PG 토큰을 재발급하지 않는다.
        val tokenized = token ?: api.tokenize(number, month, year, cvc, tokenizeKey).also {
            token = it; number = ""; cvc = ""
        }
        registered = api.pay("POST", "/payment-methods/cards", registerCardBody(tokenized), registerKey).read<Card>()
        clearDraft()
    }
    suspend fun makeDefault(id: String) {
        api.pay("PATCH", "/payment-methods/cards/$id/default", key = newKey()); load()
    }
    suspend fun delete(id: String) {
        api.pay("DELETE", "/payment-methods/cards/$id", key = newKey()); load()
    }
    fun clearDraft() {
        number = ""; month = 0; year = 0; cvc = ""; token = null
        tokenizeKey = ""; registerKey = ""; consents = emptySet()
    }
    fun clear() { clearDraft(); items = null; registered = null }
}
