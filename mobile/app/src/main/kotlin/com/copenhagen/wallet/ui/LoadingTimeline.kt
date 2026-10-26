package com.copenhagen.wallet.ui

/** Presentation time only. Never extends an API operation, authentication, or a payment lock. */
internal class LoadingTimeline {
    enum class Phase { Waiting, Loading, Content, Error }
    data class Frame(val phase: Phase, val nextUpdateMillis: Long? = null)

    private var requestedAt: Long? = null
    private var visibleAt: Long? = null

    fun advance(hasContent: Boolean, busy: Boolean, reduced: Boolean, now: Long): Frame {
        if (!hasContent && !busy) {
            reset()
            return Frame(Phase.Error)
        }
        if (reduced) {
            if (hasContent) reset() else if (visibleAt == null) visibleAt = now
            return Frame(if (hasContent) Phase.Content else Phase.Loading)
        }
        if (hasContent) {
            val remaining = visibleAt?.let { (it + MinimumVisibleMillis - now).coerceAtLeast(0) } ?: 0
            if (remaining > 0) return Frame(Phase.Loading, remaining)
            reset()
            return Frame(Phase.Content)
        }
        if (visibleAt != null) return Frame(Phase.Loading)
        val requested = requestedAt ?: now.also { requestedAt = it }
        val remaining = (requested + AppearanceDelayMillis - now).coerceAtLeast(0)
        if (remaining > 0) return Frame(Phase.Waiting, remaining)
        visibleAt = now
        return Frame(Phase.Loading)
    }

    private fun reset() { requestedAt = null; visibleAt = null }

    companion object {
        const val AppearanceDelayMillis = 120L
        const val MinimumVisibleMillis = 500L
    }
}
