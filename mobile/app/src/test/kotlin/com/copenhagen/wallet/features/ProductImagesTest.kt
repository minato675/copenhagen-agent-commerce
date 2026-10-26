package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProductImagesTest {
    private val url = "https://img.example.com/product.jpg"
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun product(name: String = "한돈 삼겹살", merchant: String = "shop-a") = CatalogProduct(
        "product-a", name, "", "", url, listOf(ProductOffer(merchant, "노을상점", 12000, "KRW", "IN_STOCK")))
    private fun search(products: List<CatalogProduct> = listOf(product())) = ProductSearch(
        "OK", products, emptyList(), listOf(MerchantRef("shop-a", "노을상점")), emptyList(), products.size, false, "2026-09-23T00:00:00Z")
    private fun envelope(value: ProductSearch = search()) = buildJsonObject { put("data", json.encodeToJsonElement(value)) }
    private fun approval() = Approval(id, amountMinor = 12000, currency = "KRW", status = "PENDING",
        expiresAt = "2099-01-01T00:00:00Z", merchants = listOf(ApprovalMerchant("shop-a", "노을상점", "checkout-a", "hash-a",
            listOf(ItemSummary("한돈 삼겹살", "기본", 1, 12000)), 12000)))

    @Test fun exactMerchantAndNameMustBeUniqueInACompleteResponse() {
        assertEquals(url, search().exactImage("shop-a", "한돈 삼겹살"))
        val unsafe = listOf(
            search().copy(status = "PARTIAL"),
            search().copy(failedMerchants = listOf("shop-b")),
            search().copy(hasNext = true),
            search().copy(total = 12),
            search(listOf(product(), product().copy(productId = "other-id"))),
            search(listOf(product(name = "한돈 삼겹살 500g"))),
            search(listOf(product(merchant = "shop-b"))),
            search(listOf(product().copy(imageUrl = "")))
        )
        unsafe.forEach { assertNull(it.exactImage("shop-a", "한돈 삼겹살")) }
        assertNull(search().exactImage("", "한돈 삼겹살"))
        assertNull(search().exactImage("shop-a", ""))
    }

    @Test fun enrichmentNeverBlocksApprovalLoadingAndKeepsPaymentTerms() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        val requests = mutableListOf<JsonObject>()
        val images = ProductImages(backgroundScope) { method, path, body, query ->
            assertEquals("POST", method); assertEquals("/products/search", path); assertTrue(query.isEmpty())
            requests += body!!.jsonObject
            gate.await()
        }
        val original = approval()
        val feature = Approvals(request = { _, _, _, _, _, _ -> json.encodeToJsonElement(original) }, images = images)
        feature.open(id)
        assertEquals(original, feature.current)
        runCurrent()
        assertEquals("shop-a", requests.single().getValue("merchantId").jsonPrimitive.content)
        assertEquals("한돈 삼겹살", requests.single().getValue("query").jsonPrimitive.content)
        gate.complete(envelope()); runCurrent()
        val enriched = feature.current!!
        assertEquals(url, enriched.displaySummary.single().imageUrl)
        assertTrue(original.sameReviewAs(enriched))
        assertEquals(original.copy(merchants = original.merchants.map { it.copy(summary = it.summary.map { row -> row.copy(imageUrl = url) }) }), enriched)
    }

    @Test fun directImagesAndMissingMerchantIdsNeverTriggerSearch() = runTest {
        var calls = 0
        val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; envelope() }
        val original = approval()
        images.enrich(original.copy(merchants = emptyList(), summary = original.displaySummary)) { fail("No metadata to enrich") }
        images.enrich(original.copy(merchants = original.merchants.map { it.copy(summary = it.summary.map { row -> row.copy(imageUrl = url) }) })) { fail("Already complete") }
        runCurrent()
        assertEquals(0, calls)
    }

    @Test fun approvalMetadataDoesNotWaitForBusyImageRequests() = runTest {
        val imageGate = CompletableDeferred<JsonElement>()
        var imageCalls = 0
        var detailCalls = 0
        val original = approval()
        val images = ProductImages(backgroundScope, pay = { path ->
            assertEquals("/payment-approvals/$id", path)
            detailCalls++
            json.encodeToJsonElement(original)
        }) { _, _, _, _ -> imageCalls++; imageGate.await() }
        val otherItems = original.copy(merchants = original.merchants.map { merchant ->
            merchant.copy(summary = (1..4).map { ItemSummary("다른 상품 $it") })
        })
        images.enrich(otherItems) { fail("Image requests are still blocked") }
        runCurrent()
        assertEquals(4, imageCalls)

        val metadata = async { images.approvalSnapshot(id) }
        runCurrent()
        assertTrue(metadata.isCompleted)
        assertEquals(original, metadata.await())
        assertEquals(1, detailCalls)
        assertEquals(4, imageCalls)
        assertFalse(imageGate.isCompleted)
    }

    @Test fun representativeOnlyResolvesOneItemWithoutFetchingApprovalDetail() = runTest {
        var detailCalls = 0
        val searches = mutableListOf<String>()
        val images = ProductImages(backgroundScope, pay = { detailCalls++; error("Unused approval detail") }) { _, _, body, _ ->
            searches += body!!.jsonObject.getValue("query").jsonPrimitive.content
            envelope()
        }
        val original = approval().let { value -> value.copy(merchants = value.merchants.map { merchant ->
            merchant.copy(summary = merchant.summary + ItemSummary("두 번째 상품") + ItemSummary("세 번째 상품"))
        }) }
        val result = images.representative(original)

        assertEquals(listOf("한돈 삼겹살"), searches)
        assertEquals(0, detailCalls)
        assertEquals(listOf(url, null, null), result.displaySummary.map { it.imageUrl })
        assertTrue(original.sameReviewAs(result))
        assertEquals(original.copy(merchants = original.merchants.map { merchant -> merchant.copy(
            summary = merchant.summary.mapIndexed { index, item -> if (index == 0) item.copy(imageUrl = url) else item }) }), result)
    }

    @Test fun representativeSkipsLookupsWhenAnyImageAlreadyExistsOrMerchantIsUnknown() = runTest {
        var requests = 0
        val images = ProductImages(backgroundScope, pay = { requests++; error("No detail required") }) { _, _, _, _ ->
            requests++; envelope()
        }
        val original = approval()
        val hasLaterImage = original.copy(merchants = original.merchants + original.merchants.single().copy(
            merchantId = "shop-b", summary = listOf(ItemSummary("이미 있는 상품", imageUrl = url))))
        val flat = original.copy(merchants = emptyList(), summary = original.displaySummary)
        assertEquals(hasLaterImage, images.representative(hasLaterImage))
        assertEquals(flat, images.representative(flat))
        assertEquals(original.copy(merchants = emptyList()), images.representative(original.copy(merchants = emptyList())))
        assertEquals(0, requests)
    }

    @Test fun representativeCanUseTheNextItemAfterAnAmbiguousFirstMatch() = runTest {
        val requests = mutableListOf<String>()
        val images = ProductImages(backgroundScope) { _, _, body, _ ->
            val name = body!!.jsonObject.getValue("query").jsonPrimitive.content
            requests += name
            if (name == "한돈 삼겹살") envelope(search(listOf(product(), product().copy(productId = "different-product"))))
            else envelope(search(listOf(product(name = name))))
        }
        val original = approval().let { value -> value.copy(merchants = value.merchants.map {
            it.copy(summary = it.summary + ItemSummary("다른 상품") + ItemSummary("조회하지 않을 상품"))
        }) }
        val result = images.representative(original)
        assertEquals(listOf(null, url, null), result.displaySummary.map { it.imageUrl })
        assertEquals(listOf("한돈 삼겹살", "다른 상품"), requests)
        assertTrue(original.sameReviewAs(result))
    }

    @Test fun representativeFallbackStopsAfterThreeDistinctMissingCandidates() = runTest {
        val requests = mutableListOf<String>()
        val images = ProductImages(backgroundScope) { _, _, body, _ ->
            requests += body!!.jsonObject.getValue("query").jsonPrimitive.content
            envelope(search(listOf(product(name = "일치하지 않는 다른 상품"))))
        }
        val original = approval().let { value -> value.copy(merchants = value.merchants.map {
            it.copy(summary = (1..10).flatMap { index -> listOf(ItemSummary("상품 $index"), ItemSummary("상품 $index")) })
        }) }
        assertEquals(original, images.representative(original))
        assertEquals(listOf("상품 1", "상품 2", "상품 3"), requests)
    }

    @Test fun suspendSnapshotsShareReadsAndCallerCancellationPropagates() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0
        val images = ProductImages(backgroundScope, pay = { calls++; gate.await() }) { _, _, _, _ -> envelope() }
        val first = async { images.approvalSnapshot(id) }
        val second = async { images.approvalSnapshot(id) }
        runCurrent()
        assertEquals(1, calls)
        first.cancel(); runCurrent()
        assertTrue(first.isCancelled)
        val third = async { images.approvalSnapshot(id) }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(json.encodeToJsonElement(approval())); runCurrent()
        assertEquals(approval(), second.await())
        assertEquals(approval(), third.await())
        assertEquals(approval(), images.approvalSnapshot(id))
        assertEquals(2, calls) // Completed metadata is not retained as a raw approval cache.
    }

    @Test fun cancelledRepresentativeWaiterDoesNotRemoveAnotherCardsSharedImageRead() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0
        val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; gate.await() }
        val first = async { images.representative(approval()) }
        val second = async { images.representative(approval()) }
        runCurrent()
        assertEquals(1, calls)
        first.cancel(); runCurrent()
        val third = async { images.representative(approval()) }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(envelope()); runCurrent()
        assertTrue(first.isCancelled)
        assertEquals(url, second.await().displaySummary.single().imageUrl)
        assertEquals(url, third.await().displaySummary.single().imageUrl)
    }

    @Test fun clearCancelsSharedMetadataReadsAndAlreadyCancelledCallersDoNotStartReads() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0
        var cancelled = false
        val images = ProductImages(backgroundScope, pay = {
            calls++
            try { gate.await() } catch (failure: CancellationException) { cancelled = true; throw failure }
        }) { _, _, _, _ -> envelope() }
        val inactive = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            images.approvalSnapshot(id)
        }
        runCurrent()
        assertTrue(inactive.isCancelled)
        assertEquals(0, calls)

        val first = async { images.approvalSnapshot(id) }
        val second = async { images.approvalSnapshot(id) }
        runCurrent()
        assertEquals(1, calls)
        images.clear(); runCurrent()
        assertTrue(cancelled)
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }

    @Test fun lightweightApprovalListReceivesItemsAndImagesWithoutWaitingForDetail() = runTest {
        val original = approval()
        val row = original.copy(merchants = emptyList(), status = "APPROVED")
        val gate = CompletableDeferred<JsonElement>()
        var detailCalls = 0
        val images = ProductImages(backgroundScope, pay = { path ->
            assertEquals("/payment-approvals/$id", path)
            detailCalls++; gate.await()
        }) { _, _, _, _ -> envelope() }
        val feature = Approvals(request = { _, _, _, _, _, _ -> json.encodeToJsonElement(Items(listOf(row))) }, images = images)
        feature.loadHistory()
        assertEquals(listOf(row), feature.history)
        runCurrent(); assertEquals(0, detailCalls)
        feature.ensureThumbnail(row.id)
        runCurrent(); assertEquals(1, detailCalls)
        gate.complete(json.encodeToJsonElement(original)); runCurrent()
        val enriched = feature.history!!.single()
        assertEquals("APPROVED", enriched.status)
        assertEquals(row.amountMinor, enriched.amountMinor)
        assertEquals("한돈 삼겹살", enriched.displaySummary.single().name)
        assertEquals(url, enriched.displaySummary.single().imageUrl)
    }

    @Test fun approvalInboxAndHistoryDoNotPrefetchFiftyOffscreenCards() = runTest {
        val original = approval()
        val rows = (1..50).map { index -> original.copy(
            id = "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}", merchants = emptyList()) }
        var detailCalls = 0
        var imageCalls = 0
        val images = ProductImages(backgroundScope, pay = { path ->
            detailCalls++
            json.encodeToJsonElement(original.copy(id = path.substringAfterLast('/')))
        }) { _, _, _, _ -> imageCalls++; envelope() }
        val feature = Approvals(request = { _, _, _, _, _, _ -> json.encodeToJsonElement(Items(rows)) }, images = images)
        feature.loadInbox()
        feature.loadHistory()
        runCurrent()
        assertEquals(50, feature.inbox!!.size)
        assertEquals(50, feature.history!!.size)
        assertEquals(0, detailCalls)
        assertEquals(0, imageCalls)

        feature.ensureThumbnail(rows.first().id)
        runCurrent()
        assertEquals(1, detailCalls)
        assertEquals(1, imageCalls)
        assertEquals(1, feature.inbox!!.count { it.displaySummary.any { item -> item.imageUrl != null } })
        assertEquals(1, feature.history!!.count { it.displaySummary.any { item -> item.imageUrl != null } })
    }

    @Test fun mismatchedApprovalDetailsCannotDecorateAListRow() = runTest {
        val original = approval()
        val row = original.copy(merchants = emptyList())
        val mismatches = listOf(original.copy(id = "22222222-2222-4222-8222-222222222222"),
            original.copy(currency = "USD"), original.copy(merchants = emptyList(), merchantName = "가게", checkoutId = "checkout", checkoutHash = "hash", merchantCount = 2),
            original.copy(amountMinor = 13000, merchants = original.merchants.map { it.copy(subtotalAmountMinor = 13000) }))
        for (wrong in mismatches) {
            var calls = 0
            val images = ProductImages(backgroundScope, pay = { json.encodeToJsonElement(wrong) }) { _, _, _, _ -> calls++; envelope() }
            images.enrich(row) { fail("Mismatched detail was exposed") }
            runCurrent(); assertEquals(0, calls)
        }
    }

    @Test fun groupedPayDetailWithoutMerchantCountStillEnrichesTheList() = runTest {
        val original = approval()
        val detail = original.copy(amountMinor = 24000, merchants = original.merchants + original.merchants.single().copy(
            merchantId = "shop-b", merchantName = "다른 상점", checkoutId = "checkout-b"))
        val wire = JsonObject(json.encodeToJsonElement(detail).jsonObject - "merchantCount")
        val row = detail.copy(merchants = emptyList(), merchantCount = 2)
        val images = ProductImages(backgroundScope, pay = { wire }) { _, _, body, _ ->
            envelope(search(listOf(product(merchant = body!!.jsonObject.getValue("merchantId").jsonPrimitive.content))))
        }
        var result: Approval? = null
        images.enrich(row) { result = it }; runCurrent()
        assertEquals(2, result!!.merchants.size)
        assertEquals(2, result!!.merchantCount)
        assertTrue(result!!.displaySummary.all { it.imageUrl == url })
    }

    @Test fun purchaseHistoryAlsoChecksCheckoutMerchantReferencesBeforeApplyingDecoratedDetail() = runTest {
        val original = approval()
        val wrongMerchant = original.copy(merchants = original.merchants.map { it.copy(merchantId = "unrelated-shop") })
        val purchase = Purchase("22222222-2222-4222-8222-222222222222", "COMPLETED",
            "33333333-3333-4333-8333-333333333333", 1, "2099-01-01T00:00:00Z", "SHOW_RESULT",
            merchantCheckouts = listOf(PurchaseCheckout("shop-a", id, "COMPLETED", 12000, "KRW")))
        var searches = 0
        val images = ProductImages(backgroundScope, pay = { json.encodeToJsonElement(wrongMerchant) }) { _, _, _, _ ->
            searches++; envelope()
        }
        val history = PurchaseHistory({ json.encodeToJsonElement(listOf(purchase)) },
            { error("Purchase list must not request Pay's inbox") }, images)
        history.load()
        history.ensureThumbnail(purchase.purchaseRequestId)
        assertTrue(history.entries!!.single().approvals.isEmpty())
        assertEquals(0, searches)
        assertEquals("COMPLETED", history.entries!!.single().status)
    }

    @Test fun duplicateLookupsShareOneRequestAndCompletedResultIsCached() = runTest {
        var calls = 0
        val gate = CompletableDeferred<JsonElement>()
        val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; gate.await() }
        val ready = mutableListOf<Approval>()
        repeat(3) { images.enrich(approval()) { ready += it } }
        runCurrent(); assertEquals(1, calls)
        gate.complete(envelope()); runCurrent()
        assertEquals(3, ready.size)
        images.enrich(approval()) { ready += it }; runCurrent()
        assertEquals(1, calls); assertEquals(4, ready.size)
    }

    @Test fun transientFailureDoesNotEraseApprovalAndCanRecoverLater() = runTest {
        var offline = true
        val images = ProductImages(backgroundScope) { _, _, _, _ ->
            if (offline) error("unavailable") else envelope()
        }
        val original = approval()
        val feature = Approvals(request = { _, _, _, _, _, _ -> json.encodeToJsonElement(original) }, images = images)
        feature.open(id); runCurrent()
        assertEquals(original, feature.current)
        offline = false
        feature.open(id); runCurrent()
        assertEquals(url, feature.current!!.displaySummary.single().imageUrl)
    }

    @Test fun clearedSessionCannotReceiveLateImageUpdates() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        val images = ProductImages(backgroundScope) { _, _, _, _ -> withContext(NonCancellable) { gate.await() } }
        var callbacks = 0
        images.enrich(approval()) { callbacks++ }; runCurrent()
        images.clear(); gate.complete(envelope()); runCurrent()
        assertEquals(0, callbacks)
    }

    @Test fun lookupConcurrencyAndPerApprovalWorkAreBounded() = runTest {
        var running = 0
        var maximum = 0
        var calls = 0
        val images = ProductImages(backgroundScope) { _, _, body, _ ->
            calls++; running++; maximum = maxOf(maximum, running)
            delay(100)
            running--
            envelope(search(listOf(product(name = body!!.jsonObject.getValue("query").jsonPrimitive.content))))
        }
        val original = approval()
        val many = original.copy(merchants = original.merchants.map { merchant -> merchant.copy(
            summary = (1..40).map { ItemSummary("상품 $it") }) })
        var ready: Approval? = null
        images.enrich(many) { ready = it }; runCurrent()
        assertEquals(4, running)
        advanceTimeBy(1000); runCurrent()
        assertEquals(4, maximum); assertEquals(24, calls)
        assertEquals(24, ready!!.displaySummary.count { it.imageUrl != null })
        assertEquals(40, ready!!.displaySummary.size)
    }
}
