package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderImagesTest {
    private val name = "[닌텐도] 닌텐도 스위치 2 본체 단품"
    private val url = "https://img.example.com/switch.jpg"
    private fun item(product: String = "switch", merchant: String = "shop-a", variant: String? = null,
        image: String? = null) = ItemSummary(name, "기본", 1, imageUrl = image,
        merchantId = merchant, merchantProductId = product, merchantVariantId = variant)
    private fun order(items: List<ItemSummary> = listOf(item())) = Order(
        "00000000-0000-4000-8000-000000000001", name, "CONFIRMED", 648000, "KRW", "2026-09-23T00:00:00Z", items)
    private fun detail(product: String = "switch", merchant: String = "shop-a", variant: String? = null,
        image: String? = url) = ProductDetailResult("FOUND", ProductSelection(product, merchant, variant),
        ProductDetail(product, "현재 상품명", "", "", image, null, emptyList(),
            ProductOffer(merchant, "상점", 648000, "KRW", "IN_STOCK"),
            variant?.let { listOf(ProductVariant(it, "기본", 648000, "IN_STOCK")) }.orEmpty(), true,
            variant != null, if (variant == null) "NONE" else "VALID", variant,
            shipping = ProductShipping("UNKNOWN", null, "")), "2026-09-23T00:00:00Z")
    private fun envelope(value: ProductDetailResult = detail()) = buildJsonObject { put("data", json.encodeToJsonElement(value)) }
    private fun fromRequest(path: String, query: Map<String, String>) = envelope(detail(
        path.removePrefix("/products/"), query.getValue("merchantId"), query["optionId"]))

    @Test fun snapshotImagesNeedNoLookupAndV2WithoutIdentifiersNeverSearchesNames() = runTest {
        var calls = 0
        val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; envelope() }
        images.enrich(order(listOf(item(image = url)))) { fail("Already complete") }
        images.enrich(order(listOf(ItemSummary(name)))) { fail("Missing identifiers") }
        images.enrich(order(listOf(ItemSummary(name, merchantId = "shop-a"))), allowNameFallback = true) { fail("Partial identifiers") }
        runCurrent(); assertEquals(0, calls)
    }

    @Test fun historicalSnapshotUsesExactProductOnceAndPreservesOrderTerms() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0
        val images = ProductImages(backgroundScope) { method, path, body, query ->
            assertEquals("GET", method); assertEquals("/products/switch", path); assertNull(body)
            assertEquals(mapOf("merchantId" to "shop-a", "optionId" to "black"), query)
            calls++; gate.await()
        }
        val source = order(listOf(item(variant = "black")))
        var ready: Order? = null
        images.enrich(source) { ready = it }
        assertNull(ready); runCurrent(); assertEquals(1, calls)
        gate.complete(envelope(detail(variant = "black"))); runCurrent()
        assertEquals(source.copy(items = source.items.map { it.copy(imageUrl = url) }), ready)
    }

    @Test fun representativeCardLooksUpOnlyOneItemAndSkipsIfAnotherItemHasAnImage() = runTest {
        val requested = mutableListOf<String>()
        val images = ProductImages(backgroundScope) { _, path, _, query -> requested += path; fromRequest(path, query) }
        val source = order((1..4).map { item("product-$it") })
        var ready: Order? = null
        images.enrich(source, representativeOnly = true) { ready = it }; runCurrent()
        assertEquals(listOf("/products/product-1"), requested)
        assertEquals(1, ready!!.items.count { it.imageUrl != null })
        images.enrich(source.copy(items = source.items.dropLast(1) + source.items.last().copy(imageUrl = url)),
            representativeOnly = true) { fail("A representative image already exists") }
        runCurrent(); assertEquals(1, requested.size)
    }

    @Test fun listAndDetailShareInflightRequestAndCacheButDifferentMerchantsAndVariantsDoNot() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val images = ProductImages(backgroundScope) { _, path, _, query -> calls++; gate.await(); fromRequest(path, query) }
        val ready = mutableListOf<Order>()
        repeat(3) { images.enrich(order(), representativeOnly = it == 0) { ready += it } }
        runCurrent(); assertEquals(1, calls)
        gate.complete(Unit); runCurrent(); assertEquals(3, ready.size)
        images.enrich(order()) { ready += it }; runCurrent(); assertEquals(1, calls)
        images.enrich(order(listOf(item(merchant = "shop-b"), item(variant = "white"), item(variant = "black")))) { ready += it }
        runCurrent(); assertEquals(4, calls); assertTrue(ready.last().items.all { it.imageUrl == url })
    }

    @Test fun detailResolvesAllMissingItemsWithAtMostFourConcurrentReads() = runTest {
        var running = 0; var maximum = 0; var calls = 0
        val images = ProductImages(backgroundScope) { _, path, _, query ->
            calls++; running++; maximum = maxOf(maximum, running)
            try { delay(100); fromRequest(path, query) } finally { running-- }
        }
        var ready: Order? = null
        images.enrich(order((1..30).map { item("product-$it") })) { ready = it }; runCurrent()
        assertEquals(4, running)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, maximum); assertEquals(30, calls); assertEquals(30, ready!!.items.count { it.imageUrl == url })
    }

    @Test fun mismatchedProductMerchantRequestOrVariantNeverDecoratesAndDoesNotPoisonCache() = runTest {
        val valid = detail(variant = "black")
        val product = valid.product!!
        val wrong = listOf(
            valid.copy(status = "PARTIAL"), valid.copy(product = null),
            valid.copy(request = valid.request.copy(productId = "different")),
            valid.copy(request = valid.request.copy(merchantId = "shop-b")),
            valid.copy(request = valid.request.copy(optionId = "white")),
            valid.copy(product = product.copy(productId = "different")),
            valid.copy(product = product.copy(offer = product.offer.copy(merchantId = "shop-b"))),
            valid.copy(product = product.copy(selectedOptionId = "white")),
            valid.copy(product = product.copy(selectionStatus = "INVALID")),
            valid.copy(product = product.copy(variants = emptyList()))
        )
        for (response in wrong) {
            var bad = true; var calls = 0; var ready: Order? = null
            val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; envelope(if (bad) response else valid) }
            val source = order(listOf(item(variant = "black")))
            images.enrich(source) { ready = it }; runCurrent(); assertNull(ready)
            bad = false
            images.enrich(source) { ready = it }; runCurrent()
            assertEquals(2, calls); assertEquals(url, ready!!.items.single().imageUrl)
        }
    }

    @Test fun completedMissingProductOrImageIsCachedButNetworkFailureCanRetry() = runTest {
        for (missing in listOf(detail(image = null), detail().copy(status = "NOT_FOUND", product = null))) {
            var calls = 0
            val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; envelope(missing) }
            repeat(2) { images.enrich(order()) { fail("No image exists") }; runCurrent() }
            assertEquals(1, calls)
        }
        var offline = true; var calls = 0; var ready: Order? = null
        val images = ProductImages(backgroundScope) { _, _, _, _ -> calls++; if (offline) error("offline") else envelope() }
        images.enrich(order()) { ready = it }; runCurrent(); assertNull(ready)
        offline = false
        images.enrich(order()) { ready = it }; runCurrent()
        assertEquals(2, calls); assertEquals(url, ready!!.items.single().imageUrl)
    }

    @Test fun timeoutsReleaseSlotsAndCanRetryWhileQueuedReadsKeepTheirOwnDeadline() = runTest {
        var stalled = true; var calls = 0; var ready: Order? = null
        val images = ProductImages(backgroundScope) { _, path, _, query ->
            calls++
            if (stalled && path != "/products/product-5") delay(5_000)
            else delay(100)
            fromRequest(path, query)
        }
        val source = order((1..5).map { item("product-$it") })
        images.enrich(source) { ready = it }; runCurrent()
        advanceTimeBy(4_200); runCurrent()
        assertEquals(5, calls); assertEquals(1, ready!!.items.count { it.imageUrl != null })
        stalled = false
        images.enrich(source) { ready = it }; runCurrent(); advanceTimeBy(200); runCurrent()
        assertEquals(9, calls); assertTrue(ready!!.items.all { it.imageUrl == url })
    }

    @Test fun logoutCancelsReadsAndDiscardsCachedImages() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0; var callbacks = 0
        val images = ProductImages(backgroundScope) { _, _, _, _ ->
            calls++; if (calls == 1) withContext(NonCancellable) { gate.await() } else envelope()
        }
        images.enrich(order()) { callbacks++ }; runCurrent()
        images.clear(); gate.complete(envelope()); runCurrent(); assertEquals(0, callbacks)
        images.enrich(order()) { callbacks++ }; runCurrent(); assertEquals(2, calls); assertEquals(1, callbacks)
        images.clear()
        images.enrich(order()) { callbacks++ }; runCurrent(); assertEquals(3, calls)
    }

    @Test fun invalidPathIdsAreRejectedBeforeLookupAndRawSpecialCharactersArePreservedForHttpEncoding() = runTest {
        val requested = mutableListOf<String>()
        val images = ProductImages(backgroundScope) { _, path, _, query -> requested += path; fromRequest(path, query) }
        for (product in listOf(".", "..", "part/id", "part\\id", "bad\nvalue")) {
            images.enrich(order(listOf(item(product))), allowNameFallback = true) { fail("Unsafe product path") }
        }
        val raw = "상품 %20?#"
        var ready: Order? = null
        images.enrich(order(listOf(item(raw)))) { ready = it }; runCurrent()
        assertEquals(listOf("/products/$raw"), requested); assertEquals(url, ready!!.items.single().imageUrl)
    }

    @Test fun nameFallbackRequiresExplicitV1OptInAndAnUnambiguousCompleteCatalog() = runTest {
        val product = CatalogProduct("switch", name, "", "", url,
            listOf(ProductOffer("shop-a", "상점", 648000, "KRW", "IN_STOCK")))
        val result = ProductSearch("OK", listOf(product), emptyList(), listOf(MerchantRef("shop-a", "상점")),
            emptyList(), 1, false, "2026-09-23T00:00:00Z")
        var calls = 0
        val images = ProductImages(backgroundScope) { method, path, _, _ ->
            calls++; assertEquals("POST", method); assertEquals("/products/search", path)
            buildJsonObject { put("data", json.encodeToJsonElement(result)) }
        }
        val source = order(listOf(ItemSummary(name)))
        var ready: Order? = null
        images.enrich(source) { ready = it }; runCurrent(); assertEquals(0, calls)
        images.enrich(source, allowNameFallback = true) { ready = it }; runCurrent()
        assertEquals(1, calls); assertEquals(url, ready!!.items.single().imageUrl)
        assertNull(result.copy(products = listOf(product, product.copy(productId = "other")), total = 2).exactOrderImage(name))
        assertNull(result.copy(hasNext = true).exactOrderImage(name))
        assertNull(result.copy(failedMerchants = listOf("shop-b")).exactOrderImage(name))
    }
}
