package com.copenhagen.wallet.ui

import org.junit.Assert.*
import org.junit.Test

class HarborSplashTest {
    @Test fun reopeningTheActivityInTheSameProcessNeverReplaysTheIntro() {
        val process = HarborSplashSession()
        val firstActivity = process.claim()!!
        firstActivity.onFrame(0L)
        firstActivity.onFrame(16_000_000L)
        // A new Activity while the same process remains in RAM cannot claim another intro,
        // even if the original Activity was closed before it finished.
        assertNull(process.claim())
        assertNull(process.claim())
    }

    @Test fun aNewProcessCanPlayAgainWithoutAnInstallOrDiskFlagReset() {
        assertNotNull(HarborSplashSession().claim())
        assertNotNull(HarborSplashSession().claim())
    }

    @Test fun readyContentStillWaitsForTheEntireDockingAndFinalLogoHold() {
        // App content can already be ready before frame zero: only visible playback releases it.
        val playback = HarborSplashPlayback()
        for (ms in 0L until HarborSplashTimeline.DurationMillis step 10L) {
            playback.onFrame(ms * 1_000_000L)
            assertFalse("Content revealed at $ms ms", playback.finished)
        }
        playback.onFrame(HarborSplashTimeline.DurationNanos)
        assertTrue(playback.finished)
        val last = HarborSplashTimeline.frame(HarborSplashTimeline.DurationMillis)
        assertEquals(0f, last.boatX, .001f)
        assertEquals(1f, last.originalLogoAlpha, 0f)
    }

    @Test fun returningFromTheBackgroundContinuesTheUnseenPartInsteadOfSkippingIt() {
        val playback = HarborSplashPlayback()
        playback.onFrame(0L)
        playback.onFrame(30_000_000L)
        playback.pause()
        playback.onFrame(60_000_000_000L)
        assertEquals(30_000_000L, playback.elapsedNanos)
        playback.onFrame(60_020_000_000L)
        assertEquals(50_000_000L, playback.elapsedNanos)
        assertFalse(playback.finished)
    }

    @Test fun oneLongFrameCannotConsumeTheWholeAnimation() {
        val playback = HarborSplashPlayback()
        playback.onFrame(0L)
        playback.onFrame(10_000_000_000L)
        assertTrue(playback.elapsedNanos < 100_000_000L)
        assertFalse(playback.finished)
    }

    @Test fun aRetainedPlaybackResumesWithoutRestartingAfterConfigurationChange() {
        val playback = HarborSplashPlayback()
        for (ms in 0L..800L step 20L) playback.onFrame(ms * 1_000_000L)
        playback.pause()
        playback.onFrame(5_000_000_000L)
        assertEquals(800_000_000L, playback.elapsedNanos)
        playback.onFrame(5_020_000_000L)
        assertEquals(820_000_000L, playback.elapsedNanos)
    }

    @Test fun theBoatArrivesWithoutOvershootingAndTheOriginalIsHeldBeforeExit() {
        var previousX = Float.MAX_VALUE
        for (ms in 0L..HarborSplashTimeline.DurationMillis step 10L) {
            val frame = HarborSplashTimeline.frame(ms)
            assertTrue(frame.boatX >= 0f && frame.boatX <= previousX)
            previousX = frame.boatX
        }
        for (ms in 1900L..HarborSplashTimeline.DurationMillis step 10L) {
            val frame = HarborSplashTimeline.frame(ms)
            assertEquals(1f, frame.originalLogoAlpha, 0f)
            assertEquals(0f, frame.bankDegrees, 0f)
            assertEquals(0f, frame.wakeAlpha, .0001f)
        }
    }

    @Test fun reducedMotionKeepsTheStationaryApprovedLogoThroughout() {
        val first = HarborSplashTimeline.frame(0L, reducedMotion = true)
        for (ms in 0L..HarborSplashTimeline.DurationMillis step 10L) {
            assertEquals(first, HarborSplashTimeline.frame(ms, reducedMotion = true))
        }
        assertEquals(1f, first.originalLogoAlpha, 0f)
        assertEquals(0f, first.boatX, 0f)
        assertEquals(0f, first.wakeAlpha, 0f)
    }
}
