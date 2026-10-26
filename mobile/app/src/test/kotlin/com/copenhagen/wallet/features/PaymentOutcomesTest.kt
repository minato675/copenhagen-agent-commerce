package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PaymentOutcomesTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val purchaseId = "22222222-2222-4222-8222-222222222222"
    private val otherId = "33333333-3333-4333-8333-333333333333"
    private val approval = Approval(id, amountMinor = 3000, currency = "KRW", status = "APPROVED",
        expiresAt = "2099-01-01T00:00:00Z")
    private fun purchase(vararg states: String) = Purchase(purchaseId, "COMPLETED", id, 1,
        "2099-01-01T00:00:00Z", "SHOW_RESULT", merchantCheckouts = states.mapIndexed { index, state ->
            PurchaseCheckout("merchant-$index", id, state, if (index == 0) 1000 else 2000, "KRW")
        })
    private class Harness(var value: Purchase) {
        val paths = mutableListOf<String>()
        var intercept: (suspend (String) -> JsonElement?)? = null
        val outcomes = PaymentOutcomes { path ->
            paths += path
            intercept?.invoke(path) ?: if (path == "/purchase-requests") json.encodeToJsonElement(listOf(value))
                else json.encodeToJsonElement(value)
        }
    }

    @Test fun approvedSessionUsesVerifiedPurchaseDetailForCompletionAndCachesItsReference() = runTest {
        val h = Harness(purchase("COMPLETED", "COMPLETED"))
        h.outcomes.load(approval)
        assertEquals(listOf("/purchase-requests", "/purchase-requests/$purchaseId"), h.paths)
        assertEquals("COMPLETED", h.outcomes.statusFor(id))
        assertEquals("APPROVED", approval.status)
        h.outcomes.load(approval)
        assertEquals(1, h.paths.count { it == "/purchase-requests" })
        assertNull(h.outcomes.statusFor(otherId))
    }

    @Test fun partialFailureAndPendingMerchantsNeverLookLikeFullSuccess() = runTest {
        val cases = listOf(listOf("COMPLETED", "FAILED") to "PARTIALLY_COMPLETED",
            listOf("FAILED", "FAILED") to "FAILED", listOf("REJECTED", "REJECTED") to "REJECTED",
            listOf("CANCELLED", "CANCELLED") to "CANCELLED", listOf("EXPIRED", "EXPIRED") to "EXPIRED",
            listOf("COMPLETED", "COMPLETING") to null, listOf("COMPLETED", "AWAITING_APPROVAL") to null,
            listOf("COMPLETED", "FUTURE_STATE") to null)
        for ((states, expected) in cases) {
            val h = Harness(purchase(*states.toTypedArray()))
            h.outcomes.load(approval)
            assertEquals(expected, h.outcomes.statusFor(id))
            if (expected != null) assertFalse(paymentResultPending(expected))
        }
    }

    @Test fun listStatusCannotOverrideMoreRecentDetail() = runTest {
        val h = Harness(purchase("COMPLETED", "COMPLETED"))
        h.intercept = { path -> if (path != "/purchase-requests") json.encodeToJsonElement(purchase("COMPLETING", "COMPLETING")) else null }
        h.outcomes.load(approval)
        assertNull(h.outcomes.statusFor(id))
    }

    @Test fun missingOldPurchasesStayUnknownAndShippingEntryCanSupplyTheirReference() = runTest {
        val h = Harness(purchase("COMPLETED", "COMPLETED"))
        h.intercept = { if (it == "/purchase-requests") JsonArray(emptyList()) else null }
        h.outcomes.load(approval)
        assertNull(h.outcomes.statusFor(id)); assertNotNull(h.outcomes.error)
        h.outcomes.remember(h.value)
        h.outcomes.load(approval)
        assertEquals("COMPLETED", h.outcomes.statusFor(id)); assertNull(h.outcomes.error)
    }

    @Test fun wrongPurchaseApprovalCurrencyOrAmountCannotProduceAResult() = runTest {
        val original = purchase("COMPLETED", "COMPLETED")
        for (wrong in listOf(original.copy(purchaseRequestId = otherId),
            original.copy(merchantCheckouts = original.merchantCheckouts.map { it.copy(approvalId = otherId) }),
            original.copy(merchantCheckouts = original.merchantCheckouts.map { it.copy(currency = "USD") }),
            original.copy(merchantCheckouts = original.merchantCheckouts.map { it.copy(totalAmount = 1) }))) {
            val h = Harness(original)
            h.intercept = { if (it != "/purchase-requests") json.encodeToJsonElement(wrong) else null }
            h.outcomes.load(approval)
            assertNull(h.outcomes.statusFor(id)); assertNotNull(h.outcomes.error)
        }
    }

    @Test fun resultReadFailurePreservesApprovalAndDoesNotInventSuccess() = runTest {
        val h = Harness(purchase("COMPLETED", "COMPLETED"))
        h.intercept = { throw IOException() }
        h.outcomes.load(approval)
        assertNull(h.outcomes.statusFor(id)); assertNotNull(h.outcomes.error)
        assertEquals("APPROVED", approval.status)
    }

    @Test fun logoutAndNewApprovalDiscardLateResults() = runTest {
        for (clear in listOf(true, false)) {
            val h = Harness(purchase("COMPLETED", "COMPLETED"))
            val gate = CompletableDeferred<JsonElement>()
            val waiting = CompletableDeferred<Unit>()
            h.intercept = { if (it != "/purchase-requests") { waiting.complete(Unit); gate.await() } else null }
            val old = launch { h.outcomes.load(approval) }
            waiting.await()
            if (clear) h.outcomes.clear() else {
                h.intercept = null
                h.outcomes.load(approval.copy(id = otherId))
            }
            gate.complete(json.encodeToJsonElement(h.value)); old.join()
            assertNull(h.outcomes.statusFor(id)); assertNull(h.outcomes.purchase)
        }
    }

    @Test fun sessionExpiryIsPropagatedInsteadOfHiddenAsAnUnknownPayment() = runTest {
        val h = Harness(purchase("COMPLETED", "COMPLETED"))
        h.intercept = { throw SessionExpired() }
        assertTrue(runCatching { h.outcomes.load(approval) }.exceptionOrNull() is SessionExpired)
    }
}
