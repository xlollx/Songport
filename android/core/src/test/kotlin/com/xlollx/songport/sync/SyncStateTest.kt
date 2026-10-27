package com.xlollx.songport.sync

import com.xlollx.songport.model.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SyncStateTest {
    /**
     * Two syncs on two threads (the server runs two at a time, the app's workers too): one reports
     * progress all the time while the other starts and finishes. No update may be lost, or a finished
     * sync stays "running" for good and can never start again.
     */
    @Test fun concurrentUpdatesAreNotLost() {
        val go = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val busy = thread {
            go.await()
            var i = 0
            while (!done.get()) SyncState.progress("busy", Progress(Progress.Step.MATCHING, i++, 1_000_000))
        }
        var lost = 0
        val other = thread {
            go.await()
            repeat(100_000) {
                SyncState.start("other")
                SyncState.pause("other")
                SyncState.finish("other")
                // Whatever the busy thread writes next must not bring the finished sync back.
                repeat(50) { Thread.onSpinWait() }
                if ("other" in SyncState.running.value) { lost++; SyncState.finish("other") }
            }
        }
        go.countDown()
        other.join()
        done.set(true)
        busy.join()
        assertEquals("finished syncs shown as running again", 0, lost)
        assertFalse("other" in SyncState.running.value)
        assertFalse(SyncState.isPaused("other"))
        SyncState.finish("busy")
        assertEquals(emptyMap<String, Progress?>(), SyncState.running.value.filterKeys { it == "busy" || it == "other" })
    }
}
