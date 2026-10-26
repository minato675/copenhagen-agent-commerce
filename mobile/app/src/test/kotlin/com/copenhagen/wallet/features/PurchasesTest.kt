package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PurchasesTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val cartId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val address = Address("88888888-8888-4888-8888-888888888888", "수령인", "010-0000-0000", "03000", "테스트 주소", isDefault = true)
    private fun purchase(status: String = "PENDING_ADDRESS", version: Int = 1) = Purchase(id, status, cartId, version,
        "2099-01-01T00:00:00Z", when (status) { "PENDING_ADDRESS" -> "SELECT_SHIPPING_ADDRESS"; "CREATING_CHECKOUTS" -> "WAIT"; else -> "SHOW_RESULT" })
    private inner class Harness(restored: PurchaseResume? = null) {
        var server = purchase()
        var saved = restored
        var failPersistence = false
        var sent = 0
        var waitMillis = 0L
        var failure: Exception? = null
        var acceptBeforeFailure = false
        var response: Purchase? = null
        var readGate: CompletableDeferred<Unit>? = null
        var addressRows = listOf(address)
        val commands = mutableListOf<Pair<JsonElement?, String?>>()
        val feature = Purchases(PurchaseTransport { method, path, body, key ->
            when {
                method == "POST" -> {
                    assertEquals("/purchase-requests/$id/resume", path)
                    assertNotNull(saved)
                    sent++; commands += body to key
                    delay(waitMillis)
                    if (acceptBeforeFailure) server = purchase("CREATING_CHECKOUTS")
                    failure?.let { throw it }
                    server = response ?: purchase("CREATING_CHECKOUTS")
                    json.encodeToJsonElement(server)
                }
                path == "/purchase-requests" -> json.encodeToJsonElement(listOf(server))
                path == "/addresses" -> json.encodeToJsonElement(Items(addressRows))
                path == "/cart" -> json.encodeToJsonElement(CartView(cartId, 1, emptyList(), CartSummary(0,0,0,0,"KRW")))
                else -> { val snapshot = server; readGate?.await(); json.encodeToJsonElement(snapshot) }
            }
        }, restored) { if (failPersistence) error("Cannot persist"); saved = it }
        suspend fun ready() { feature.open(id); feature.select(address.id) }
    }

    @Test fun defaultAddressStartsSelectedButOpeningNeverSendsTheSelection() = runTest {
        val h = Harness()
        h.addressRows = listOf(address.copy(id = cartId, isDefault = false), address)
        h.feature.open(id)
        assertEquals(address.id,h.feature.selectedId); assertEquals(0, h.sent)
        h.feature.select(address.id)
        assertEquals(address, h.feature.selected); assertEquals(0, h.sent)
    }
    @Test fun manualChoiceSurvivesReloadInsteadOfResettingToDefault() = runTest {
        val h=Harness()
        val other=address.copy(id=cartId,isDefault=false)
        h.addressRows=listOf(address,other)
        h.feature.open(id); h.feature.select(other.id); h.feature.refresh()
        assertEquals(other.id,h.feature.selectedId); assertEquals(0,h.sent)
    }
    @Test fun noDefaultDoesNotChooseAnArbitraryAddress() = runTest {
        val h=Harness(); h.addressRows=listOf(address.copy(isDefault=false))
        h.feature.open(id)
        assertNull(h.feature.selectedId); assertEquals(0,h.sent)
    }
    @Test fun dispatchAndTruthAreIndependentOfEveryAnimationLatency() = runTest {
        for (latency in listOf(0L,80,250,800,2000,8000,30000)) {
            val h = Harness(); h.ready(); h.waitMillis = latency
            val job = launch { h.feature.confirm() }; runCurrent()
            assertEquals(1, h.sent)
            if (latency > 0) { assertTrue(h.feature.submitting); assertNull(h.feature.confirmedSelection); assertNotNull(h.saved) }
            advanceUntilIdle(); job.join()
            assertFalse(h.feature.submitting); assertFalse(h.feature.uncertain)
            assertEquals("CREATING_CHECKOUTS", h.feature.current!!.status)
            assertEquals(address, h.feature.confirmedSelection); assertNull(h.saved)
        }
    }
    @Test fun repeatedConfirmationsSendOneCommandWithExactVersionAddressAndUuidKey() = runTest {
        val h = Harness(); h.ready(); h.waitMillis = 8000
        val first = launch { h.feature.confirm() }; runCurrent()
        repeat(8) { h.feature.confirm() }
        advanceUntilIdle(); first.join()
        assertEquals(1, h.sent)
        assertEquals(buildJsonObject { put("addressId", address.id); put("cartVersion", 1) }, h.commands.single().first)
        assertTrue(validId(h.commands.single().second!!))
    }
    @Test fun responseLostAfterAcceptanceReconcilesWithoutAnotherPost() = runTest {
        val h = Harness(); h.ready(); h.failure = IOException(); h.acceptBeforeFailure = true
        assertTrue(runCatching { h.feature.confirm() }.isFailure)
        assertTrue(h.feature.uncertain); assertNotNull(h.saved)
        h.feature.retry()
        assertEquals(1,h.sent); assertNull(h.saved); assertFalse(h.feature.uncertain)
    }
    @Test fun offlineRetryReusesExactJournalAfterProcessRestart() = runTest {
        val h = Harness(); h.ready(); h.failure = IOException()
        assertTrue(runCatching { h.feature.confirm() }.isFailure)
        val saved = h.saved!!
        val restored = Harness(json.decodeFromString<PurchaseResume>(json.encodeToString(PurchaseResume.serializer(),saved)))
        restored.feature.open(id)
        assertEquals(0,restored.sent); assertEquals(address.id, restored.feature.selectedId)
        assertTrue(runCatching { restored.feature.select(address.id) }.isFailure)
        restored.feature.retry()
        assertEquals(h.commands.single(), restored.commands.single()); assertNull(restored.saved)
    }
    @Test fun serverErrorNeverAutomaticallyRetriesOrInventsSuccess() = runTest {
        val h = Harness(); h.ready(); h.failure = ApiError(500,"INTERNAL_ERROR")
        assertTrue(runCatching { h.feature.confirm() }.isFailure)
        assertEquals(1,h.sent); assertTrue(h.feature.uncertain); assertNull(h.feature.confirmedSelection)
    }
    @Test fun changedCartBeforeCommitStopsTheWrite() = runTest {
        val h = Harness(); h.ready(); h.server = purchase(version=2)
        assertTrue(runCatching { h.feature.confirm() }.isFailure)
        assertEquals(0,h.sent); assertNull(h.saved)
    }
    @Test fun expiredOrAlreadyContinuedRequestsCannotChooseAnotherAddress() = runTest {
        for (server in listOf(purchase().copy(expiresAt="2000-01-01T00:00:00Z"),purchase("CREATING_CHECKOUTS"))) {
            val h = Harness(); h.ready(); h.server = server
            assertTrue(runCatching { h.feature.confirm() }.isFailure); assertEquals(0,h.sent)
        }
    }
    @Test fun unavailableAddressAllowsCorrectionAfterDefinitiveFailure() = runTest {
        val h = Harness(); h.ready(); h.failure = ApiError(404,"ADDRESS_NOT_FOUND")
        assertTrue(runCatching { h.feature.confirm() }.isFailure)
        assertNull(h.feature.selectedId); assertNull(h.saved); assertFalse(h.feature.uncertain)
    }
    @Test fun mismatchedResponseDoesNotClearTheRecoveryCommand() = runTest {
        val h = Harness(); h.ready(); h.response = purchase("CREATING_CHECKOUTS").copy(purchaseRequestId=cartId)
        assertTrue(runCatching { h.feature.confirm() }.isFailure)
        assertTrue(h.feature.uncertain); assertNotNull(h.saved); assertNull(h.feature.confirmedSelection)
    }
    @Test fun unrecognizedResponseStaysUncertainWithoutRawStatusLabels() = runTest {
        val h = Harness(); h.ready(); h.response = purchase("FUTURE_STATE")
        h.feature.confirm()
        assertTrue(h.feature.uncertain); assertNotNull(h.saved)
        assertEquals("상태 확인 필요",purchaseStatusLabel(h.feature.current!!.status))
    }
    @Test fun failedPersistencePreventsDispatch() = runTest {
        val h = Harness(); h.ready(); h.failPersistence = true
        assertTrue(runCatching { h.feature.confirm() }.isFailure); assertEquals(0,h.sent)
    }
    @Test fun closeDuringDispatchDoesNotCancelItsResult() = runTest {
        val h = Harness(); h.ready(); h.waitMillis=8000
        val job = launch { h.feature.confirm() }; runCurrent(); h.feature.close()
        advanceUntilIdle(); job.join()
        assertEquals(1,h.sent); assertEquals("CREATING_CHECKOUTS",h.feature.current!!.status); assertNull(h.saved)
    }
    @Test fun logoutDiscardsLateReadsAndMutationResults() = runTest {
        val h = Harness(); h.ready(); h.waitMillis=8000
        val job = launch { h.feature.confirm() }; runCurrent(); h.feature.clear()
        advanceUntilIdle(); job.join(); assertNull(h.feature.current); assertNull(h.feature.addresses)
        val read = Harness(); read.readGate=CompletableDeferred()
        val task=launch { read.feature.open(id) }; runCurrent(); read.feature.clear(); read.readGate!!.complete(Unit)
        task.join(); assertNull(read.feature.current)
    }
    @Test fun cancelKeepsJournalAndDoesNotPermitDifferentSelection() = runTest {
        val h = Harness(); h.ready(); h.waitMillis=30000
        val job=launch { h.feature.confirm() }; runCurrent(); job.cancelAndJoin()
        assertTrue(h.feature.uncertain); assertNotNull(h.saved)
        assertTrue(runCatching { h.feature.select(address.id) }.isFailure)
    }
    @Test fun olderReadCannotReplaceConfirmedResumeResponse() = runTest {
        val h = Harness(); h.ready()
        val gate = CompletableDeferred<Unit>(); h.readGate = gate
        val old = launch { h.feature.open(id) }; runCurrent()
        h.readGate = null
        h.feature.confirm()
        gate.complete(Unit); old.join()
        assertEquals("CREATING_CHECKOUTS", h.feature.current!!.status)
        assertEquals(address, h.feature.confirmedSelection)
    }
    @Test fun resumeResponseWithoutAddressesDecodesAndOnlyServerLinkedApprovalsAreOffered() {
        val value=json.decodeFromString<Purchase>("""{"purchaseRequestId":"$id","cartId":"$cartId","cartVersion":1,
            "expiresAt":"2099-01-01T00:00:00Z","status":"AWAITING_APPROVAL","nextAction":"OPEN_APPROVAL",
            "merchantCheckouts":[{"merchantId":"merchant","approvalId":"$id","status":"AWAITING_APPROVAL","totalAmount":42800,"currency":"KRW"}]}""")
        assertEquals(listOf(id),value.approvalIds)
        assertTrue(value.copy(nextAction="WAIT").approvalIds.isEmpty())
    }
}
