package com.copenhagen.wallet.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PayContractTest {
    @Test fun approvalListAcceptsNullProductOption() {
        // 실제 승인 목록 응답과 같은 구조: 옵션 없는 상품은 option: null로 내려온다.
        val response = """{
            "approvalId":"approval-id","merchantName":"테스트 상점","checkoutId":"checkout-id",
            "summary":[{"name":"테스트 상품","option":null,"quantity":1,"amountMinor":61800}],
            "amountMinor":64800,"currency":"KRW","status":"PENDING","requiredAction":"BIOMETRIC",
            "expiresAt":"2026-09-18T07:55:31.954920Z"
        }"""
        val page = json.parseToJsonElement("""{"items":[$response]}""").read<Items<Approval>>()
        val approval = page.items.single()
        assertNull(approval.summary.single().option)
        assertEquals("테스트 상품", approval.summary.single().name)
        assertEquals(64800L, approval.amountMinor)
    }
    @Test fun approvalDetailAcceptsTotalAmountMinor() {
        val response = json.parseToJsonElement("""{
            "approvalId":"approval-id","merchantName":"테스트 상점","checkoutId":"checkout-id",
            "checkoutHash":"sha256:test",
            "summary":[{"name":"테스트 상품","option":null,"quantity":1,"amountMinor":61800}],
            "totalAmountMinor":61800,"currency":"KRW","selectedPaymentMethodId":"card-id",
            "status":"PENDING","requiredAction":"PIN","expiresAt":"2026-09-18T08:27:49.012787Z",
            "biometricChallenge":null
        }""")
        val approval = response.read<Approval>()
        assertEquals(61800L, approval.amountMinor)
        assertEquals("KRW", approval.currency)
        assertEquals("card-id", approval.selectedPaymentMethodId)
        assertEquals("PIN", approval.requiredAction)
        assertNull(approval.summary.single().option)
        assertNull(approval.biometricChallenge)
        // 상품 금액이 있어도 승인 총액이 누락된 응답은 허용하지 않는다.
        val missingTotal = JsonObject(response.jsonObject - "totalAmountMinor")
        assertTrue(runCatching { missingTotal.read<Approval>() }.exceptionOrNull() is kotlinx.serialization.SerializationException)
    }
    @Test fun productOptionCanBeAbsentEmptyOrPresent() {
        assertNull(json.decodeFromString<ItemSummary>("""{"name":"상품"}""").option)
        assertEquals("", json.decodeFromString<ItemSummary>("""{"name":"상품","option":""}""").option)
        assertEquals("270mm", json.decodeFromString<ItemSummary>("""{"name":"상품","option":"270mm"}""").option)
        assertTrue(runCatching { json.decodeFromString<ItemSummary>("""{"option":null}""") }.isFailure)
    }
    @Test fun tokenizeHasOnlyFourFields() {
        val body = tokenizeBody("4111-1111 1111 1111", 12, 2099, "123")
        assertEquals(setOf("cardNumber", "expiryMonth", "expiryYear", "cvc"), body.keys)
        assertEquals("4111111111111111", body.getValue("cardNumber").jsonPrimitive.content)
        assertEquals(12, body.getValue("expiryMonth").jsonPrimitive.int)
    }
    @Test fun payGetsTokenNotCardNumberOrCvc() {
        val token = TokenizedCard("mock-pg", "opaque-reference", "VISA", "1111", 12, 2099)
        val body = registerCardBody(token)
        assertEquals(setOf("tokenizationResult"), body.keys)
        val nested = body.getValue("tokenizationResult").jsonObject
        assertEquals(setOf("provider", "providerTokenRef", "brand", "last4", "expiryMonth", "expiryYear"), nested.keys)
        assertEquals("opaque-reference", nested.getValue("providerTokenRef").jsonPrimitive.content)
        assertFalse(body.toString().contains("cardNumber"))
        assertFalse(body.toString().contains("cvc"))
    }
    @Test fun pinApprovalContainsNoCardSelection() {
        val body = pinApprovalBody("123456")
        assertEquals(setOf("authentication"), body.keys)
        assertEquals(setOf("method", "pin"), body.getValue("authentication").jsonObject.keys)
        assertEquals("PIN", body.getValue("authentication").jsonObject.getValue("method").jsonPrimitive.content)
    }
    @Test fun biometricApprovalDoesNotResendChallengeOrDevice() {
        val body = biometricApprovalBody("challenge-id", "der-signature")
        assertEquals(setOf("authentication"), body.keys)
        assertEquals(setOf("method", "challengeId", "assertion"), body.getValue("authentication").jsonObject.keys)
    }
    @Test fun optionalCardMetadataIsNotInvented() {
        val card = json.decodeFromString<Card>("""{"paymentMethodId":"id","isDefault":true,"status":"ACTIVE"}""")
        assertNull(card.last4); assertNull(card.brand); assertTrue(card.active)
    }
    @Test fun ignoresOnlyUnknownResponseFields() {
        val card = json.decodeFromString<Card>("""{"paymentMethodId":"id","isDefault":false,"status":"ACTIVE","provider":"mock-pg"}""")
        assertEquals("id", card.id)
        assertTrue(runCatching { json.decodeFromString<Card>("""{"isDefault":false,"status":"ACTIVE"}""") }.isFailure)
    }
    @Test fun paymentResultRequiresServerState() {
        val result = json.decodeFromString<ApprovalResult>("""{"approvalId":"id","status":"APPROVED","credentialId":"secret","checkoutMandate":"secret"}""")
        assertEquals("APPROVED", result.status)
        assertNotEquals(statusLabel(result.status), statusLabel("COMPLETED"))
    }
    @Test fun rawTokenIsRedactedFromToString() {
        assertFalse(TokenizedCard("mock-pg", "secret-reference", "VISA", "1111", 12, 2099).toString().contains("secret-reference"))
    }
}
