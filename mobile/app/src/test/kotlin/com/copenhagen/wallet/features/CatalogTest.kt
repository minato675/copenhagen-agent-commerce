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

class CatalogTest {
    private val server = MockWebServer()
    private val http = Http(log = {})
    private val catalog = Catalog { method, path, body, query ->
        http.request(server.url("/").toString(), method, "/api/core/v1$path", body, token = "test-user", query = query)
    }
    private fun product(id: String = "same-id", merchant: String = "store-a", price: String = "1000") = """{
        "productId":"$id","name":"상품","brand":"","category":"","imageUrl":null,"offers":[{
        "merchantId":"$merchant","merchantName":"판매처","price":$price,"currency":"KRW","availability":"IN_STOCK"}]}"""
    private fun response(products: String = product(), hasNext: Boolean = false, failed: String = "[]") = """{
        "publicUserRef":"user-ref","connectionRef":"default","data":{
        "status":"${if (failed == "[]") "OK" else "PARTIAL"}","products":[$products],"categories":[],
        "merchants":[{"merchantId":"store-a","name":"A몰"},{"merchantId":"store-b","name":"B몰"}],
        "failedMerchants":$failed,"total":20,"hasNext":$hasNext,"checkedAt":"2026-09-21T00:00:00Z"}}"""
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun requestUsesPublicSearchBodyAndAuthenticatedPost() = runBlocking {
        catalog.edit(ProductFilters(query = " 신발 ", merchantId = "store-a", minPrice = "3000000000", maxPrice = "4000000000", sort = ProductSort.PRICE_DESC))
        server.enqueue(MockResponse().setBody(response()))
        catalog.search(2)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/core/v1/products/search", request.path)
        assertEquals("Bearer test-user", request.getHeader("Authorization"))
        assertEquals(json.parseToJsonElement("""{"query":"신발","merchantId":"store-a","category":"","brand":"",
            "minPrice":3000000000,"maxPrice":4000000000,"sort":"PRICE_DESC","page":2,"pageSize":10}"""),
            json.parseToJsonElement(request.body.readUtf8()))
    }

    @Test fun envelopeKeepsMerchantSpecificDuplicateIdsAndUnknownPrice() = runBlocking {
        server.enqueue(MockResponse().setBody(response(product() + "," + product(merchant = "store-b", price = "null"))))
        catalog.search()
        val products = catalog.result!!.products
        assertEquals(2, products.size)
        assertEquals("store-a", products[0].offers.single().merchantId)
        assertEquals("store-b", products[1].offers.single().merchantId)
        assertNull(products[1].offers.single().price)
        assertFalse(json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.containsKey("minPrice"))
    }

    @Test fun pagesReplaceResultsAndEditingConditionsResetsToFirstPage() = runBlocking {
        server.enqueue(MockResponse().setBody(response(product("first"), hasNext = true)))
        catalog.search()
        server.enqueue(MockResponse().setBody(response(product("second"))))
        catalog.search(2)
        assertEquals(listOf("second"), catalog.result!!.products.map { it.productId })
        catalog.edit(catalog.filters.copy(query = "new"))
        assertNull(catalog.page)
        assertNull(catalog.result)
        assertEquals(2, catalog.merchants.size)
        server.enqueue(MockResponse().setBody(response(product("new"))))
        catalog.search()
        assertEquals(1, catalog.page)
        assertEquals(listOf("new"), catalog.result!!.products.map { it.productId })
    }

    @Test fun invalidPriceRangesAndOverflowNeverReachServer() = runBlocking {
        listOf(ProductFilters(minPrice = "-1"), ProductFilters(maxPrice = "abc"),
            ProductFilters(minPrice = "20", maxPrice = "10"), ProductFilters(maxPrice = "9223372036854775808")).forEach {
            catalog.edit(it)
            assertTrue(runCatching { catalog.search() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun failedPageRemembersRequestedPageForExplicitRetry() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(runCatching { catalog.search(2) }.isFailure)
        assertNull(catalog.result)
        assertEquals(2, catalog.page)
        server.enqueue(MockResponse().setBody(response(product("retry"))))
        catalog.search(catalog.page!!)
        assertEquals("retry", catalog.result!!.products.single().productId)
    }

    @Test fun partialFailureWithNoProductsIsNotTreatedAsSuccessfulEmptySearch() = runBlocking {
        server.enqueue(MockResponse().setBody(response(products = "", failed = "[\"store-b\"]")))
        catalog.search()
        assertEquals("PARTIAL", catalog.result!!.status)
        assertEquals(listOf("store-b"), catalog.result!!.failedMerchants)
        assertTrue(catalog.result!!.products.isEmpty())
    }

    @Test fun lateOldResponseCannotOverwriteNewFilterResults() = runBlocking {
        val oldResponse = CompletableDeferred<JsonElement>()
        val racing = Catalog { _, _, body, _ ->
            if (body!!.jsonObject.getValue("query").jsonPrimitive.content == "old") oldResponse.await()
            else json.parseToJsonElement(response(product("new")))
        }
        racing.edit(ProductFilters(query = "old"))
        val old = async(start = CoroutineStart.UNDISPATCHED) { racing.search() }
        racing.edit(ProductFilters(query = "new")); racing.search()
        oldResponse.complete(json.parseToJsonElement(response(product("old")))); old.await()
        assertEquals("new", racing.result!!.products.single().productId)
        assertEquals("new", racing.filters.query)
    }

    @Test fun sessionClearDiscardsPendingResponseAndAllSearchState() = runBlocking {
        val pending = CompletableDeferred<JsonElement>()
        val racing = Catalog { _, _, _, _ -> pending.await() }
        racing.edit(ProductFilters(query = "private query"))
        val search = async(start = CoroutineStart.UNDISPATCHED) { racing.search() }
        racing.clear()
        pending.complete(json.parseToJsonElement(response())); search.await()
        assertNull(racing.result)
        assertNull(racing.page)
        assertEquals(ProductFilters(), racing.filters)
        assertTrue(racing.merchants.isEmpty())
    }

    @Test fun staleFailureAfterConditionChangeDoesNotFailCurrentSearch() = runBlocking {
        val oldResponse = CompletableDeferred<JsonElement>()
        val racing = Catalog { _, _, body, _ ->
            if (body!!.jsonObject.getValue("query").jsonPrimitive.content == "old") oldResponse.await()
            else json.parseToJsonElement(response(product("new")))
        }
        racing.edit(ProductFilters(query = "old"))
        val old = async(start = CoroutineStart.UNDISPATCHED) { racing.search() }
        racing.edit(ProductFilters(query = "new")); racing.search()
        oldResponse.completeExceptionally(ApiError(503, "")); old.await()
        assertEquals("new", racing.result!!.products.single().productId)
    }
}
