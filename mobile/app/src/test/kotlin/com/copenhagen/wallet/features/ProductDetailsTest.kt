package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProductDetailsTest {
    private val server = MockWebServer()
    private val http = Http(log = {})
    private val details = ProductDetails { method, path, body, query ->
        http.request(server.url("/").toString(), method, "/api/core/v1$path", body, token = "test-user", query = query)
    }
    private val selected = ProductSelection("product-a", "store-a")
    private fun response(selection: ProductSelection = selected, status: String = "FOUND", stock: String = "null", fee: String = "null",
        purchase: String = "null", availability: String = "IN_STOCK"): String {
        val product = if (status == "NOT_FOUND") "null" else """{
            "productId":${JsonPrimitive(selection.productId)},"name":"상품","brand":"","category":"","imageUrl":null,
            "description":"설명","specs":[{"name":"색상","value":"검정"}],
            "offer":{"merchantId":${JsonPrimitive(selection.merchantId)},"merchantName":"판매처",
            "price":${if (selection.optionId == null) "null" else "3000"},"currency":"KRW","availability":"$availability"},
            "variants":[{"optionId":${JsonPrimitive(selection.optionId ?: "black")},"label":"검정","price":3000,"availability":"$availability","purchaseAllowed":$purchase}],
            "optionsComplete":${selection.optionId != null},"optionsRequired":true,
            "selectionStatus":"${if (selection.optionId == null) "REQUIRED" else "VALID"}",
            "selectedOptionId":${selection.optionId?.let { JsonPrimitive(it) } ?: JsonNull},"stockQuantity":$stock,
            "shipping":{"status":"${if (fee == "null") "UNKNOWN" else "KNOWN"}","fee":$fee,"description":""}}"""
        return """{"publicUserRef":"user-ref","connectionRef":"default","data":{
            "status":"$status","request":{"productId":${JsonPrimitive(selection.productId)},
            "merchantId":${JsonPrimitive(selection.merchantId)},"optionId":${selection.optionId?.let { JsonPrimitive(it) } ?: JsonNull}},
            "product":$product,"checkedAt":"2026-09-21T00:00:00Z"}}"""
    }
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun requiredMerchantAndProductIdAreEncodedWithoutAddingOptionalOption() = runBlocking {
        val special = ProductSelection("상품 ?#%", "store & one")
        server.enqueue(MockResponse().setBody(response(special)))
        details.load(special)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("Bearer test-user", request.getHeader("Authorization"))
        assertEquals(listOf("api", "core", "v1", "products", special.productId), request.requestUrl!!.pathSegments)
        assertEquals(special.merchantId, request.requestUrl!!.queryParameter("merchantId"))
        assertNull(request.requestUrl!!.queryParameter("optionId"))
    }

    @Test fun selectedOptionChangesRequestAndServerResolvedPrice() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        details.load(selected)
        assertNull(details.result!!.product!!.offer.price)
        val withOption = selected.copy(optionId = "black & white")
        server.enqueue(MockResponse().setBody(response(withOption, stock = "12", fee = "2500", purchase = "true")))
        details.load(withOption)
        server.takeRequest()
        assertEquals(withOption.optionId, server.takeRequest().requestUrl!!.queryParameter("optionId"))
        val product = details.result!!.product!!
        assertEquals(withOption.optionId, product.selectedOptionId)
        assertEquals(3000L, product.offer.price)
        assertEquals(12L, product.stockQuantity)
        assertEquals(2500L, product.shipping.fee)
        assertEquals(true, product.variants.single().purchaseAllowed)
    }

    @Test fun openingAnotherMerchantStartsWithoutPreviousOption() = runBlocking {
        val first = selected.copy(optionId = "black")
        server.enqueue(MockResponse().setBody(response(first))); details.load(first)
        val other = selected.copy(merchantId = "store-b")
        server.enqueue(MockResponse().setBody(response(other))); details.load(other)
        server.takeRequest()
        val query = server.takeRequest().requestUrl!!
        assertEquals("store-b", query.queryParameter("merchantId"))
        assertNull(query.queryParameter("optionId"))
        assertNull(details.result!!.product!!.selectedOptionId)
    }

    @Test fun absentPriceStockShippingAndPurchasePermissionRemainUnknown() = runBlocking {
        server.enqueue(MockResponse().setBody(response())); details.load(selected)
        val product = details.result!!.product!!
        assertNull(product.offer.price)
        assertNull(product.stockQuantity)
        assertNull(product.shipping.fee)
        assertNull(product.variants.single().purchaseAllowed)
        assertEquals("REQUIRED", product.selectionStatus)
        assertFalse(product.optionsComplete)
    }

    @Test fun soldOutAndDisallowedVariantAreNotConvertedIntoAvailability() = runBlocking {
        server.enqueue(MockResponse().setBody(response(stock = "0", purchase = "false", availability = "OUT_OF_STOCK")))
        details.load(selected)
        assertEquals(0L, details.result!!.product!!.stockQuantity)
        assertEquals(false, details.result!!.product!!.variants.single().purchaseAllowed)
        assertEquals("품절", availabilityLabel(details.result!!.product!!.offer.availability))
    }

    @Test fun successfulNotFoundEnvelopeIsDifferentFromTransportFailure() = runBlocking {
        server.enqueue(MockResponse().setBody(response(status = "NOT_FOUND"))); details.load(selected)
        assertEquals("NOT_FOUND", details.result!!.status)
        assertNull(details.result!!.product)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(runCatching { details.reload() }.isFailure)
        assertNull(details.result)
        server.enqueue(MockResponse().setBody(response())); details.reload()
        assertEquals("FOUND", details.result!!.status)
    }

    @Test fun invalidPathSegmentsAndMissingMerchantNeverReachServer() = runBlocking {
        listOf("", ".", "..", "../cart", "a/b", "a\\b").forEach { id ->
            assertTrue(runCatching { details.load(selected.copy(productId = id)) }.isFailure)
        }
        assertTrue(runCatching { details.load(selected.copy(merchantId = "")) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun laterOptionResponseWinsEvenWhenOlderResponseArrivesLast() = runBlocking {
        val delayed = CompletableDeferred<JsonElement>()
        val racing = ProductDetails { _, _, _, query ->
            if (query["optionId"] == null) delayed.await()
            else json.parseToJsonElement(response(selected.copy(optionId = query["optionId"])))
        }
        val old = async(start = CoroutineStart.UNDISPATCHED) { racing.load(selected) }
        racing.load(selected.copy(optionId = "black"))
        delayed.complete(json.parseToJsonElement(response())); old.await()
        assertEquals("black", racing.selection!!.optionId)
        assertEquals("black", racing.result!!.product!!.selectedOptionId)
    }

    @Test fun closingDetailOrSessionDiscardsPendingResult() = runBlocking {
        val delayed = CompletableDeferred<JsonElement>()
        val racing = ProductDetails { _, _, _, _ -> delayed.await() }
        val old = async(start = CoroutineStart.UNDISPATCHED) { racing.load(selected) }
        racing.clear()
        delayed.complete(json.parseToJsonElement(response())); old.await()
        assertNull(racing.selection)
        assertNull(racing.result)
    }

    @Test fun foundEnvelopeWithoutProductIsAnErrorNotAnEmptyProduct() = runBlocking {
        server.enqueue(MockResponse().setBody(response(status = "NOT_FOUND").replace("NOT_FOUND", "FOUND")))
        assertTrue(runCatching { details.load(selected) }.isFailure)
        assertNull(details.result)
    }
}
