package com.xlollx.songport.sync

import com.xlollx.songport.model.Progress
import com.xlollx.songport.platform.Env
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
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

    /** Only what checkpoint() needs: the text of a stop. */
    private val env = java.lang.reflect.Proxy.newProxyInstance(Env::class.java.classLoader, arrayOf(Env::class.java)) { _, m, args ->
        if (m.name == "text") args!![0] as String else throw UnsupportedOperationException(m.name)
    } as Env

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    /** Pause is cooperative: asked for, the sync is "Pausing"; only once the engine waits is it "Paused". A stop wins over both. */
    @Test fun runStateFollowsTheEngine() {
        assertEquals(SyncState.Run.RUNNING, SyncState.runOf(pauseAsked = false, parked = false, stopping = false))
        assertEquals(SyncState.Run.PAUSING, SyncState.runOf(pauseAsked = true, parked = false, stopping = false))
        assertEquals(SyncState.Run.PAUSED, SyncState.runOf(pauseAsked = true, parked = true, stopping = false))
        // Resumed, the engine not yet out of its wait: already running again.
        assertEquals(SyncState.Run.RUNNING, SyncState.runOf(pauseAsked = false, parked = true, stopping = false))
        assertEquals(SyncState.Run.STOPPING, SyncState.runOf(pauseAsked = true, parked = true, stopping = true))
        assertEquals(SyncState.Run.STOPPING, SyncState.runOf(pauseAsked = false, parked = false, stopping = true))
    }

    /** The top pill and the notification title: "N syncs paused" only when every running sync waits. */
    @Test fun tallyCountsOnlySyncsReallyPaused() {
        val all = listOf("a", "b", "c", "d")
        val none = SyncState.tally(all, emptySet(), emptySet(), emptySet())
        assertEquals(SyncState.Tally(4, 0), none)
        assertFalse(none.allPaused); assertFalse(none.somePaused)
        // Pause all pressed, every engine still finishing its step: nothing paused yet.
        val pausing = SyncState.tally(all, all.toSet(), emptySet(), emptySet())
        assertEquals(0, pausing.paused); assertFalse(pausing.allPaused)
        val mixed = SyncState.tally(all, all.toSet(), setOf("a", "b", "c"), emptySet())
        assertEquals(1, mixed.running); assertEquals(3, mixed.paused); assertTrue(mixed.somePaused); assertFalse(mixed.allPaused)
        val paused = SyncState.tally(all, all.toSet(), all.toSet(), emptySet())
        assertTrue(paused.allPaused); assertFalse(paused.somePaused); assertEquals(0, paused.running)
        // A paused sync being stopped is no longer counted as paused.
        assertEquals(3, SyncState.tally(all, all.toSet(), all.toSet(), setOf("d")).paused)
        assertFalse(SyncState.Tally(0, 0).allPaused)
    }

    /** checkpoint() marks the sync parked while it waits, and clears the mark on resume. */
    @Test fun checkpointParksWhilePausedAndLeavesOnResume() {
        val id = "parking"
        SyncState.start(id)
        try {
            SyncState.checkpoint(env, id)
            assertEquals(SyncState.Run.RUNNING, SyncState.run(id))
            SyncState.pause(id)
            assertEquals(SyncState.Run.PAUSING, SyncState.run(id))
            val engine = thread { SyncState.checkpoint(env, id) }
            waitUntil("the engine parked") { id in SyncState.parked.value }
            assertEquals(SyncState.Run.PAUSED, SyncState.run(id))
            SyncState.resume(id)
            engine.join(5_000)
            assertFalse(engine.isAlive)
            assertFalse(id in SyncState.parked.value)
            assertEquals(SyncState.Run.RUNNING, SyncState.run(id))
        } finally {
            SyncState.finish(id)
        }
    }

    /** Stop while paused: the wait ends, the engine stops, and nothing stays parked. */
    @Test fun stopGoesThroughAPause() {
        val id = "parked-stop"
        SyncState.start(id)
        try {
            SyncState.pause(id)
            val thrown = AtomicReference<Throwable?>()
            val engine = thread { try { SyncState.checkpoint(env, id) } catch (e: Throwable) { thrown.set(e) } }
            waitUntil("the engine parked") { id in SyncState.parked.value }
            SyncState.stop(id)
            assertEquals(SyncState.Run.STOPPING, SyncState.run(id))
            engine.join(5_000)
            assertFalse(engine.isAlive)
            assertTrue("stopped: ${thrown.get()}", thrown.get() is SyncStoppedException)
            assertFalse(id in SyncState.parked.value)
        } finally {
            SyncState.finish(id)
        }
        assertFalse(id in SyncState.paused.value || id in SyncState.stopping.value || id in SyncState.parked.value)
    }
}
