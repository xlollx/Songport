package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The File service's playlists while something writes them: a radio sync adds songs while a run reads its source. */
class LocalFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aReaderNeverSeesAHalfWrittenPlaylist() {
        val env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
        val pl = runBlocking { LocalFilesProvider.createPlaylist(env, "Heard", "") }
        runBlocking { LocalFilesProvider.addTracks(env, pl.id, listOf(Track(id = "", title = "Song 0", artists = listOf("Band")))) }
        val stop = AtomicBoolean(false)
        val writer = thread {
            var i = 1
            while (!stop.get() && i < 400) {
                runBlocking { LocalFilesProvider.addTracks(env, pl.id, listOf(Track(id = "", title = "Song $i", artists = listOf("Band")))) }
                i++
            }
        }
        var last = 1
        try {
            while (writer.isAlive) {
                val n = runBlocking { LocalFilesProvider.tracks(env, pl.id) }.size
                assertTrue("read $n tracks after $last", n >= last)
                last = n
            }
        } finally {
            stop.set(true)
            writer.join()
        }
    }
}
