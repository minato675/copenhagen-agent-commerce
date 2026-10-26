package com.copenhagen.wallet.features

import com.copenhagen.wallet.data.Http
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionsTest {
    private val server = MockWebServer()
    private val http = Http(log = {})
    private val connections = Connections { method, path ->
        http.request(server.url("/").toString(), method, "/api/core/v1$path", token = "test-user")
    }
    private val id = "550e8400-e29b-41d4-a716-446655440000"
    private val rows = """[{"connectionId":"$id","clientName":"ChatGPT","logoUrl":null,
        "scopes":["catalog:read","cart:write"],"connectedAt":"2026-09-20T00:00:00Z","lastUsedAt":null}]"""
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun readsBareArrayAndPreservesNullableMetadataThenClearsOnLogout() = runBlocking {
        server.enqueue(MockResponse().setBody(rows))
        connections.load()
        val item = connections.items!!.single()
        assertEquals("ChatGPT", item.clientName)
        assertEquals(listOf("catalog:read", "cart:write"), item.scopes)
        assertNull(item.logoUrl)
        assertNull(item.lastUsedAt)
        assertEquals("/api/core/v1/users/me/agent-connections", server.takeRequest().path)
        connections.clear()
        assertNull(connections.items)
    }

    @Test fun disconnectAcceptsEmpty204AndReloadsList() = runBlocking {
        server.enqueue(MockResponse().setBody(rows))
        connections.load(); server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("[]"))
        connections.disconnect(id)
        val deletion = server.takeRequest()
        assertEquals("DELETE", deletion.method)
        assertEquals("/api/core/v1/users/me/agent-connections/$id", deletion.path)
        assertEquals("Bearer test-user", deletion.getHeader("Authorization"))
        assertEquals("GET", server.takeRequest().method)
        assertTrue(connections.items!!.isEmpty())
    }

    @Test fun failedDisconnectKeepsExistingConnectionAndDoesNotPretendSuccess() = runBlocking {
        server.enqueue(MockResponse().setBody(rows))
        connections.load()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { connections.disconnect(id) }.isFailure)
        assertEquals(id, connections.items!!.single().connectionId)
        assertEquals(2, server.requestCount)
    }

    @Test fun failedReloadAfterSuccessfulDisconnectHidesStaleListAndAllowsRetry() = runBlocking {
        server.enqueue(MockResponse().setBody(rows))
        connections.load()
        server.enqueue(MockResponse().setResponseCode(204))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(runCatching { connections.disconnect(id) }.isFailure)
        assertNull(connections.items)
        server.enqueue(MockResponse().setBody("[]"))
        connections.load()
        assertTrue(connections.items!!.isEmpty())
    }
}
