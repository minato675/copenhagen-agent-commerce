package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalsTest {
    private val id = "550e8400-e29b-41d4-a716-446655440000"
    private val otherId = "550e8400-e29b-41d4-a716-446655440001"
    private val pin = pinApprovalBody("123456")
    private fun approval(status: String = "PENDING", id: String = this.id) = Approval(
        id, "오래된 가게 이름도 그대로", "checkout-1", 6_000_000_001L, "KRW", status,
        "PIN", "2099-09-21T12:00:00Z", "card-1", "sha256:review",
        listOf(ItemSummary("상품", "검정 / 270", 2))
    )
    private data class Call(val method: String, val path: String, val body: JsonElement?, val key: String?, val query: Map<String, String>, val device: Boolean)
    private inner class Harness(recoveredId: String? = null) {
        var value = approval()
        var nextStatus = "APPROVED"
        var intercept: (suspend (Call) -> JsonElement?)? = null
        val calls = mutableListOf<Call>()
        val persisted = mutableListOf<String?>()
        val approvals = Approvals(request = { method, path, body, key, query, device ->
            val call = Call(method, path, body, key, query, device)
            calls += call
            intercept?.invoke(call) ?: when {
                method == "GET" && path == "/payment-approvals" -> json.encodeToJsonElement(Items(listOf(value)))
                method == "GET" -> json.encodeToJsonElement(value)
                else -> json.encodeToJsonElement(ApprovalResult(value.id, nextStatus))
            }
        }, recoveredId = recoveredId, persistUnresolved = { persisted += it })
        suspend fun open() { approvals.open(id) }
        fun writes() = calls.filter { it.method == "POST" }
    }

    @Test fun dispatchIsImmediateAndApprovedIsNotCompleted() = runBlocking {
        val h = Harness(); h.open()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.method == "POST") gate.await() else null }
        val task = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.approve(pin) }
        assertEquals(1, h.writes().size)
        assertTrue(h.approvals.submitting)
        assertTrue(h.approvals.uncertain)
        assertEquals(id, h.approvals.unresolvedId)
        assertNotNull(h.approvals.operationStartedAt)
        assertEquals(id, h.persisted.last())
        gate.complete(json.encodeToJsonElement(ApprovalResult(id, "APPROVED")))
        assertEquals("APPROVED", task.await())
        assertEquals("APPROVED", h.approvals.current!!.status)
        assertNotEquals("COMPLETED", h.approvals.result)
        assertFalse(h.approvals.submitting)
        assertFalse(h.approvals.uncertain)
        assertNull(h.approvals.unresolvedId)
        assertNull(h.persisted.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun everyRequiredLatencyScheduleKeepsTruthAndNeverDelaysDispatch() = runTest {
        for (latency in listOf(0L, 80L, 250L, 800L, 2_000L, 8_000L, 30_000L)) {
            val h = Harness(); h.open()
            h.intercept = {
                if (it.method == "POST") {
                    delay(latency)
                    json.encodeToJsonElement(ApprovalResult(id, "COMPLETED"))
                } else null
            }
            val task = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.approve(pin) }
            assertEquals("dispatch at $latency ms schedule", 1, h.writes().size)
            if (latency > 0) {
                assertTrue(h.approvals.submitting)
                assertNull(h.approvals.result)
                assertEquals(id, h.approvals.current!!.id)
                assertEquals(6_000_000_001L, h.approvals.current!!.amountMinor)
                advanceTimeBy(latency - 1)
                assertNull(h.approvals.result)
                advanceTimeBy(1); runCurrent()
            }
            assertEquals("COMPLETED", task.await())
            assertEquals("COMPLETED", h.approvals.result)
            assertFalse(h.approvals.submitting)
            assertNull(h.approvals.unresolvedId)
        }
    }

    @Test fun rapidRepeatedConfirmationSendsOneLogicalOperation() = runBlocking {
        val h = Harness(); h.open()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.method == "POST") gate.await() else null }
        val first = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.approve(pin) }
        repeat(8) { assertTrue(runCatching { h.approvals.approve(pin) }.isFailure) }
        assertEquals(1, h.writes().size)
        gate.complete(json.encodeToJsonElement(ApprovalResult(id, "COMPLETED")))
        first.await()
        assertEquals("COMPLETED", h.approvals.result)
    }

    @Test fun acceptedButPendingResponseKeepsOperationIdentityAndNeedsReconciliation() = runBlocking {
        val h = Harness(); h.open(); h.nextStatus = "PENDING"
        assertEquals("PENDING", h.approvals.approve(pin))
        assertEquals("PENDING", h.approvals.result)
        assertTrue(h.approvals.uncertain)
        assertEquals(id, h.approvals.unresolvedId)
        assertFalse(h.approvals.submitting)
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        assertEquals(1, h.writes().size)
        h.value = approval("COMPLETED")
        assertEquals("COMPLETED", h.approvals.reconcile())
        assertNull(h.approvals.unresolvedId)
    }

    @Test fun unknownResponseRetainsIdentityAndRetryReconcilesBeforeUsingSameKey() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException("lost after acceptance") else null }
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        val first = h.writes().single()
        assertTrue(h.approvals.uncertain)
        assertTrue(h.approvals.canRetry)
        assertFalse(h.approvals.canAuthorize)
        h.intercept = null
        assertEquals("APPROVED", h.approvals.retry())
        val retry = h.writes().last()
        assertEquals(first.key, retry.key)
        assertEquals(first.body, retry.body)
        assertEquals("GET", h.calls[h.calls.size - 2].method)
        assertTrue(h.calls[h.calls.size - 2].device)
    }

    @Test fun reconciliationThatAlreadyCompletedNeverResends() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException() else null }
        runCatching { h.approvals.approve(pin) }
        h.intercept = null; h.value = approval("COMPLETED")
        assertEquals("COMPLETED", h.approvals.retry())
        assertEquals(1, h.writes().size)
        assertFalse(h.approvals.uncertain)
    }

    @Test fun backgroundAndCloseEraseSecretsButRetainUnresolvedOperation() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException() else null }
        runCatching { h.approvals.approve(pin) }
        h.approvals.forgetAuthentication(); h.approvals.clearAttempt(); h.approvals.close()
        assertEquals(id, h.approvals.pendingId)
        assertEquals(id, h.approvals.unresolvedId)
        assertEquals(id, h.approvals.current!!.id)
        assertFalse(h.approvals.canRetry)
        assertTrue(h.approvals.uncertain)
        assertEquals(id, h.persisted.last())
        assertTrue(runCatching { h.approvals.open(otherId) }.isFailure)
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        assertEquals(1, h.writes().size)
    }

    @Test fun restoredUnknownOperationCannotAcquireANewMutationKeyEvenAfterPendingRead() = runBlocking {
        val h = Harness(recoveredId = id)
        assertEquals(id, h.approvals.unresolvedId)
        assertEquals("PENDING", h.approvals.reconcile())
        assertFalse(h.approvals.canAuthorize)
        assertFalse(h.approvals.canRetry)
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        assertTrue(runCatching { h.approvals.reject() }.isFailure)
        assertTrue(runCatching { h.approvals.selectCard("card-2") }.isFailure)
        assertTrue(h.writes().isEmpty())
        h.value = approval("APPROVED")
        h.approvals.reconcile()
        assertFalse(h.approvals.uncertain)
        assertNull(h.persisted.last())
    }

    @Test fun unrecognizedReconciliationStatusCannotDiscardUnresolvedIdentity() = runBlocking {
        val h = Harness(recoveredId = id)
        h.value = approval("PROCESSING")
        assertTrue(runCatching { h.approvals.reconcile() }.isFailure)
        assertTrue(h.approvals.uncertain)
        assertEquals(id, h.approvals.unresolvedId)
        assertEquals(id, h.approvals.pendingId)
        assertFalse(h.approvals.canAuthorize)
        assertTrue(h.persisted.none { it == null })
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        assertTrue(h.writes().isEmpty())
        h.value = approval("COMPLETED")
        assertEquals("COMPLETED", h.approvals.reconcile())
        assertFalse(h.approvals.uncertain)
    }

    @Test fun changedReviewOrAuthenticationCannotReuseUnresolvedOperation() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException() else null }
        runCatching { h.approvals.approve(pin) }
        h.intercept = null
        h.approvals.reconcile()
        assertTrue(runCatching { h.approvals.approve(pinApprovalBody("654321")) }.isFailure)
        h.value = approval().copy(amountMinor = 100)
        assertTrue(runCatching { h.approvals.retry() }.isFailure)
        assertEquals(1, h.writes().size)
        assertTrue(h.approvals.uncertain)
    }

    @Test fun definitivePinFailureAllowsCorrectionAndNewKey() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw ApiError(422, "INVALID_PIN") else null }
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        assertFalse(h.approvals.uncertain)
        assertTrue(h.approvals.canAuthorize)
        h.intercept = null
        h.approvals.approve(pinApprovalBody("654321"))
        assertNotEquals(h.writes().first().key, h.writes().last().key)
    }

    @Test fun rejectedReplayDoesNotProveOriginalUnknownRequestFailed() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException("original response lost") else null }
        runCatching { h.approvals.approve(pin) }
        val key = h.writes().single().key
        h.intercept = { if (it.method == "POST") throw ApiError(422, "INVALID_PIN") else null }
        assertTrue(runCatching { h.approvals.retry() }.isFailure)
        assertEquals(key, h.writes().last().key)
        assertEquals(id, h.approvals.unresolvedId)
        assertTrue(h.approvals.uncertain)
        assertFalse(h.approvals.canAuthorize)
        assertEquals(id, h.persisted.last())
        assertTrue(runCatching { h.approvals.approve(pinApprovalBody("654321")) }.isFailure)
        assertEquals(2, h.writes().size)
        h.intercept = null; h.value = approval("APPROVED")
        assertEquals("APPROVED", h.approvals.reconcile())
        assertNull(h.approvals.unresolvedId)
    }

    @Test fun pinRateLimitRejectsFirstAttemptButCannotResolveAnEarlierUnknownRequest() = runBlocking {
        val rejected = Harness(); rejected.open()
        rejected.intercept = { if (it.method == "POST") throw ApiError(429, "PAYMENT_PIN_LOCKED") else null }
        assertTrue(runCatching { rejected.approvals.approve(pin) }.isFailure)
        assertFalse(rejected.approvals.uncertain)
        assertNull(rejected.approvals.unresolvedId)

        val generic = Harness(); generic.open()
        generic.intercept = { if (it.method == "POST") throw ApiError(429, "RATE_LIMITED") else null }
        runCatching { generic.approvals.approve(pin) }
        assertTrue(generic.approvals.uncertain)

        val replay = Harness(); replay.open()
        replay.intercept = { if (it.method == "POST") throw IOException() else null }
        runCatching { replay.approvals.approve(pin) }
        replay.intercept = { if (it.method == "POST") throw ApiError(429, "PAYMENT_PIN_LOCKED") else null }
        assertTrue(runCatching { replay.approvals.retry() }.isFailure)
        assertTrue(replay.approvals.uncertain)
        assertEquals(id, replay.approvals.unresolvedId)
        assertEquals(replay.writes().first().key, replay.writes().last().key)
    }

    @Test fun rejectTimeoutIsUnknownAndCannotBeRepeatedOrTurnedIntoApproval() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException() else null }
        runCatching { h.approvals.reject() }
        assertTrue(h.approvals.uncertain)
        assertEquals(id, h.approvals.unresolvedId)
        assertFalse(h.approvals.canRetry)
        assertTrue(runCatching { h.approvals.reject() }.isFailure)
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        h.intercept = null; h.value = approval("REJECTED")
        assertEquals("REJECTED", h.approvals.reconcile())
        assertEquals(1, h.writes().size)
    }

    @Test fun malformedOrMismatchedResultIsNeverPresentedAsSuccess() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") json.encodeToJsonElement(ApprovalResult(otherId, "COMPLETED")) else null }
        assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
        assertTrue(h.approvals.uncertain)
        assertNull(h.approvals.result)
        assertEquals("PENDING", h.approvals.current!!.status)
    }

    @Test fun cancelledMutationKeepsUnknownReferenceWithoutInventingFailure() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw CancellationException() else null }
        assertTrue(runCatching { h.approvals.approve(pin) }.exceptionOrNull() is CancellationException)
        assertTrue(h.approvals.uncertain)
        assertFalse(h.approvals.submitting)
        assertEquals(id, h.approvals.unresolvedId)
    }

    @Test fun leavingDuringSubmissionDoesNotCancelOrDiscardReceipt() = runBlocking {
        val h = Harness(); h.open()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.method == "POST") gate.await() else null }
        val task = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.approve(pin) }
        h.approvals.close()
        assertEquals(id, h.approvals.pendingId)
        gate.complete(json.encodeToJsonElement(ApprovalResult(id, "COMPLETED")))
        task.await()
        assertEquals(id, h.approvals.current!!.id)
        assertEquals("COMPLETED", h.approvals.result)
    }

    @Test fun oldReadCannotOverwriteAuthoritativeMutationResult() = runBlocking {
        val h = Harness(); h.open()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.method == "GET") gate.await() else null }
        val read = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.open(id) }
        h.approvals.approve(pin)
        gate.complete(json.encodeToJsonElement(approval()))
        read.await()
        assertEquals("APPROVED", h.approvals.current!!.status)
        assertEquals("APPROVED", h.approvals.result)
    }

    @Test fun logoutDiscardsLateMutationResultAndPrivateState() = runBlocking {
        val h = Harness(); h.open()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.method == "POST") gate.await() else null }
        val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.approvals.approve(pin) } }
        h.approvals.clear()
        gate.complete(json.encodeToJsonElement(ApprovalResult(id, "COMPLETED")))
        assertTrue(task.await().isFailure)
        assertNull(h.approvals.current)
        assertNull(h.approvals.pendingId)
        assertNull(h.approvals.result)
        assertNull(h.approvals.unresolvedId)
    }

    @Test fun historyRetainsContentDuringRefreshAndLateFilterCannotReplaceNewFilter() = runBlocking {
        val h = Harness()
        h.approvals.loadHistory()
        val original = h.approvals.history
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.query["status"] == "APPROVED") gate.await() else null }
        val old = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.loadHistory() }
        assertEquals(original, h.approvals.history)
        h.approvals.historyStatus = "COMPLETED"; h.value = approval("COMPLETED")
        h.approvals.loadHistory()
        gate.complete(json.encodeToJsonElement(Items(listOf(approval("APPROVED")))))
        old.await()
        assertEquals("COMPLETED", h.approvals.history!!.single().status)
    }

    @Test fun receiptHistoryUsesExactAuthoritativeRecordAndPreservesMatchingRowPosition() = runBlocking {
        val h = Harness()
        val other = approval("COMPLETED", otherId)
        h.intercept = { if (it.path == "/payment-approvals") json.encodeToJsonElement(Items(listOf(other, approval("COMPLETED"), approval("APPROVED", "old-filter")))) else null }
        h.approvals.loadHistory()
        h.value = approval("COMPLETED").copy(amountMinor = Long.MAX_VALUE, summary = listOf(ItemSummary("정확한 주문", "선택한 옵션", 3)))
        h.open()
        val prepared = h.approvals.prepareReceiptHistory()
        assertEquals(h.value, prepared)
        assertEquals("COMPLETED", h.approvals.historyStatus)
        assertEquals(listOf(otherId, id), h.approvals.history!!.map { it.id })
        assertEquals(h.value, h.approvals.history!![1])
        h.approvals.close()
        assertNull(h.approvals.current)
        assertEquals(Long.MAX_VALUE, h.approvals.history!![1].amountMinor)
        assertEquals("정확한 주문", h.approvals.history!![1].summary.single().name)
    }

    @Test fun receiptHistoryNeverSynthesizesAResultForUnknownOperation() = runBlocking {
        val h = Harness(); h.open()
        h.intercept = { if (it.method == "POST") throw IOException() else null }
        runCatching { h.approvals.approve(pin) }
        assertNull(h.approvals.prepareReceiptHistory())
        assertNull(h.approvals.history)
        assertTrue(h.approvals.uncertain)
        assertEquals(id, h.approvals.unresolvedId)
    }

    @Test fun receiptHistorySurvivesOlderReadAndExposesReadRefreshState() = runBlocking {
        val h = Harness(); h.value = approval("COMPLETED"); h.open()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { if (it.path == "/payment-approvals") gate.await() else null }
        val old = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.loadHistory() }
        assertTrue(h.approvals.historyRefreshing)
        h.approvals.prepareReceiptHistory()
        assertFalse(h.approvals.historyRefreshing)
        gate.complete(json.encodeToJsonElement(Items<Approval>(emptyList())))
        old.await()
        assertEquals(h.value, h.approvals.history!!.single())
        assertFalse(h.approvals.historyRefreshing)
    }

    @Test fun staleHistoryAndDetailReadsAfterLogoutCannotRestorePrivateData() = runBlocking {
        val h = Harness()
        val gate = CompletableDeferred<JsonElement>()
        h.intercept = { gate.await() }
        val old = async(start = CoroutineStart.UNDISPATCHED) { h.approvals.loadHistory() }
        h.approvals.clear()
        gate.complete(json.encodeToJsonElement(Items(listOf(approval()))))
        old.await()
        assertNull(h.approvals.history)
    }

    @Test fun expiredUnsupportedOrUnselectedApprovalCannotSubmit() = runBlocking {
        for (value in listOf(approval().copy(expiresAt = "2020-01-01T00:00:00Z"),
            approval().copy(currency = "USD"), approval().copy(selectedPaymentMethodId = null), approval("COMPLETED"))) {
            val h = Harness(); h.value = value; h.open()
            assertTrue(runCatching { h.approvals.approve(pin) }.isFailure)
            assertTrue(h.writes().isEmpty())
        }
    }

    @Test fun reviewEqualityCoversAllConsequentialTermsButNotAuthenticationChallenge() {
        val reviewed = approval()
        assertTrue(reviewed.sameReviewAs(reviewed.copy(requiredAction = "BIOMETRIC", biometricChallenge = Challenge("id", "challenge", "2099-01-01T00:00:00Z"))))
        listOf(reviewed.copy(id = otherId), reviewed.copy(merchantName = "다른 상점"), reviewed.copy(checkoutId = "checkout-2"),
            reviewed.copy(checkoutHash = "sha256:new"), reviewed.copy(amountMinor = 10L), reviewed.copy(currency = "USD"),
            reviewed.copy(selectedPaymentMethodId = "card-2"), reviewed.copy(summary = listOf(ItemSummary("다른 상품"))))
            .forEach { assertFalse(reviewed.sameReviewAs(it)) }
    }
}
