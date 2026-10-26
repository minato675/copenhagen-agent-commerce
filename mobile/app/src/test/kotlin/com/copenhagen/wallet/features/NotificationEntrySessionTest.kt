package com.copenhagen.wallet.features

import org.junit.Assert.*
import org.junit.Test

class NotificationEntrySessionTest {
    private val id = "550e8400-e29b-41d4-a716-446655440000"
    private val otherId = "550e8400-e29b-41d4-a716-446655440001"

    @Test fun startsWithoutAnActivePresentation() {
        val entry = NotificationEntrySession()
        assertNull(entry.activeId)
        assertNull(entry.sessionId)
        assertTrue(entry.start(id))
        assertEquals(id, entry.activeId)
        assertNotNull(entry.sessionId)
    }

    @Test fun repeatedSameIdKeepsTheOriginalSession() {
        val entry = NotificationEntrySession()
        assertTrue(entry.start(id))
        val session = entry.sessionId
        repeat(5) {
            assertTrue(entry.start(id))
            assertEquals(id, entry.activeId)
            assertEquals(session, entry.sessionId)
        }
    }

    @Test fun aDifferentNotificationCannotReplaceTheLiveRequest() {
        val entry = NotificationEntrySession()
        assertTrue(entry.start(id))
        val session = entry.sessionId
        assertFalse(entry.start(otherId))
        assertEquals(id, entry.activeId)
        assertEquals(session, entry.sessionId)
    }

    @Test fun closingInvalidatesTheOwnerAndReopeningTheSameIdCreatesANewSession() {
        val entry = NotificationEntrySession()
        assertTrue(entry.start(id))
        val previous = requireNotNull(entry.sessionId)
        entry.close()
        assertNull(entry.activeId)
        assertNull(entry.sessionId)
        assertTrue(entry.start(id))
        assertEquals(id, entry.activeId)
        assertTrue(requireNotNull(entry.sessionId) > previous)
    }

    @Test fun successiveRequestsHaveMonotonicSessionsAndCloseIsIdempotent() {
        val entry = NotificationEntrySession()
        var previous = 0L
        for (requestId in listOf(id, otherId, id, otherId)) {
            entry.close()
            entry.close()
            assertTrue(entry.start(requestId))
            val current = requireNotNull(entry.sessionId)
            assertTrue(current > previous)
            previous = current
        }
    }

    @Test fun blankIdsAreRejectedWithoutStartingOrReplacingAPresentation() {
        val entry = NotificationEntrySession()
        for (blank in listOf("", " ", "\n\t")) {
            assertThrows(IllegalArgumentException::class.java) { entry.start(blank) }
            assertNull(entry.activeId)
            assertNull(entry.sessionId)
        }
        assertTrue(entry.start(id))
        val session = entry.sessionId
        assertThrows(IllegalArgumentException::class.java) { entry.start(" ") }
        assertEquals(id, entry.activeId)
        assertEquals(session, entry.sessionId)
    }
}
