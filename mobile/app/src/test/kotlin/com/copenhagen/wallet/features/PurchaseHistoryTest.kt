package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseHistoryTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val approvalId = "22222222-2222-4222-8222-222222222222"
    private val otherId = "33333333-3333-4333-8333-333333333333"
    private fun purchase(vararg states: String) = Purchase(id, "COMPLETED", otherId, 1,
        "2099-01-01T00:00:00Z", "SHOW_RESULT", merchantCheckouts = states.mapIndexed { index, state ->
            PurchaseCheckout("merchant-$index", approvalId, state, if (index == 0) 24300 else 18500, "KRW")
        })
    private fun approval() = Approval(approvalId, merchantName = "코펜하겐 스토어", amountMinor = 42800,
        currency = "KRW", status = "APPROVED", expiresAt = "2099-01-01T00:00:00Z", merchantCount = 2,
        merchants = listOf(ApprovalMerchant("merchant-0", "코펜하겐 스토어", "checkout-a", "hash-a",
            listOf(ItemSummary("토트백")), 24300), ApprovalMerchant("merchant-1", "노르딕 문구점", "checkout-b", "hash-b",
            listOf(ItemSummary("위클리 노트")), 18500)))

    private inner class Harness(var value: Purchase = purchase("COMPLETED", "FAILED")) {
        var payValue = approval()
        val calls = mutableListOf<String>()
        var coreIntercept: (suspend (String) -> JsonElement?)? = null
        var payIntercept: (suspend (String) -> JsonElement?)? = null
        val history = PurchaseHistory(core = { path ->
            calls += "core:$path"
            coreIntercept?.invoke(path) ?: if (path == "/purchase-requests") json.encodeToJsonElement(listOf(value))
                else json.encodeToJsonElement(value)
        }, pay = { path ->
            calls += "pay:$path"
            payIntercept?.invoke(path) ?: if (path == "/payment-approvals")
                json.encodeToJsonElement(Items(listOf(payValue.copy(merchants = emptyList())))) else json.encodeToJsonElement(payValue)
        })
    }

    @Test fun groupedHistorySeparatesCompletedRequestedAndUnpaidAmounts() = runTest {
        val h = Harness()
        h.history.load()
        val row = h.history.entries!!.single()
        assertEquals("판매처 2곳 구매", row.title)
        assertEquals("PARTIALLY_COMPLETED", row.status)
        assertEquals(PurchaseAmounts("KRW", 42800, 24300, 0, 18500, 0), row.amounts.single())
        assertEquals(1, row.completedCount)
        assertEquals(listOf(approvalId), row.approvalIds)
        assertEquals(listOf("core:/purchase-requests"), h.calls)
    }

    @Test fun detailUsesFreshCoreResultsAndLoadsSharedApprovalOnlyOnce() = runTest {
        val h = Harness(purchase("COMPLETED", "COMPLETED"))
        h.history.load()
        h.value = purchase("COMPLETED", "COMPLETING")
        h.history.open(id)
        val detail = h.history.detail!!
        assertEquals("COMPLETING", detail.status)
        assertEquals(18500L, detail.amounts.single().processing)
        assertEquals("노르딕 문구점", detail.merchantName(detail.checkouts[1]))
        assertEquals("위클리 노트", detail.items(detail.checkouts[1]).single().name)
        assertEquals("COMPLETING", h.history.entries!!.single().status)
        assertEquals(1, h.calls.count { it == "pay:/payment-approvals/$approvalId" })
        assertEquals("APPROVED", detail.approvals[approvalId]!!.status)
    }

    @Test fun approvalReadFailuresKeepCoreResultsAndOfferRetry() = runTest {
        val h = Harness()
        h.payIntercept = { throw IOException("offline") }
        h.history.load(); h.history.open(id)
        assertEquals("PARTIALLY_COMPLETED", h.history.entries!!.single().status)
        assertEquals(24300L, h.history.detail!!.amounts.single().completed)
        assertNull(h.history.listNotice); assertNotNull(h.history.detailNotice)
        assertEquals("판매처 2곳 구매", h.history.detail!!.title)
        h.payIntercept = null
        h.history.open(id)
        assertNull(h.history.detailNotice)
        assertEquals("코펜하겐 스토어 외 1곳", h.history.detail!!.title)
    }

    @Test fun wrongApprovalIdentityOrMerchantAmountsCannotEnrichARecord() = runTest {
        for (wrong in listOf(approval().copy(id = otherId), approval().copy(amountMinor = 1),
            approval().copy(merchants = approval().merchants.mapIndexed { index, m ->
                m.copy(subtotalAmountMinor = if (index == 0) 18500 else 24300)
            }), approval().copy(currency = "USD"))) {
            val h = Harness()
            h.payValue = wrong
            h.history.open(id)
            assertTrue(h.history.detail!!.approvals.isEmpty())
            assertEquals("PARTIALLY_COMPLETED", h.history.detail!!.status)
            assertNotNull(h.history.detailNotice)
        }
    }

    @Test fun failedRefreshKeepsLastReadAndSwitchingIdNeverShowsAnotherPurchase() = runTest {
        val h = Harness()
        h.history.load(); h.history.open(id)
        h.coreIntercept = { throw IOException() }
        h.history.load(); h.history.open(id)
        assertNotNull(h.history.entries); assertNotNull(h.history.detail)
        assertNotNull(h.history.listError); assertNotNull(h.history.detailError)
        h.history.open(otherId)
        assertNull(h.history.detail)
        h.coreIntercept = null
        h.history.open(otherId) // Server returns the original id: never join it to this route.
        assertNull(h.history.detail); assertNotNull(h.history.detailError)
    }

    @Test fun logoutAndNewDetailDiscardLateResponses() = runTest {
        for (logout in listOf(true, false)) {
            val h = Harness()
            val pending = CompletableDeferred<JsonElement>()
            val started = CompletableDeferred<Unit>()
            h.coreIntercept = { started.complete(Unit); pending.await() }
            val job = launch { h.history.open(id) }
            started.await()
            if (logout) h.history.clear() else {
                h.coreIntercept = null
                h.value = h.value.copy(purchaseRequestId = otherId)
                h.history.open(otherId)
            }
            val callCount = h.calls.size
            pending.complete(json.encodeToJsonElement(purchase("COMPLETED", "COMPLETED"))); job.join()
            assertEquals(if (logout) null else otherId, h.history.detail?.id)
            assertEquals(callCount, h.calls.size)
        }
    }

    @Test fun newerListAndLogoutDiscardLateCoreListResponses() = runTest {
        for (logout in listOf(true, false)) {
            val h = Harness()
            val pending = CompletableDeferred<JsonElement>()
            val started = CompletableDeferred<Unit>()
            h.coreIntercept = { started.complete(Unit); pending.await() }
            val job = launch { h.history.load() }
            started.await()
            if (logout) h.history.clear() else {
                h.coreIntercept = null; h.value = purchase("FAILED", "FAILED")
                h.history.load()
            }
            pending.complete(json.encodeToJsonElement(listOf(purchase("COMPLETED", "COMPLETED")))); job.join()
            assertEquals(if (logout) null else "FAILED", h.history.entries?.single()?.status)
        }
    }

    @Test fun sessionExpiryAndCancellationAreNeverSwallowedAsOptionalMetadataErrors() = runTest {
        for (failure in listOf(SessionExpired(), CancellationException())) {
            for (detail in listOf(false, true)) {
                val h = Harness()
                if (detail) h.payIntercept = { throw failure } else h.coreIntercept = { throw failure }
                try {
                    if (detail) h.history.open(id) else h.history.load()
                    fail("Expected propagation")
                } catch (caught: Exception) { assertSame(failure, caught) }
            }
        }
    }

    @Test fun emptyHistoryAndPreCheckoutRequestsNeverShowZeroAsAQuotedTotal() = runTest {
        val h = Harness()
        h.coreIntercept = { JsonArray(emptyList()) }
        h.history.load()
        assertTrue(h.history.entries!!.isEmpty())
        assertEquals(listOf("core:/purchase-requests"), h.calls)
        val entry = PurchaseHistoryEntry(purchase().copy(status = "PENDING_ADDRESS", nextAction = "SELECT_SHIPPING_ADDRESS"))
        assertTrue(entry.amounts.isEmpty()); assertTrue(entry.inProgress)
        assertEquals("PENDING_ADDRESS", entry.status)
        assertEquals("UNKNOWN", PurchaseHistoryEntry(purchase()).status)
    }

    private fun records(count: Int): Pair<List<Purchase>, List<Approval>> {
        val approvals = List(count) { index -> approval().copy(
            id = "22222222-2222-4222-8222-%012d".format(index + 1),
            status = listOf("APPROVED", "EXPIRED", "REJECTED", "CANCELLED")[index % 4],
            merchants = approval().merchants.map { merchant ->
                merchant.copy(summary = merchant.summary.map { it.copy(name = "${it.name} $index") })
            }) }
        val purchases = approvals.mapIndexed { index, approval ->
            purchase("COMPLETED", "COMPLETED").copy(
                purchaseRequestId = "11111111-1111-4111-8111-%012d".format(index + 1),
                merchantCheckouts = purchase("COMPLETED", "COMPLETED").merchantCheckouts.map {
                    it.copy(approvalId = approval.id)
                })
        }
        return purchases to approvals
    }

    private fun searchResult(body: JsonElement?, imageUrl: String): JsonElement {
        val merchantId = body!!.jsonObject.getValue("merchantId").jsonPrimitive.content
        val name = body.jsonObject.getValue("query").jsonPrimitive.content
        val product = CatalogProduct("product-$merchantId", name, "", "", imageUrl,
            listOf(ProductOffer(merchantId, "판매처", 100, "KRW", "IN_STOCK")))
        val search = ProductSearch("OK", listOf(product), emptyList(), emptyList(), emptyList(),
            1, false, "2026-09-23T00:00:00Z")
        return buildJsonObject { put("data", json.encodeToJsonElement(search)) }
    }

    @Test fun firstLoadLatencyIsOnlyCoreEvenWhenPayWouldTakeThirtyFiveSeconds() = runTest {
        val (purchases, _) = records(50)
        var payCalls = 0
        var catalogCalls = 0
        val slowPay: suspend (String) -> JsonElement = {
            payCalls++; delay(35_000)
            json.encodeToJsonElement(approval())
        }
        val images = ProductImages(backgroundScope, pay = slowPay) { _, _, _, _ ->
            catalogCalls++; error("No catalog reads are needed to show Core purchase results")
        }
        val history = PurchaseHistory(core = {
            delay(250); json.encodeToJsonElement(purchases)
        }, pay = slowPay, images = images)
        val startedAt = testScheduler.currentTime
        history.load(); runCurrent()
        assertEquals(250L, testScheduler.currentTime - startedAt)
        assertEquals(50, history.entries!!.size)
        assertEquals(0, payCalls); assertEquals(0, catalogCalls)
    }

    @Test fun fiftyRecordLoadNeverWaitsForPayAndOnlyRequestedCardLoadsOneRepresentativeImage() = runTest {
        val (purchases, approvals) = records(50)
        val metadataGate = CompletableDeferred<Unit>()
        val imageGate = CompletableDeferred<Unit>()
        val detailCalls = mutableListOf<String>()
        val catalogCalls = mutableListOf<String>()
        val imageUrl = "https://images.example.com/tote.jpg"
        val images = ProductImages(backgroundScope, pay = { path ->
            detailCalls += path
            metadataGate.await()
            json.encodeToJsonElement(approvals.single { path.endsWith(it.id) })
        }) { _, path, body, _ ->
            catalogCalls += path
            imageGate.await()
            searchResult(body, imageUrl)
        }
        val history = PurchaseHistory(core = { path ->
            assertEquals("/purchase-requests", path)
            delay(250)
            json.encodeToJsonElement(purchases)
        }, pay = { error("Initial history must not query Pay's inbox") }, images = images)

        val startedAt = testScheduler.currentTime
        history.load(); runCurrent()
        assertEquals(250L, testScheduler.currentTime - startedAt)
        assertEquals(50, history.entries!!.size)
        assertTrue(detailCalls.isEmpty()); assertTrue(catalogCalls.isEmpty())
        assertTrue(history.entries!!.all { it.approvals.isEmpty() })

        val visible = launch { history.ensureThumbnail(purchases.first().purchaseRequestId) }
        val sameCard = launch { history.ensureThumbnail(purchases.first().purchaseRequestId) }
        runCurrent()
        assertEquals(listOf("/payment-approvals/${approvals.first().id}"), detailCalls)
        assertFalse(visible.isCompleted)
        metadataGate.complete(Unit); runCurrent()
        assertEquals("코펜하겐 스토어 외 1곳", history.entries!!.first().title)
        assertEquals("토트백 0", history.entries!!.first().checkouts.flatMap(history.entries!!.first()::items).first().name)
        assertEquals(listOf("/products/search"), catalogCalls)
        assertFalse(visible.isCompleted) // Names are usable while the image server remains slow.
        assertTrue(history.entries!!.drop(1).all { it.approvals.isEmpty() })

        imageGate.complete(Unit); visible.join(); sameCard.join()
        val record = history.entries!!.first()
        assertEquals(purchases.first(), record.purchase)
        assertEquals(listOf(imageUrl, null), record.checkouts.flatMap(record::items).map { it.imageUrl })
        history.ensureThumbnail(record.id)
        assertEquals(1, detailCalls.size); assertEquals(1, catalogCalls.size)
        assertNull(history.detail)
    }

    @Test fun visibleTerminalApprovalReadsItsExactReferenceWithoutRequestingPayInbox() = runTest {
        val snapshot = approval().copy(status = "EXPIRED", merchants = approval().merchants.map { merchant ->
            merchant.copy(summary = merchant.summary.map { it.copy(imageUrl = "https://images.example.com/item.jpg") })
        })
        var snapshotCalls = 0
        val images = ProductImages(backgroundScope, pay = { path ->
            assertEquals("/payment-approvals/$approvalId", path); snapshotCalls++
            json.encodeToJsonElement(snapshot)
        }) { _, _, _, _ -> error("Snapshot already contains its images") }
        val history = PurchaseHistory(core = { json.encodeToJsonElement(listOf(purchase("COMPLETED", "FAILED"))) },
            pay = { error("Do not depend on the active approval inbox") }, images = images)
        history.load()
        assertEquals(0, snapshotCalls)
        history.ensureThumbnail(id)
        val record = history.entries!!.single()
        assertEquals(1, snapshotCalls)
        assertEquals(snapshot, record.approvals[approvalId])
        assertEquals("PARTIALLY_COMPLETED", record.status)
        assertNull(history.listNotice); assertNull(history.listError)
    }

    @Test fun visibleCardCanUseLaterReferencedApprovalWhenFirstMerchantHasNoImage() = runTest {
        val imageUrl = "https://images.example.com/notebook.jpg"
        val value = purchase("COMPLETED", "COMPLETED").let { purchase ->
            purchase.copy(merchantCheckouts = purchase.merchantCheckouts.mapIndexed { index, checkout ->
                checkout.copy(approvalId = if (index == 0) approvalId else otherId)
            })
        }
        val snapshots = approval().merchants.mapIndexed { index, merchant ->
            approval().copy(id = if (index == 0) approvalId else otherId,
                amountMinor = merchant.subtotalAmountMinor, merchantCount = 1,
                merchants = listOf(if (index == 0) merchant else merchant.copy(summary =
                    merchant.summary.map { it.copy(imageUrl = imageUrl) })))
        }
        val detailCalls = mutableListOf<String>()
        var catalogCalls = 0
        val images = ProductImages(backgroundScope, pay = { path ->
            detailCalls += path
            json.encodeToJsonElement(snapshots.single { path.endsWith(it.id) })
        }) { _, _, _, _ ->
            catalogCalls++
            buildJsonObject { put("data", json.encodeToJsonElement(ProductSearch("EMPTY", emptyList(),
                emptyList(), emptyList(), emptyList(), 0, false, "2026-09-23T00:00:00Z"))) }
        }
        val history = PurchaseHistory(core = { json.encodeToJsonElement(listOf(value)) },
            pay = { error("No inbox query") }, images = images)
        history.load(); history.ensureThumbnail(id)
        assertEquals(listOf("/payment-approvals/$approvalId", "/payment-approvals/$otherId"), detailCalls)
        assertEquals(1, catalogCalls)
        val record = history.entries!!.single()
        assertEquals(listOf(null, imageUrl), record.checkouts.flatMap(record::items).map { it.imageUrl })
        assertEquals(value, record.purchase)
    }

    @Test fun searchLoadsOffscreenMetadataWithTwoWorkersAndNeverSearchesCatalog() = runTest {
        val (purchases, approvals) = records(50)
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        val detailCalls = mutableListOf<String>()
        val images = ProductImages(backgroundScope, pay = { path ->
            detailCalls += path
            running++; peak = maxOf(peak, running)
            try {
                gate.await(); delay(10)
                json.encodeToJsonElement(approvals.single { path.endsWith(it.id) })
            } finally { running-- }
        }) { _, _, _, _ -> error("Search must resolve names without loading product images") }
        val history = PurchaseHistory(core = { json.encodeToJsonElement(purchases) },
            pay = { error("Search only needs server-referenced approval details") }, images = images)
        history.load()
        assertTrue(history.entries!!.none { it.matches(PurchaseHistoryFilter.ALL, "위클리 노트 49") })
        val search = launch { history.prepareSearch("위클리 노트 49", PurchaseHistoryFilter.ALL) }
        runCurrent()
        assertTrue(history.searchLoading); assertTrue(detailCalls.isEmpty())
        advanceTimeBy(249); runCurrent()
        assertTrue(detailCalls.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(2, detailCalls.size); assertEquals(2, peak)
        gate.complete(Unit); search.join()
        val matches = history.entries!!.filter { it.matches(PurchaseHistoryFilter.ALL, "위클리 노트 49") }
        assertEquals(listOf(purchases.last().purchaseRequestId), matches.map { it.id })
        assertEquals(approvals.map { "/payment-approvals/${it.id}" }.toSet(), detailCalls.toSet())
        assertEquals(50, detailCalls.size); assertEquals(2, peak)
        assertFalse(history.searchLoading); assertNull(history.searchNotice)
        history.prepareSearch("토트백", PurchaseHistoryFilter.ALL)
        assertEquals(50, detailCalls.size) // Metadata is shared across searches.
    }

    @Test fun searchOnlyReadsSelectedStatusAndReportsIncompleteMetadata() = runTest {
        val (rows, approvals) = records(3)
        val purchases = rows.mapIndexed { index, row -> if (index == 1) row.copy(merchantCheckouts =
            row.merchantCheckouts.map { it.copy(status = "FAILED") }) else row }
        val calls = mutableListOf<String>()
        val images = ProductImages(backgroundScope, pay = { path ->
            calls += path
            throw IOException("metadata unavailable")
        }) { _, _, _, _ -> error("No catalog reads during text search") }
        val history = PurchaseHistory(core = { json.encodeToJsonElement(purchases) },
            pay = { error("No inbox query") }, images = images)
        history.load()
        history.prepareSearch("토트백", PurchaseHistoryFilter.ATTENTION)
        assertEquals(listOf("/payment-approvals/${approvals[1].id}"), calls)
        assertFalse(history.searchLoading); assertNotNull(history.searchNotice)
        assertEquals(3, history.entries!!.size)
        history.prepareSearch("", PurchaseHistoryFilter.ALL)
        assertNull(history.searchNotice); assertFalse(history.searchLoading)
        assertEquals(1, calls.size)
    }

    @Test fun cancelledSearchDoesNotLeaveLoadingOrStartReadsBeforeDebounce() = runTest {
        val images = ProductImages(backgroundScope, pay = { error("Cancelled search must not reach Pay") }) {
            _, _, _, _ -> error("Search must not load images")
        }
        val history = PurchaseHistory(core = { json.encodeToJsonElement(listOf(purchase("COMPLETED", "FAILED"))) },
            pay = { error("No inbox query") }, images = images)
        history.load()
        val search = launch { history.prepareSearch("토트백", PurchaseHistoryFilter.ALL) }
        runCurrent(); assertTrue(history.searchLoading)
        search.cancelAndJoin()
        assertFalse(history.searchLoading)
        history.prepareSearch("", PurchaseHistoryFilter.ALL)
        advanceTimeBy(500); runCurrent()
        assertFalse(history.searchLoading); assertNull(history.searchNotice)
    }

    @Test fun cancelledPreviousSearchCannotClearTheNewSearchLoadingState() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0
        val images = ProductImages(backgroundScope, pay = { calls++; gate.await() }) {
            _, _, _, _ -> error("Search must not load images")
        }
        val history = PurchaseHistory(core = { json.encodeToJsonElement(listOf(purchase("COMPLETED", "FAILED"))) },
            pay = { error("No inbox query") }, images = images)
        history.load()
        val old = launch { history.prepareSearch("토트", PurchaseHistoryFilter.ALL) }
        runCurrent()
        val latest = launch { history.prepareSearch("위클리", PurchaseHistoryFilter.PARTIAL) }
        runCurrent()
        old.cancelAndJoin()
        assertTrue(history.searchLoading)
        advanceTimeBy(250); runCurrent()
        assertEquals(1, calls); assertTrue(history.searchLoading)
        gate.complete(json.encodeToJsonElement(approval())); latest.join()
        assertFalse(history.searchLoading); assertNull(history.searchNotice)
        assertTrue(history.entries!!.single().matches(PurchaseHistoryFilter.PARTIAL, "위클리"))
    }

    @Test fun lateHistoricalSnapshotsCannotOverwriteNewListsOrCrossCheckoutReferences() = runTest {
        for (scenario in listOf("clear", "refresh", "wrong-merchant", "wrong-amount", "wrong-id")) {
            var value = purchase("COMPLETED", "FAILED")
            val gate = CompletableDeferred<JsonElement>()
            val images = ProductImages(backgroundScope, pay = { gate.await() }) { _, _, _, _ ->
                error("Stale or mismatched snapshots must not trigger catalog lookup")
            }
            val history = PurchaseHistory(core = { json.encodeToJsonElement(listOf(value)) },
                pay = { error("No inbox query") }, images = images)
            history.load()
            val thumbnail = launch { history.ensureThumbnail(id) }
            runCurrent()
            when (scenario) {
                "clear" -> history.clear()
                "refresh" -> {
                    value = purchase("FAILED", "FAILED").copy(merchantCheckouts =
                        value.merchantCheckouts.map { it.copy(approvalId = null, status = "FAILED") })
                    history.load()
                }
            }
            val snapshot = when (scenario) {
                "wrong-merchant" -> approval().copy(merchants = approval().merchants.map { it.copy(merchantId = "wrong-${it.merchantId}") })
                "wrong-amount" -> approval().copy(amountMinor = 1)
                "wrong-id" -> approval().copy(id = otherId)
                else -> approval()
            }
            gate.complete(json.encodeToJsonElement(snapshot)); thumbnail.join()
            if (scenario == "clear") assertNull(history.entries)
            else {
                assertEquals(value, history.entries!!.single().purchase)
                assertTrue(history.entries!!.single().approvals.isEmpty())
            }
            assertNull(history.detail)
        }
    }

    @Test fun lateSearchMetadataCannotRepopulateClearedOrRefreshedHistory() = runTest {
        for (logout in listOf(true, false)) {
            var value = purchase("COMPLETED", "FAILED")
            val gate = CompletableDeferred<JsonElement>()
            val images = ProductImages(backgroundScope, pay = { gate.await() }) { _, _, _, _ -> error("No images for search") }
            val history = PurchaseHistory(core = { json.encodeToJsonElement(listOf(value)) },
                pay = { error("No inbox query") }, images = images)
            history.load()
            val search = launch { history.prepareSearch("토트백", PurchaseHistoryFilter.ALL) }
            runCurrent(); advanceTimeBy(250); runCurrent()
            if (logout) history.clear() else {
                value = value.copy(merchantCheckouts = value.merchantCheckouts.map { it.copy(approvalId = null) })
                history.load()
            }
            gate.complete(json.encodeToJsonElement(approval())); search.join()
            assertFalse(history.searchLoading)
            assertNull(history.searchNotice)
            if (logout) assertNull(history.entries) else assertTrue(history.entries!!.single().approvals.isEmpty())
        }
    }

    @Test fun refreshAndDetailPreserveImagesOnlyForUnchangedCheckoutAndItemSnapshots() = runTest {
        val imageUrl = "https://images.example.com/item.jpg"
        val snapshot = approval().copy(merchants = approval().merchants.map { merchant ->
            merchant.copy(summary = merchant.summary.map { it.copy(imageUrl = imageUrl) })
        })
        var value = purchase("COMPLETED", "FAILED")
        val detailGate = CompletableDeferred<Unit>()
        val images = ProductImages(backgroundScope, pay = { json.encodeToJsonElement(snapshot) }) {
            _, _, _, _ -> error("Retained snapshot images need no catalog calls")
        }
        val history = PurchaseHistory(core = { path ->
            if (path == "/purchase-requests") json.encodeToJsonElement(listOf(value)) else {
                detailGate.await(); json.encodeToJsonElement(value)
            }
        }, pay = { json.encodeToJsonElement(approval()) }, images = images)
        history.load(); history.ensureThumbnail(id)
        val known = history.entries!!.single()
        val revision = history.imageRevision
        value = value.copy(merchantCheckouts = value.merchantCheckouts.map { it.copy(status = "COMPLETED") })
        history.load()
        assertTrue(history.imageRevision > revision)
        assertEquals("COMPLETED", history.entries!!.single().status)
        assertEquals(known.approvals, history.entries!!.single().approvals)
        val open = launch { history.open(id) }
        runCurrent()
        assertEquals(known.approvals, history.detail!!.approvals) // Cached card is available before the detail API.
        detailGate.complete(Unit); open.join(); runCurrent()
        assertEquals(listOf(imageUrl, imageUrl), history.detail!!.checkouts.flatMap(history.detail!!::items).map { it.imageUrl })
        assertEquals(history.detail!!.approvals, history.entries!!.single().approvals)

        value = value.copy(merchantCheckouts = value.merchantCheckouts.mapIndexed { index, row ->
            if (index == 0) row.copy(totalAmount = row.totalAmount + 1) else row
        })
        history.load()
        assertTrue(history.entries!!.single().approvals.isEmpty())
        assertEquals(42801L, history.entries!!.single().amounts.single().requested)
    }

    @Test fun unknownAndUnfinishedMerchantsCannotLookLikeFullOrPartialCompletion() {
        val cases = mapOf("COMPLETING" to "COMPLETING", "AWAITING_APPROVAL" to "AWAITING_APPROVAL",
            "CREATING" to "CREATING_CHECKOUTS", "NEW_STATE" to "UNKNOWN", "FAILED" to "PARTIALLY_COMPLETED")
        cases.forEach { (merchantState, expected) ->
            val entry = PurchaseHistoryEntry(purchase("COMPLETED", merchantState))
            assertEquals(expected, entry.status)
            assertEquals(24300L, entry.amounts.single().completed)
        }
    }

    @Test fun amountsKeepCurrenciesSeparateAndUseLongWithOverflowAndDuplicateValidation() {
        val purchase = purchase("COMPLETED", "FAILED").let {
            it.copy(merchantCheckouts = listOf(it.merchantCheckouts[0].copy(totalAmount = 5000000000L),
                it.merchantCheckouts[1].copy(totalAmount = 1234, currency = "USD")))
        }
        purchase.requireHistoryRecord()
        val amounts = PurchaseHistoryEntry(purchase).amounts
        assertEquals(listOf("KRW", "USD"), amounts.map { it.currency })
        assertEquals(listOf(5000000000L, 0L), amounts.map { it.completed })
        for (bad in listOf(purchase.copy(merchantCheckouts = listOf(purchase.merchantCheckouts.first(), purchase.merchantCheckouts.first())),
            purchase.copy(merchantCheckouts = purchase.merchantCheckouts.map { it.copy(totalAmount = -1) }),
            purchase.copy(merchantCheckouts = purchase.merchantCheckouts.map { it.copy(totalAmount = Long.MAX_VALUE, currency = "KRW") }))) {
            assertTrue(runCatching { bad.requireHistoryRecord() }.isFailure)
        }
    }

    @Test fun filtersAndSearchWorkAtPurchaseLevelWithoutDuplicatingSharedApprovals() {
        val partial = PurchaseHistoryEntry(purchase("COMPLETED", "FAILED"), mapOf(approvalId to approval()))
        assertTrue(partial.matches(PurchaseHistoryFilter.PARTIAL, "노르딕"))
        assertTrue(partial.matches(PurchaseHistoryFilter.ALL, "  위클리 "))
        assertTrue(partial.matches(PurchaseHistoryFilter.ALL, id.take(8)))
        assertFalse(partial.matches(PurchaseHistoryFilter.COMPLETED, ""))
        assertFalse(partial.matches(PurchaseHistoryFilter.ALL, "없는 상품"))
        assertTrue(PurchaseHistoryEntry(purchase("FAILED", "FAILED")).matches(PurchaseHistoryFilter.ATTENTION, ""))
        assertTrue(PurchaseHistoryEntry(purchase("COMPLETED", "COMPLETING")).matches(PurchaseHistoryFilter.IN_PROGRESS, ""))
        assertTrue(PurchaseHistoryEntry(purchase("COMPLETED", "COMPLETED")).matches(PurchaseHistoryFilter.COMPLETED, ""))
    }
}
