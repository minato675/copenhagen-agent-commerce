package com.copenhagen.wallet.features

/** In-memory notification presentation ownership, independent of payment actions and results. */
class NotificationEntrySession {
    var activeId: String? = null; private set
    var sessionId: Long? = null; private set
    private var sequence = 0L

    /** Repeated delivery preserves the live request; another ID cannot replace its presentation. */
    fun start(id: String): Boolean {
        require(id.isNotBlank())
        if (activeId != null) return activeId == id
        activeId = id
        sessionId = ++sequence
        return true
    }

    /** Invalidates the presentation owner without changing any payment or authentication state. */
    fun close() {
        activeId = null
        sessionId = null
    }
}
