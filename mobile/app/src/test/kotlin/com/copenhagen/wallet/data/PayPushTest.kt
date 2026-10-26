package com.copenhagen.wallet.data

import org.junit.Assert.*
import org.junit.Test

class PayPushTest {
    private val approval = "11111111-1111-4111-8111-111111111111"
    private val payment = "22222222-2222-4222-8222-222222222222"
    private val purchase = "33333333-3333-4333-8333-333333333333"
    private fun payload(type: String = "PAYMENT_RESULT", action: String = "SHOW_PAYMENT_RESULT", status: String = "SUCCESS") = mapOf(
        "eventId" to "event-1", "pushId" to "event-1", "type" to type, "nextAction" to action,
        "approvalId" to if (type == "PURCHASE_HANDOFF") "" else approval,
        "paymentId" to if (type == "PAYMENT_RESULT") payment else "",
        "purchaseRequestId" to if (type == "PURCHASE_HANDOFF") purchase else "", "status" to status)

    @Test fun allFourActionsHaveDistinctCorrectDestinations() {
        val handoff = PayPush.fromPayload(payload("PURCHASE_HANDOFF", "OPEN_PURCHASE", ""))!!
        val request = PayPush.fromPayload(payload("APPROVAL_REQUESTED", "OPEN_APPROVAL", ""))!!
        val result = PayPush.fromPayload(payload())!!
        val wait = PayPush.fromPayload(payload(action = "WAIT_FOR_PAYMENT_STATUS", status = "UNKNOWN"))!!
        assertEquals(RequestTarget(RequestTarget.Kind.PURCHASE, purchase), handoff.target)
        assertEquals(RequestTarget(RequestTarget.Kind.APPROVAL, approval), request.target)
        assertEquals(RequestTarget(RequestTarget.Kind.PAYMENT_RESULT, approval, payment), result.target)
        assertEquals(result.target, wait.target)
        assertEquals(3, setOf(handoff.channel, request.channel, result.channel).size)
        assertNotEquals(request.target.identity, result.target.identity)
    }
    @Test fun successFailureAndUnknownUseStatusNotEventType() {
        assertEquals("결제 완료", PayPush.fromPayload(payload())!!.title)
        assertEquals("결제 실패", PayPush.fromPayload(payload(status = "FAILED"))!!.title)
        for (status in listOf("UNKNOWN", "READY", "PROCESSING", "UNRECOGNIZED", "")) {
            val push = PayPush.fromPayload(payload(status = status))!!
            assertFalse(push.terminal)
            assertEquals("결제 결과 확인 중", push.title)
        }
    }
    @Test fun waitActionCannotClaimSuccessEvenWithConflictingStatus() {
        val push = PayPush.fromPayload(payload(action = "WAIT_FOR_PAYMENT_STATUS"))!!
        assertFalse(push.terminal)
        assertEquals("결제 결과 확인 중", push.title)
    }
    @Test fun malformedContractsNeverCreateNotifications() {
        for (field in PayPush.fields) assertNull("missing $field", PayPush.fromPayload(payload() - field))
        for (replacement in listOf(mapOf("eventId" to ""), mapOf("pushId" to "other"),
            mapOf("type" to "APPROVAL_REQUESTED"), mapOf("nextAction" to "OPEN_UNKNOWN"),
            mapOf("approvalId" to "../users"), mapOf("paymentId" to ""), mapOf("purchaseRequestId" to purchase))) {
            assertNull(PayPush.fromPayload(payload() + replacement))
        }
    }
    @Test fun typedFcmDoesNotUseLegacyFallbackButInternalLinksRemainSupported() {
        assertNull(PayPush.fromPayload(mapOf("approvalId" to approval)))
        assertEquals(RequestTarget(RequestTarget.Kind.APPROVAL, approval), RequestTarget.fromPayload(mapOf("approvalId" to approval)))
        assertNull(RequestTarget.fromPayload(mapOf("approvalId" to approval, "nextAction" to "UNKNOWN")))
    }
    @Test fun aNotificationResultNeverTurnsApprovedOrUnknownIntoCompleted() {
        assertEquals("결제가 완료되었어요", paymentResultTitle("COMPLETED"))
        assertEquals("결제를 완료하지 못했어요", paymentResultTitle("INVALIDATED"))
        for (status in listOf("APPROVED", "PENDING", "PAYMENT_METHOD_REQUIRED", "UNKNOWN", "UNRECOGNIZED")) {
            assertTrue(paymentResultPending(status))
            assertEquals("결제 결과 확인 중", paymentResultTitle(status))
        }
        for (status in listOf("COMPLETED", "INVALIDATED", "REJECTED", "EXPIRED")) assertFalse(paymentResultPending(status))
    }
}
