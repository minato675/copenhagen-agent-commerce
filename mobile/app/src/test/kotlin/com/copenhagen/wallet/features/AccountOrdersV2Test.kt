package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountOrdersV2Test {
    private fun order(index: Int = 1, image: String? = "https://images.example/$index.jpg") = Order(
        "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}", "상품 $index", "CONFIRMED", 1000, "KRW",
        "2026-09-23T00:00:00Z", listOf(ItemSummary("상품 $index", imageUrl = image,
            merchantId = "merchant-a", merchantProductId = "product-$index")))
    private fun page(rows: List<Order>, cursor: String? = null) = json.encodeToJsonElement(Items(rows, cursor))

    @Test fun twentySnapshotCardsAndOneDetailUseTwoRequestsAndNoCatalogLookups() = runTest {
        var catalogCalls = 0
        val images = ProductImages(backgroundScope) { _, _, _, _ -> catalogCalls++; error("no catalog lookup") }
        val rows = (1..20).map(::order)
        val paths = mutableListOf<String>()
        val account = Account({ method, path, _, query ->
            assertEquals("GET", method); paths += path
            if (path == "/api/core/v2/orders") { assertEquals("20", query["size"]); page(rows) }
            else json.encodeToJsonElement(rows[4])
        }, images)
        account.loadOrders()
        rows.forEach { account.ensureOrderThumbnail(it.id) }
        account.openOrder(rows[4].id)
        runCurrent()
        assertEquals(rows, account.orders!!.items)
        assertEquals(rows[4], account.order)
        assertEquals(listOf("/api/core/v2/orders", "/api/core/v2/orders/${rows[4].id}"), paths)
        assertEquals(0, catalogCalls)
    }

    @Test fun missingImagesDoNotStartBackgroundWorkUntilACardIsRequested() = runTest {
        val rows = (1..20).map { order(it, image = null) }
        var calls = 0
        val images = ProductImages(backgroundScope) { method, path, _, query ->
            calls++
            assertEquals("GET", method); assertEquals("/products/product-3", path)
            assertEquals("merchant-a", query["merchantId"])
            buildJsonObject { put("data", buildJsonObject { put("status", "NOT_FOUND") }) }
        }
        val account = Account({ _, _, _, _ -> page(rows) }, images)
        account.loadOrders(); runCurrent(); assertEquals(0, calls)
        account.ensureOrderThumbnail(rows[2].id); runCurrent()
        assertEquals(1, calls)
        assertEquals(rows, account.orders!!.items)
    }

    @Test fun paginationRetainsRowsAndCursorAndDoesNotDuplicateConcurrentReads() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        var calls = 0
        val first = order(); val second = order(2)
        val account = Account({ _, _, _, query ->
            calls++
            if (query["cursor"] == "opaque+/=") gate.await() else page(listOf(first), "opaque+/=")
        })
        account.loadOrders()
        val more = launch { account.loadOrders(more = true) }; runCurrent()
        account.loadOrders(more = true)
        assertEquals(2, calls)
        gate.complete(page(listOf(first, second))); more.join()
        assertEquals(listOf(first, second), account.orders!!.items)
        assertNull(account.orders!!.nextCursor)
        account.loadOrders(more = true); assertEquals(2, calls)
    }

    @Test fun oldPagesCannotOverwriteANewerFilter() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        val shipped = order(2).copy(status = "SHIPPED")
        val account = Account({ _, _, _, query ->
            when {
                query["status"] == "SHIPPED" -> page(listOf(shipped))
                query.containsKey("cursor") -> gate.await()
                else -> page(listOf(order()), "next")
            }
        })
        account.loadOrders()
        val pending = launch { account.loadOrders(true) }; runCurrent()
        account.orderStatus = "SHIPPED"; account.loadOrders()
        gate.complete(page(listOf(order(3)))); pending.join()
        assertEquals(listOf(shipped), account.orders!!.items)
    }

    @Test fun detailImmediatelyReusesTheSelectedSnapshotThenRefreshesAndRejectsLateResponses() = runTest {
        val first = order(); val second = order(2)
        val gate = CompletableDeferred<JsonElement>()
        val account = Account({ _, path, _, _ ->
            when (path) {
                "/api/core/v2/orders" -> page(listOf(first, second))
                "/api/core/v2/orders/${first.id}" -> gate.await()
                else -> json.encodeToJsonElement(second.copy(status = "DELIVERED"))
            }
        })
        account.loadOrders()
        val old = launch { account.openOrder(first.id) }; runCurrent()
        assertEquals(first, account.order)
        account.openOrder(second.id)
        gate.complete(json.encodeToJsonElement(first)); old.join()
        assertEquals(second.copy(status = "DELIVERED"), account.order)
    }

    @Test fun logoutDiscardsPendingListsAndDetails() = runTest {
        val listGate = CompletableDeferred<JsonElement>()
        val detailGate = CompletableDeferred<JsonElement>()
        val account = Account({ _, path, _, _ -> if (path.endsWith("/orders")) listGate.await() else detailGate.await() })
        val list = launch { account.loadOrders() }
        val detail = launch { account.openOrder(order().id) }
        runCurrent(); account.clear()
        listGate.complete(page(listOf(order()))); detailGate.complete(json.encodeToJsonElement(order()))
        list.join(); detail.join()
        assertNull(account.orders); assertNull(account.order)
    }

    @Test fun failedRefreshPreservesFullDetailAndAdvancesThumbnailRetryGeneration() = runTest {
        val full = order().copy(address = OrderAddress("받는 분", "010-0000-0000", "00000", "주소"))
        var fail = false
        val account = Account({ _, path, _, _ ->
            if (fail) error("offline")
            if (path.endsWith("/orders")) page(listOf(order())) else json.encodeToJsonElement(full)
        })
        account.loadOrders(); account.openOrder(full.id)
        val generation = account.orderImageRevision
        fail = true
        assertTrue(runCatching { account.openOrder(full.id) }.isFailure)
        assertEquals(full, account.order)
        assertTrue(runCatching { account.loadOrders() }.isFailure)
        assertTrue(account.orderImageRevision > generation)
        assertEquals(listOf(order()), account.orders!!.items)
    }

    @Test fun paginationKeepsLegacyImageFallbackWithItsOwnRowsAcrossAVersionUpgrade() = runTest {
        var now = 0L
        var deployed = false
        val legacyRow = order(image = null).let { it.copy(items = it.items.map { item ->
            item.copy(merchantId = null, merchantProductId = null)
        }) }
        val v2Row = order(2, image = null)
        val reads = OrderHistoryReads({ path, query ->
            if (path.startsWith("/api/core/v2") && !deployed) throw ApiError(404, "")
            if (query.containsKey("cursor")) page(listOf(v2Row)) else page(listOf(legacyRow), "next")
        }, { now })
        val imageMethods = mutableListOf<String>()
        val images = ProductImages(backgroundScope) { method, _, _, _ ->
            imageMethods += method; error("optional unavailable")
        }
        val account = Account({ _, _, _, _ -> error("orders have their own transport") }, images, reads)
        account.loadOrders()
        deployed = true; now = OrderHistoryReads.RETRY_V2_AFTER_MS
        account.loadOrders(more = true)
        account.ensureOrderThumbnail(legacyRow.id); account.ensureOrderThumbnail(v2Row.id)
        runCurrent()
        assertEquals(listOf("GET", "POST"), imageMethods.sorted())
    }

    @Test fun differentDetailIdInvalidAmountsAndDuplicateRowsAreRejected() = runTest {
        val account = Account({ _, path, _, _ ->
            if (path.endsWith("/orders")) page(listOf(order(), order())) else json.encodeToJsonElement(order(2))
        })
        assertTrue(runCatching { account.loadOrders() }.isFailure)
        assertTrue(runCatching { account.openOrder(order().id) }.isFailure)
        assertNull(account.order)
        val invalid = Account({ _, _, _, _ -> page(listOf(order().copy(amountMinor = -1))) })
        assertTrue(runCatching { invalid.loadOrders() }.isFailure)
    }
}
