package com.xlollx.songport.sync

import com.xlollx.songport.model.RadioSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.time.LocalDateTime
import kotlin.concurrent.thread

class RadioSamplerTest {

    /** A fake ICY stream: [blocks] metadata blocks, each after [metaInt] bytes of "audio". */
    private fun icy(metaInt: Int, vararg blocks: String, tail: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in blocks) {
            out.write(ByteArray(metaInt) { (it % 251).toByte() })
            val bytes = b.toByteArray(Charsets.UTF_8)
            val len = (bytes.size + 15) / 16
            out.write(len)
            out.write(bytes.copyOf(len * 16))
        }
        out.write(ByteArray(tail))
        return out.toByteArray()
    }

    @Test fun readsTheFirstTitleAndStopsThere() {
        val bytes = icy(16_000, "StreamTitle='Daft Punk - Digital Love';StreamUrl='';", "StreamTitle='Next - Song';", tail = 100)
        val input = ByteArrayInputStream(bytes)
        val s = RadioSampler.readTitle(input, 16_000)
        assertEquals("Daft Punk - Digital Love", s.title)
        // The audio up to the block and the block itself: no further.
        assertEquals(16_000L + 1 + 64, s.bytes)
        assertTrue("the rest of the stream is left unread", input.available() > 16_000)
    }

    @Test fun anEmptyFirstBlockIsSkipped() {
        val bytes = icy(1000, "", "StreamTitle='Björk - Jóga';")
        assertEquals("Björk - Jóga", RadioSampler.readTitle(ByteArrayInputStream(bytes), 1000).title)
    }

    @Test fun latin1TitlesAreReadToo() {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(100))
        val block = "StreamTitle='Mötley Crüe - Kickstart';".toByteArray(Charsets.ISO_8859_1)
        out.write((block.size + 15) / 16)
        out.write(block.copyOf((block.size + 15) / 16 * 16))
        assertEquals("Mötley Crüe - Kickstart", RadioSampler.readTitle(ByteArrayInputStream(out.toByteArray()), 100).title)
    }

    @Test fun noTitleInTheBlocksLookedAt() {
        val bytes = icy(500, "", "", "", "StreamTitle='Too - Late';")
        val s = RadioSampler.readTitle(ByteArrayInputStream(bytes), 500, maxBlocks = 3)
        assertNull(s.title)
        assertEquals(3L * 501, s.bytes)
    }

    @Test fun aStreamThatEndsEarlyIsAnError() {
        try {
            RadioSampler.readTitle(ByteArrayInputStream(ByteArray(200)), 16_000)
            fail()
        } catch (e: java.io.IOException) {
            assertEquals("the stream ended", e.message)
        }
    }

    @Test fun noMetadataHeaderSaysTheStreamSendsNoTitles() {
        try { RadioSampler.metaInt(null); fail() } catch (e: RadioSampler.NoTitles) { assertEquals("this stream sends no song titles", e.message) }
        try { RadioSampler.metaInt("0"); fail() } catch (_: RadioSampler.NoTitles) {}
        try { RadioSampler.metaInt("abc"); fail() } catch (_: RadioSampler.NoTitles) {}
        assertEquals(16000, RadioSampler.metaInt(" 16000 "))
    }

    @Test fun samplesAStreamOverHttp() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("icy-metaint", "8192").setHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(icy(8192, "StreamTitle='Air - Kelly Watch the Stars';", tail = 50_000))))
            val s = RadioSampler.sample(server.url("/stream").toString())
            assertEquals("Air - Kelly Watch the Stars", s.title)
            val req = server.takeRequest()
            assertEquals("1", req.getHeader("Icy-MetaData"))
        }
    }

    @Test fun aStreamWithoutTheHeaderHasNoTitles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/aac").setBody(Buffer().write(ByteArray(40_000))))
            try {
                RadioSampler.sample(server.url("/live.aac").toString())
                fail()
            } catch (e: RadioSampler.NoTitles) {
                assertEquals("this stream sends no song titles", e.message)
            }
        }
    }

    @Test fun aPlaylistLeadsToItsStream() = runBlocking {
        MockWebServer().use { server ->
            val stream = server.url("/live.mp3").toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/x-scpls").setBody("[playlist]\nNumberOfEntries=1\nFile1=$stream\n"))
            server.enqueue(MockResponse().setHeader("icy-metaint", "1000").setBody(Buffer().write(icy(1000, "StreamTitle='Moby - Porcelain';"))))
            assertEquals("Moby - Porcelain", RadioSampler.sample(server.url("/radio.pls").toString()).title)
        }
    }

    @Test fun aRedirectIsFollowedAndAnErrorSaysTheCode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/moved"))
            server.enqueue(MockResponse().setResponseCode(404))
            try {
                RadioSampler.sample(server.url("/old").toString())
                fail()
            } catch (e: RadioSampler.Refused) {
                assertEquals(404, e.code)
            }
        }
    }

    @Test fun oldShoutcastAnswersIcy200() = runBlocking {
        ServerSocket(0).use { ss ->
            val t = thread {
                ss.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    while (r.readLine().orEmpty().isNotEmpty()) Unit
                    val out = s.getOutputStream()
                    out.write("ICY 200 OK\r\nicy-name:Test FM\r\nicy-metaint:256\r\n\r\n".toByteArray())
                    out.write(icy(256, "StreamTitle='Portishead - Glory Box';", tail = 1000))
                    out.flush()
                }
            }
            assertEquals("Portishead - Glory Box", RadioSampler.sample("http://127.0.0.1:${ss.localPort}/;").title)
            t.join(5000)
        }
    }

    @Test fun theNextWindowStart() {
        val weekdays = RadioSource("R", "http://x", days = listOf(1, 2, 3, 4, 5), startMinute = 8 * 60, endMinute = 10 * 60)
        val friday9 = LocalDateTime.of(2026, 11, 6, 9, 0)
        assertEquals(LocalDateTime.of(2026, 11, 9, 8, 0), RadioTitles.nextStart(weekdays, friday9))
        assertEquals(LocalDateTime.of(2026, 11, 6, 8, 0), RadioTitles.nextStart(weekdays, friday9.withHour(7)))
        assertEquals(LocalDateTime.of(2026, 11, 3, 0, 0), RadioTitles.nextStart(RadioSource("R", "http://x"), LocalDateTime.of(2026, 11, 2, 0, 0)))
        assertNull(RadioTitles.nextStart(weekdays.copy(days = emptyList()), friday9))
    }
}
