package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ParallelRunsTest {
    private fun job(id: String, src: String, dst: String, dstList: String? = "p") =
        SyncJob(id, id, PlaylistRef(src, "s", "S"), PlaylistRef(dst, dstList, "T"))

    @Test fun differentAccountsTogetherTheSameAccountInTurn() = runBlocking {
        val jobs = listOf(
            job("a", "spotify", "ytm"), job("b", "deezer", "navidrome"), job("c", "spotify", "amazon"),
            job("d", "tidal", "jellyfin"), job("e", "plex", "csv", "x"), job("f", "lastfm", "csv", "y"),
        )
        val running = Collections.synchronizedSet(HashSet<String>())
        var most = 0
        val order = Collections.synchronizedList(ArrayList<String>())
        ParallelRuns.run(jobs) { j ->
            synchronized(running) {
                // No account held twice at once.
                jobs.filter { it.id in running }.forEach { other -> assertTrue(ParallelRuns.keys(other).intersect(ParallelRuns.keys(j)).isEmpty()) }
                running += j.id
                most = maxOf(most, running.size)
            }
            delay(50)
            order += j.id
            running -= j.id
        }
        assertEquals(6, order.size)
        assertEquals(ParallelRuns.MAX, most)
        // "c" shares Spotify with "a": it ends after it.
        assertTrue(order.indexOf("c") > order.indexOf("a"))
    }

    @Test fun twoFilesAreTwoThingsOneFileIsOne() {
        assertTrue(ParallelRuns.keys(job("a", "plex", "csv", "x")).intersect(ParallelRuns.keys(job("b", "deezer", "csv", "y"))).isEmpty())
        assertEquals(setOf("csv:x"), ParallelRuns.keys(job("a", "plex", "csv", "x")).intersect(ParallelRuns.keys(job("b", "deezer", "csv", "x"))))
    }
}
