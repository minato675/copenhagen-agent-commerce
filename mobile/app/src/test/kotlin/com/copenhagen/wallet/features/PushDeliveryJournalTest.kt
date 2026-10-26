package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import org.junit.Assert.*
import org.junit.Test

class PushDeliveryJournalTest {
    private val approval = "11111111-1111-4111-8111-111111111111"
    private val payment = "22222222-2222-4222-8222-222222222222"
    private fun request(event: String) = PayPush(event, RequestTarget(RequestTarget.Kind.APPROVAL, approval), "")
    private fun result(event: String, status: String = "SUCCESS") = PayPush(event, RequestTarget(RequestTarget.Kind.PAYMENT_RESULT, approval, payment), status)
    @Test fun resendStaysSuppressedAfterProcessRestartAndNotificationDismissal() {
        val journal = PushDeliveryJournal()
        val push = request("creation")
        assertTrue(journal.shouldPost(push)); journal.record(push)
        assertFalse(journal.shouldPost(push))
        assertFalse(PushDeliveryJournal(journal.snapshot()).shouldPost(push))
    }
    @Test fun selectingCardCreatesADifferentEventForSameApproval() {
        val journal = PushDeliveryJournal(); journal.record(request("creation"))
        val afterSelection = request("card-selected")
        assertTrue(journal.shouldPost(afterSelection)); journal.record(afterSelection)
        assertFalse(journal.shouldPost(afterSelection))
    }
    @Test fun handoffApprovalAndResultAreAllDelivered() {
        val journal = PushDeliveryJournal()
        val handoff = PayPush("handoff", RequestTarget(RequestTarget.Kind.PURCHASE, approval), "")
        for (push in listOf(handoff, request("approval"), result("unknown", "UNKNOWN"), result("final"))) {
            assertTrue(journal.shouldPost(push)); journal.record(push)
        }
    }
    @Test fun delayedUnknownAndApprovalCannotReplaceAFinalResult() {
        val journal = PushDeliveryJournal(); journal.record(result("final"))
        val restored = PushDeliveryJournal(journal.snapshot())
        assertFalse(restored.shouldPost(result("late-unknown", "UNKNOWN")))
        assertFalse(restored.shouldPost(request("late-card-selection")))
        assertFalse(restored.shouldPost(result("conflicting-terminal", "FAILED")))
    }
    @Test fun pendingResultCanAdvanceToDefinitiveFailure() {
        val journal = PushDeliveryJournal(); journal.record(result("unknown", "UNKNOWN"))
        assertTrue(journal.shouldPost(result("failed", "FAILED")))
        assertFalse(journal.shouldPost(request("late-approval")))
    }
    @Test fun permissionFailureDoesNotConsumeAnEventBeforePosting() {
        val journal = PushDeliveryJournal(); val push = request("permissions-off")
        repeat(3) { assertTrue(journal.shouldPost(push)) }
        assertTrue(PushDeliveryJournal(journal.snapshot()).shouldPost(push))
    }
    @Test fun journalDoesNotStoreRawIdentifiersOrPaymentStatus() {
        val journal = PushDeliveryJournal(); journal.record(result("private-event", "FAILED"))
        for (value in listOf(approval, payment, "private-event", "FAILED")) assertFalse(journal.snapshot().contains(value))
        assertTrue(PushDeliveryJournal("corrupt").shouldPost(request("new")))
    }
}
