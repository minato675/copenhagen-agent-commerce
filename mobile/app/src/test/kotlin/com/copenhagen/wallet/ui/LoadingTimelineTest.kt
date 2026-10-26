package com.copenhagen.wallet.ui

import com.copenhagen.wallet.ui.LoadingTimeline.Phase.*
import org.junit.Assert.*
import org.junit.Test

class LoadingTimelineTest {
    @Test fun fastReadsNeverFlashALoadingCard() {
        for (latency in listOf(0L, 80L, 119L)) {
            val timeline = LoadingTimeline()
            assertEquals(Waiting, timeline.advance(false, true, false, 0).phase)
            assertEquals(Content, timeline.advance(true, false, false, latency).phase)
            assertEquals(Content, timeline.advance(true, false, false, 120).phase)
        }
    }

    @Test fun aVisibleLoaderGetsHalfASecondBeforeTheContentTransition() {
        val timeline = LoadingTimeline()
        assertEquals(LoadingTimeline.Frame(Waiting, 120), timeline.advance(false, true, false, 0))
        assertEquals(Loading, timeline.advance(false, true, false, 120).phase)
        assertEquals(LoadingTimeline.Frame(Loading, 370), timeline.advance(true, false, false, 250))
        assertEquals(LoadingTimeline.Frame(Loading, 1), timeline.advance(true, false, false, 619))
        assertEquals(Content, timeline.advance(true, false, false, 620).phase)
    }

    @Test fun minimumStartsWhenTheLoaderActuallyAppearsEvenIfSchedulingWasLate() {
        val timeline = LoadingTimeline()
        timeline.advance(false, true, false, 0)
        timeline.advance(false, true, false, 250)
        assertEquals(LoadingTimeline.Frame(Loading, 499), timeline.advance(true, false, false, 251))
        assertEquals(Content, timeline.advance(true, false, false, 750).phase)
    }

    @Test fun longReadsTransitionToContentAsSoonAsTheyFinish() {
        for (latency in listOf(800L, 2000L, 8000L, 30000L)) {
            val timeline = LoadingTimeline()
            timeline.advance(false, true, false, 0)
            timeline.advance(false, true, false, 120)
            assertEquals(LoadingTimeline.Frame(Loading), timeline.advance(false, true, false, latency - 1))
            assertEquals(LoadingTimeline.Frame(Content), timeline.advance(true, false, false, latency))
        }
    }

    @Test fun cachedContentDoesNotBecomeALoaderDuringRefresh() {
        val timeline = LoadingTimeline()
        for (time in listOf(0L, 80L, 250L, 8000L)) {
            assertEquals(LoadingTimeline.Frame(Content), timeline.advance(true, true, false, time))
        }
    }

    @Test fun anIndependentReadStillRunningCannotDelayReadyContentAgain() {
        val timeline = LoadingTimeline()
        timeline.advance(false, true, false, 0)
        timeline.advance(false, true, false, 120)
        assertEquals(LoadingTimeline.Frame(Loading, 370), timeline.advance(true, true, false, 250))
        assertEquals(Content, timeline.advance(true, true, false, 620).phase)
        assertEquals(Content, timeline.advance(true, false, false, 900).phase)
    }

    @Test fun errorsInterruptTheMinimumAndRetriesStartAFreshPresentation() {
        val timeline = LoadingTimeline()
        timeline.advance(false, true, false, 0)
        timeline.advance(false, true, false, 120)
        assertEquals(Error, timeline.advance(false, false, false, 150).phase)
        assertEquals(LoadingTimeline.Frame(Waiting, 120), timeline.advance(false, true, false, 200))
        assertEquals(Content, timeline.advance(true, false, false, 250).phase)
    }

    @Test fun repeatedBusyChangesDoNotRestartAnAlreadyVisibleLoader() {
        val timeline = LoadingTimeline()
        timeline.advance(false, true, false, 0)
        timeline.advance(false, true, false, 120)
        timeline.advance(true, false, false, 200)
        assertEquals(Loading, timeline.advance(false, true, false, 300).phase)
        assertEquals(LoadingTimeline.Frame(Loading, 220), timeline.advance(true, false, false, 400))
        assertEquals(Content, timeline.advance(true, false, false, 620).phase)
    }

    @Test fun reducedMotionSkipsBothTimingGatesIncludingAMidFlightPreferenceChange() {
        val timeline = LoadingTimeline()
        assertEquals(LoadingTimeline.Frame(Loading), timeline.advance(false, true, true, 0))
        assertEquals(Content, timeline.advance(true, false, true, 1).phase)
        timeline.advance(false, true, false, 10)
        timeline.advance(false, true, false, 130)
        assertEquals(Content, timeline.advance(true, false, true, 131).phase)
    }

    @Test fun leavingAndReopeningDoesNotInheritAnotherScreensHold() {
        val outgoing = LoadingTimeline()
        outgoing.advance(false, true, false, 0)
        outgoing.advance(false, true, false, 120)
        val destination = LoadingTimeline()
        assertEquals(Content, destination.advance(true, false, false, 150).phase)
        assertEquals(LoadingTimeline.Frame(Waiting, 120), destination.advance(false, true, false, 200))
    }
}
