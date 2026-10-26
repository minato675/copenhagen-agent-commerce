package com.copenhagen.wallet.data

import com.copenhagen.wallet.features.NotificationEntrySession
import org.junit.Assert.*
import org.junit.Test

class RequestTargetTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    @Test fun firebaseEmptyApprovalIdStillOpensPurchase() {
        val target = RequestTarget.fromPayload(mapOf("type" to "PURCHASE_HANDOFF", "nextAction" to "OPEN_PURCHASE",
            "purchaseRequestId" to id, "approvalId" to "", "paymentId" to "", "status" to ""))!!
        assertEquals(RequestTarget.Kind.PURCHASE, target.kind)
        assertEquals("purchases/$id", target.identity)
    }
    @Test fun approvalPayloadAndLegacyIdRemainCompatible() {
        for (data in listOf(mapOf("approvalId" to id), mapOf("type" to "APPROVAL_REQUESTED", "approvalId" to id, "purchaseRequestId" to ""))) {
            assertEquals(RequestTarget(RequestTarget.Kind.APPROVAL, id), RequestTarget.fromPayload(data))
        }
    }
    @Test fun conflictingOrInvalidReferencesNeverOpenAnyRequest() {
        for (data in listOf(mapOf("approvalId" to id, "purchaseRequestId" to id),
            mapOf("type" to "PURCHASE_HANDOFF", "approvalId" to id),
            mapOf("type" to "PURCHASE_HANDOFF", "purchaseRequestId" to "../addresses"),
            mapOf("approvalId" to "invalid"), emptyMap())) assertNull(RequestTarget.fromPayload(data))
    }
    @Test fun purchaseAndApprovalWithSameUuidCannotSharePresentationIdentity() {
        val entry = NotificationEntrySession()
        val purchase = RequestTarget(RequestTarget.Kind.PURCHASE, id)
        val approval = RequestTarget(RequestTarget.Kind.APPROVAL, id)
        assertTrue(entry.start(purchase.identity))
        assertFalse(entry.start(approval.identity))
        assertEquals(purchase.identity, entry.activeId)
    }
    @Test fun allPurchaseStatusesHavePlainLanguageLabelsIncludingUnknown() {
        for (status in listOf("PENDING_ADDRESS", "CREATING_CHECKOUTS", "AWAITING_APPROVAL", "COMPLETING", "COMPLETED",
            "PARTIALLY_COMPLETED", "CANCELLED", "EXPIRED", "REJECTED", "FAILED", "UNRECOGNIZED")) {
            assertFalse(purchaseStatusLabel(status).contains(status))
            assertTrue(purchaseStatusLabel(status).any { it in '가'..'힣' })
        }
    }
}
