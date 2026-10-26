package com.copenhagen.wallet.ui

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Finite launch choreography, independent of network readiness and payment state. */
internal object HarborSplashTimeline {
    const val DurationMillis = 2180L
    const val ExitMillis = 200
    const val DurationNanos = DurationMillis * 1_000_000L

    data class Frame(
        val boatX: Float,
        val boatY: Float,
        val bankDegrees: Float,
        val wakeAlpha: Float,
        val arrival: Float,
        val originalLogoAlpha: Float,
        val artworkAlpha: Float,
        val wordmarkAlpha: Float
    )

    fun frame(elapsedMillis: Long, reducedMotion: Boolean = false): Frame {
        if (reducedMotion) return Frame(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
        val travel = segment(elapsedMillis, 160L, 1420L)
        // Decelerate to a precise berth; the boat never overshoots into the dock wall.
        val remaining = (1f - travel).pow(3)
        return Frame(
            boatX = 760f * remaining,
            boatY = 24f * sin(PI.toFloat() * travel) * (1f - travel),
            bankDegrees = -6f * remaining,
            wakeAlpha = .34f * sin(PI.toFloat() * travel).coerceAtLeast(0f),
            arrival = segment(elapsedMillis, 1370L, 1710L),
            originalLogoAlpha = smooth(segment(elapsedMillis, 1640L, 1860L)),
            artworkAlpha = smooth(segment(elapsedMillis, 0L, 140L)),
            wordmarkAlpha = smooth(segment(elapsedMillis, 920L, 1500L))
        )
    }

    private fun segment(time: Long, start: Long, end: Long): Float =
        ((time - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun smooth(value: Float): Float = value * value * (3f - 2f * value)
}

/** Counts displayed, foreground frames. Background time and scheduler stalls cannot skip the ending. */
internal class HarborSplashPlayback(restoredElapsedNanos: Long = 0L) {
    var elapsedNanos = restoredElapsedNanos.coerceIn(0L, HarborSplashTimeline.DurationNanos)
        private set
    private var previousFrame: Long? = null
    val finished get() = elapsedNanos >= HarborSplashTimeline.DurationNanos

    fun onFrame(frameNanos: Long): Long {
        val previous = previousFrame
        previousFrame = frameNanos
        if (previous != null && !finished) {
            val visibleStep = (frameNanos - previous).coerceIn(0L, 50_000_000L)
            elapsedNanos = (elapsedNanos + visibleStep).coerceAtMost(HarborSplashTimeline.DurationNanos)
        }
        return elapsedNanos
    }

    fun pause() { previousFrame = null }
}

/** One claim per in-memory app process, including when an Activity is closed and recreated. */
internal class HarborSplashSession {
    private var claimed = false

    fun claim(): HarborSplashPlayback? {
        if (claimed) return null
        claimed = true
        return HarborSplashPlayback()
    }
}
