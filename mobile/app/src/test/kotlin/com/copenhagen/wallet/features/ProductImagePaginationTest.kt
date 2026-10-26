package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** The merchant searches title tokens with OR, so even an exact title returns several pages. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductImagePaginationTest {
    private val merchantId = "merchant-a"
    private val name = "[닌텐도] 닌텐도 스위치 2 본체 단품"
    private val imageUrl = "https://img.example.com/switch.jpg"
    private val approvalId = "11111111-1111-4111-8111-111111111111"

    private fun product(index: Int, exact: Boolean = false) = CatalogProduct(
        productId = "product-$index", name = if (exact) name else "다른 닌텐도 상품 $index",
        brand = "", category = "", imageUrl = imageUrl,
        offers = listOf(ProductOffer(merchantId, "Copenhagen Demo Store", 12000, "KRW", "IN_STOCK")))

    private fun approval() = Approval(
        approvalId, amountMinor = 12000, currency = "KRW", status = "PENDING",
        expiresAt = "2099-01-01T00:00:00Z",
        merchants = listOf(ApprovalMerchant(merchantId, "Copenhagen Demo Store", "checkout-a", "hash-a",
            listOf(ItemSummary(name, "기본", 1, 12000)), 12000)))

    private fun page(products: List<CatalogProduct>, page: Int, pageSize: Int = 20): ProductSearch {
        val offset = (page - 1) * pageSize
        val rows = products.drop(offset).take(pageSize)
        return ProductSearch(if (rows.isEmpty()) "EMPTY" else "OK", rows, emptyList(),
            listOf(MerchantRef(merchantId, "Copenhagen Demo Store")), emptyList(), products.size,
            offset + rows.size < products.size, "2026-09-23T00:00:00Z")
    }

    private fun envelope(result: ProductSearch): JsonElement = buildJsonObject {
        put("data", json.encodeToJsonElement(result))
    }

    @Test fun exactMatchOnFirstOrLastPageSurvivesTheMerchantsHundredCandidateSearch() = runTest {
        for (matchIndex in listOf(0, 98)) {
            val products = List(100) { product(it, exact = it == matchIndex) }
            val requestedPages = mutableListOf<Int>()
            val images = ProductImages(backgroundScope) { method, path, body, _ ->
                assertEquals("POST", method)
                assertEquals("/products/search", path)
                val request = requireNotNull(body).jsonObject
                assertEquals(name, request.getValue("query").jsonPrimitive.content)
                assertEquals(merchantId, request.getValue("merchantId").jsonPrimitive.content)
                assertEquals(20, request.getValue("pageSize").jsonPrimitive.int)
                val pageNumber = request.getValue("page").jsonPrimitive.int
                requestedPages += pageNumber
                envelope(page(products, pageNumber))
            }
            var result: Approval? = null
            val original = approval()
            images.enrich(original) { result = it }
            runCurrent()

            assertEquals(listOf(1, 2, 3, 4, 5), requestedPages)
            assertEquals(imageUrl, result?.displaySummary?.single()?.imageUrl)
            assertTrue(original.sameReviewAs(requireNotNull(result)))
            images.enrich(original) { result = it }
            runCurrent()
            assertEquals("The complete result is cached", 5, requestedPages.size)
            images.clear()
        }
    }

    @Test fun firstPageExactMatchWaitsForAllPagesAndRejectsAnotherExactTitleLater() = runTest {
        val products = List(40) { product(it, exact = it == 0 || it == 39) }
        val laterPage = CompletableDeferred<JsonElement>()
        var calls = 0
        val images = ProductImages(backgroundScope) { _, _, body, _ ->
            calls++
            val pageNumber = body!!.jsonObject.getValue("page").jsonPrimitive.int
            if (pageNumber == 1) envelope(page(products, 1)) else laterPage.await()
        }
        var result: Approval? = null
        images.enrich(approval()) { result = it }
        runCurrent()
        assertEquals(2, calls)
        assertNull("A partial exact match is not safe to publish", result)

        laterPage.complete(envelope(page(products, 2)))
        runCurrent()
        assertNull("Same title on another product remains ambiguous", result)
    }

    @Test fun failedOrInconsistentPagesNeverBecomeCachedMissesAndCanRetry() = runTest {
        val products = List(40) { product(it, exact = it == 0) }
        val faults: List<Pair<String, (Int, ProductSearch) -> ProductSearch>> = listOf(
            "partial status" to { number, response ->
                if (number == 2) response.copy(status = "PARTIAL") else response
            },
            "failed merchant" to { number, response ->
                if (number == 2) response.copy(failedMerchants = listOf(merchantId)) else response
            },
            "request failure" to { number, response ->
                if (number == 2) error("catalog unavailable") else response
            },
            "changed total" to { number, response ->
                if (number == 2) response.copy(total = 39) else response
            },
            "repeated product identity" to { number, response ->
                if (number == 2) response.copy(products = listOf(response.products.first().copy(
                    productId = products.first().productId)) + response.products.drop(1)) else response
            },
            "short page" to { number, response ->
                if (number == 2) response.copy(products = response.products.dropLast(1)) else response
            },
            "next page beyond total" to { number, response ->
                if (number == 2) response.copy(hasNext = true) else response
            },
            "premature end" to { number, response ->
                if (number == 1) response.copy(hasNext = false) else response
            },
            "result beyond merchant cap" to { _, response -> response.copy(total = 101, hasNext = true) }
        )
        for ((label, corrupt) in faults) {
            var broken = true
            var calls = 0
            var result: Approval? = null
            val images = ProductImages(backgroundScope) { _, _, body, _ ->
                calls++
                val pageNumber = body!!.jsonObject.getValue("page").jsonPrimitive.int
                val response = page(products, pageNumber)
                envelope(if (broken) corrupt(pageNumber, response) else response)
            }
            images.enrich(approval()) { result = it }
            runCurrent()
            assertNull("$label must not publish an image", result)
            val failedCalls = calls
            assertTrue("$label must stop within the bounded page limit", failedCalls in 1..5)

            broken = false
            images.enrich(approval()) { result = it }
            runCurrent()
            assertEquals("$label must retry instead of caching a miss", imageUrl,
                result?.displaySummary?.single()?.imageUrl)
            assertEquals("$label restarts pagination from page one", failedCalls + 2, calls)
            images.clear()
        }
    }
}
