package com.copenhagen.wallet.data

import org.junit.Assert.*
import org.junit.Test

class ApiErrorMessageTest {
    @Test fun invalidOrMissingRequestKeyIsNotDescribedAsDifferentRequestContents() {
        val conflict = ApiError(409, "IDEMPOTENCY_CONFLICT").userMessage()
        for (code in listOf("IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_INVALID")) {
            val message = ApiError(400, code).userMessage()
            assertNotEquals(conflict, message)
            assertFalse(message.contains("이전 요청"))
            assertFalse(message.contains(code))
        }
        assertTrue(conflict.contains("이전 요청과 내용이 달라요"))
    }

    @Test fun sameHttpConflictHasDifferentActionableMessages() {
        assertTrue(ApiError(409, "PAYMENT_METHOD_ALREADY_REGISTERED").userMessage().contains("이미 등록된 카드"))
        assertTrue(ApiError(409, "PAYMENT_METHOD_INACTIVE").userMessage().contains("다른 카드"))
        assertTrue(ApiError(409, "ADDRESS_LIMIT_EXCEEDED").userMessage().contains("20개"))
        assertTrue(ApiError(409, "PIN_ALREADY_REGISTERED").userMessage().contains("설정에서 변경"))
        assertEquals("요청 상태가 변경되었어요. 다시 확인해 주세요.", ApiError(409, "STATE_CONFLICT").userMessage())
    }

    @Test fun accountAndPinErrorsKeepTheirContractMeaning() {
        assertEquals("이미 사용 중인 이메일이에요.", ApiError(409, "AUTH_EMAIL_DUPLICATED").userMessage())
        assertEquals("이 계정은 사용할 수 없어요.", ApiError(403, "AUTH_ACCOUNT_UNAVAILABLE").userMessage())
        assertEquals("결제 PIN을 먼저 등록해 주세요.", ApiError(404, "PIN_NOT_REGISTERED").userMessage())
        assertEquals("현재 PIN과 다른 PIN을 입력해 주세요.", ApiError(400, "PIN_MUST_DIFFER").userMessage())
        assertEquals("PIN이 일치하지 않아요.", ApiError(422, "PIN_INVALID").userMessage())
    }
}
