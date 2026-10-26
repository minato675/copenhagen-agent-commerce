package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CartTest {
    private val server = MockWebServer()
    private val http = Http(log = {})
    private val cart = Cart { method, path, body, query ->
        http.request(server.url("/").toString(), method, "/api/core/v1$path", body, token = "test-user", query = query)
    }
    private val itemId = "550e8400-e29b-41d4-a716-446655440000"
    private val emptyCart = """{"cartId":null,"version":0,"items":[],"summary":{
        "itemsTotal":0,"discountTotal":0,"shippingFee":0,"estimatedTotal":0,"currency":"KRW"}}"""
    private fun response(version: Int = 3, quantity: Int = 2) = """{
        "cartId":"550e8400-e29b-41d4-a716-446655440001","version":$version,"items":[{
        "cartItemId":"$itemId","merchantId":"store-a","productId":"product-a","optionValueIds":["black"],
        "productName":"상품","productUrl":null,"imageUrl":null,"quantity":$quantity,
        "unitPrice":{"amount":3000000000,"currency":"KRW"},"lineTotal":${3000000000L * quantity}}],
        "summary":{"itemsTotal":${3000000000L * quantity},"discountTotal":0,"shippingFee":0,
        "estimatedTotal":${3000000000L * quantity},"currency":"KRW"}}"""
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun absentCartIsAValidEmptyCartNotAnError() = runBlocking {
        server.enqueue(MockResponse().setBody(emptyCart))
        cart.load()
        assertNull(cart.view!!.cartId)
        assertEquals(0, cart.view!!.version)
        assertTrue(cart.view!!.items.isEmpty())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/core/v1/cart", request.path)
        assertEquals("Bearer test-user", request.getHeader("Authorization"))
    }

    @Test fun keepsServerVersionOptionsAndLongAmountsWithoutRecalculatingTotals() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        val view = cart.view!!
        assertEquals(3, view.version)
        assertEquals(listOf("black"), view.items.single().optionValueIds)
        assertEquals(3000000000L, view.items.single().unitPrice.amount)
        assertEquals(6000000000L, view.summary.estimatedTotal)
        assertEquals("6,000,000,000원", money(view.summary.estimatedTotal, view.summary.currency))
        cart.clear()
        assertNull(cart.view)
    }

    @Test fun failedRefreshHidesPreviousAccountDataAndAllowsRetry() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(runCatching { cart.load() }.exceptionOrNull() is ApiError)
        assertNull(cart.view)
        server.enqueue(MockResponse().setBody(emptyCart))
        cart.load()
        assertTrue(cart.view!!.items.isEmpty())
    }

    @Test fun missingSummaryIsNotPresentedAsAnEmptyCart() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"cartId":null,"version":0,"items":[]}"""))
        assertTrue(runCatching { cart.load() }.isFailure)
        assertNull(cart.view)
    }

    @Test fun consecutiveQuantityChangesUseReloadedVersionsAndTotals() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load(); server.takeRequest()
        for (version in 4..5) {
            server.enqueue(MockResponse().setBody("""{"cartVersion":$version}"""))
            server.enqueue(MockResponse().setBody(response(version, version)))
            cart.updateQuantity(itemId, version)
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/core/v1/cart/items/$itemId", patch.path)
            assertEquals(json.parseToJsonElement("""{"cartVersion":${version - 1},"quantity":$version}"""),
                json.parseToJsonElement(patch.body.readUtf8()))
            assertEquals("GET", server.takeRequest().method)
            assertEquals(version, cart.view!!.version)
            assertEquals(3000000000L * version, cart.view!!.summary.estimatedTotal)
        }
    }

    @Test fun deletingLastItemSendsVersionInQueryAndReads200ThenEmptyCart() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load(); server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"cartVersion":4}"""))
        server.enqueue(MockResponse().setBody(emptyCart.replace("\"version\":0", "\"version\":4")))
        cart.remove(itemId)
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("/api/core/v1/cart/items/$itemId?cartVersion=3", deletion.path)
        assertEquals(0L, deletion.bodySize)
        assertTrue(cart.view!!.items.isEmpty())
        assertEquals(4, cart.view!!.version)
    }

    @Test fun conflictReloadsAndExplainsChangeWithoutReplayingWrite() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"CART_VERSION_CONFLICT"}"""))
        server.enqueue(MockResponse().setBody(response(8, 6)))
        cart.updateQuantity(itemId, 3)
        assertEquals(8, cart.view!!.version)
        assertEquals(6, cart.view!!.items.single().quantity)
        assertNotNull(cart.notice)
        assertEquals(3, server.requestCount)
    }

    @Test fun missingItemReloadFailureBlocksFurtherEditingUntilReadSucceeds() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"CART_ITEM_NOT_FOUND"}"""))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(runCatching { cart.remove(itemId) }.isFailure)
        assertNull(cart.view)
        assertNotNull(cart.notice)
        assertTrue(runCatching { cart.updateQuantity(itemId, 3) }.isFailure)
        assertEquals(5, server.requestCount) // One read, one DELETE, and three failed reload attempts.
        server.enqueue(MockResponse().setBody(emptyCart))
        cart.load()
        assertTrue(cart.view!!.items.isEmpty())
    }

    @Test fun rejectedQuantityDoesNotOptimisticallyChangeVersionOrQuantity() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"code":"CART_QUANTITY_LIMIT_EXCEEDED"}"""))
        assertTrue(runCatching { cart.updateQuantity(itemId, 999) }.isFailure)
        assertEquals(3, cart.view!!.version)
        assertEquals(2, cart.view!!.items.single().quantity)
        assertEquals(2, server.requestCount)
    }

    @Test fun lostWriteResponseRequiresReadBeforeNextEditAndDoesNotReplay() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(runCatching { cart.updateQuantity(itemId, 3) }.isFailure)
        assertNull(cart.view)
        assertNotNull(cart.notice)
        assertTrue(runCatching { cart.remove(itemId) }.isFailure)
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setBody(response(4, 3)))
        cart.load()
        assertEquals(4, cart.view!!.version)
        assertEquals(3, cart.view!!.items.single().quantity)
        assertNull(cart.notice)
    }

    @Test fun invalidQuantityAndUnknownItemNeverSendWrite() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        assertTrue(runCatching { cart.updateQuantity(itemId, 0) }.isFailure)
        assertTrue(runCatching { cart.updateQuantity(itemId, -1) }.isFailure)
        assertTrue(runCatching { cart.remove("../../users/me") }.isFailure)
        assertTrue(runCatching { cart.remove("550e8400-e29b-41d4-a716-446655440099") }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun delete204IsNotMistakenForTheVersionResponse() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        cart.load()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(runCatching { cart.remove(itemId) }.isFailure)
        assertNull(cart.view)
        assertNotNull(cart.notice)
    }

    @Test fun cancellationPropagatesAndDiscardsUncertainVersion() = runBlocking {
        val interrupted = Cart { method, _, _, _ ->
            if (method == "PATCH") throw CancellationException("cancelled")
            json.parseToJsonElement(response())
        }
        interrupted.load()
        assertTrue(runCatching { interrupted.updateQuantity(itemId, 3) }.exceptionOrNull() is CancellationException)
        assertNull(interrupted.view)
    }
}
