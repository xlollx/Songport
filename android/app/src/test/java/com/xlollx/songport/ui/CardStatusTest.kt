package com.xlollx.songport.ui

import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which pill a sync card wears: the most important thing the last run left for the user. */
class CardStatusTest {
    private fun report(heldBack: Int = 0, notFound: Int = 0, interrupted: Boolean = false, error: String? = null) = SyncReport(
        id = "r", jobId = "j", jobName = "Liked songs", startedEpoch = 0, durationMs = 0,
        unmatchedTracks = (1..notFound).map { Track(id = "n$it", title = "Song $it") },
        heldBack = heldBack, searchInterrupted = interrupted, error = error,
    )

    @Test fun removalsOnHoldWinOverNotFound() {
        // The owner's Liked songs: one song not found, hundreds of unlikes held back.
        assertEquals(CardStatus.HELD_BACK, cardStatus(isRunning = false, enabled = true, report = report(heldBack = 412, notFound = 1)))
    }

    @Test fun removalsOnHoldWinOverAnIncompleteSearch() {
        assertEquals(CardStatus.HELD_BACK, cardStatus(false, true, report(heldBack = 20, notFound = 3, interrupted = true)))
    }

    @Test fun notFoundWhenNothingIsHeld() {
        assertEquals(CardStatus.NOT_FOUND, cardStatus(false, true, report(notFound = 1)))
        assertEquals(CardStatus.OK, cardStatus(false, true, report()))
    }

    @Test fun runningDisabledNeverAndErrorComeFirst() {
        assertEquals(CardStatus.RUNNING, cardStatus(true, true, report(heldBack = 5)))
        assertEquals(CardStatus.DISABLED, cardStatus(false, false, report(heldBack = 5)))
        assertEquals(CardStatus.NEVER, cardStatus(false, true, null))
        assertEquals(CardStatus.ERROR, cardStatus(false, true, report(heldBack = 5, error = "HTTP 500")))
    }
}
