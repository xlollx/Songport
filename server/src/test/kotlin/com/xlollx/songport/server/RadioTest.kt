package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.CsvCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.time.LocalDateTime

/** A radio sync against a fake Icecast station: titles in the stream's metadata become songs in the playlists. */
class RadioTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var station: HttpServer

    /** A station that sends [titles] one after the other, 8 bytes of "audio" between each, then ends. */
    private fun startStation(titles: List<String>, icy: Boolean = true) {
        station = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        station.createContext("/stream") { ex ->
            if (icy) ex.responseHeaders.add("icy-metaint", "8")
            ex.responseHeaders.add("Content-Type", "audio/mpeg")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                for (t in titles + titles.last()) {
                    out.write(ByteArray(8) { 0x55 })
                    val meta = "StreamTitle='$t';".toByteArray()
                    val blocks = (meta.size + 15) / 16
                    out.write(blocks)
                    out.write(meta.copyOf(blocks * 16))
                    out.flush()
                }
            }
        }
        station.start()
    }

    @Before fun setUp() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
    }

    @After fun tearDown() { if (::station.isInitialized) station.stop(0) }

    private fun radioJob(stream: String): SyncJob {
        val log = runBlocking { LocalFilesProvider.createPlaylist(env, "Radio - Test FM", "") }
        val job = SyncJob(
            id = "r1", name = "Test FM radio",
            source = PlaylistRef(LocalFilesProvider.id, log.id, log.name),
            target = PlaylistRef(LocalFilesProvider.id, null, "Heard on Test FM"),
            radio = RadioSource("Test FM", stream),
        )
        env.store.upsertJob(job)
        return job
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!check()) { if (System.currentTimeMillis() > until) throw AssertionError("timed out: $what"); Thread.sleep(100) }
    }

    @Test fun songsOnAirReachTheSourceAndTheTarget() {
        startStation(listOf("Band A - Song One", "Test FM", "Advert - Spring sale", "Band B - Song Two", "Band A - Song One"))
        val job = radioJob("http://127.0.0.1:${station.address.port}/stream")
        val runner = Runner(env)
        runner.radio.tick(LocalDateTime.now())
        assertTrue(runner.radio.isListening(job.id))
        val heard = File(data, "csv/${job.source.playlistId}.csv")
        waitFor("two songs heard") { CsvCodec.parse(heard.readText()).size == 2 }
        val songs = CsvCodec.parse(heard.readText())
        assertEquals(listOf("Song One", "Song Two"), songs.map { it.title })
        assertEquals(listOf("Band A"), songs[0].artists)
        // The first new song ran the sync at once: the target exists with it.
        waitFor("the sync ran") { env.store.job(job.id)!!.lastRunEpoch > 0 && !runner.isRunning(job.id) }
        val target = env.store.job(job.id)!!.target
        assertTrue(File(data, "csv/${target.playlistId ?: target.playlistName}.csv").readText().contains("Song One"))
        // Turned off: the listening stops, with one last run.
        env.store.upsertJob(env.store.job(job.id)!!.copy(enabled = false))
        runner.radio.tick(LocalDateTime.now())
        assertFalse(runner.radio.isListening(job.id))
    }

    @Test fun aStreamWithoutTitlesSaysSo() {
        startStation(listOf("Band A - Song One"), icy = false)
        val job = radioJob("http://127.0.0.1:${station.address.port}/stream")
        val runner = Runner(env)
        runner.radio.tick(LocalDateTime.now())
        waitFor("the error") { runner.radio.status[job.id]?.error != null }
        assertTrue(runner.radio.status[job.id]!!.error!!.contains("no song titles"))
    }
}
