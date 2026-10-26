package com.copenhagen.wallet.data

import kotlinx.serialization.json.*

fun tokenizeBody(number: String, month: Int, year: Int, cvc: String): JsonObject {
    val normalized = normalizeCardNumber(number)
    require(validCardNumber(normalized) && validExpiry(month, year) && validCvc(cvc)) { "카드 정보를 확인해 주세요." }
    return buildJsonObject {
        put("cardNumber", normalized); put("expiryMonth", month); put("expiryYear", year); put("cvc", cvc)
    }
}
fun registerCardBody(token: TokenizedCard) = buildJsonObject {
    put("tokenizationResult", json.encodeToJsonElement(token))
}
fun pinApprovalBody(pin: String): JsonObject {
    require(validPin(pin)) { "PIN 6자리를 입력해 주세요." }
    return buildJsonObject { put("authentication", buildJsonObject { put("method", "PIN"); put("pin", pin) }) }
}
fun biometricApprovalBody(challengeId: String, assertion: String) = buildJsonObject {
    put("authentication", buildJsonObject {
        put("method", "BIOMETRIC"); put("challengeId", challengeId); put("assertion", assertion)
    })
}
