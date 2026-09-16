package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CsvCodecTest {
    @Test fun roundTrip() {
        val tracks = listOf(
            Track("1", "Hello, World", listOf("A", "B"), "Album \"X\"", 200_000, "ISRC1"),
            Track("2", "Multi\nline", listOf("C"), "", 0, null),
        )
        val parsed = CsvCodec.parse(CsvCodec.encode(tracks))
        assertEquals(2, parsed.size)
        assertEquals("Hello, World", parsed[0].title)
        assertEquals(listOf("A", "B"), parsed[0].artists)
        assertEquals("Album \"X\"", parsed[0].album)
        assertEquals(200_000L, parsed[0].durationMs)
        assertEquals("ISRC1", parsed[0].isrc)
        assertEquals("Multi\nline", parsed[1].title)
        assertEquals(0L, parsed[1].durationMs)
    }

    @Test fun exportifyHeaders() {
        val csv = "Track URI,Track Name,Album Name,Artist Name(s),Release Date,Duration (ms),ISRC\n" +
            "spotify:track:1,Get Lucky,Random Access Memories,\"Daft Punk, Pharrell Williams\",2013,369626,GBTDF1300003\n"
        val p = CsvCodec.parse(csv)
        assertEquals(1, p.size)
        assertEquals("Get Lucky", p[0].title)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), p[0].artists)
        assertEquals(369_626L, p[0].durationMs)
        assertEquals("GBTDF1300003", p[0].isrc)
    }

    @Test fun semicolonAndMmSs() {
        val csv = "Titolo;Artista;Durata\nVolare;Domenico Modugno;3:31\n"
        val p = CsvCodec.parse(csv)
        assertEquals("Volare", p[0].title)
        assertEquals(211_000L, p[0].durationMs)
    }

    @Test fun stableIds() {
        val a = CsvCodec.withStableId(Track("", "Song (Remastered)", listOf("Band")))
        val b = CsvCodec.withStableId(Track("zzz", "song", listOf("The Band")))
        val c = CsvCodec.withStableId(Track("", "Other", listOf("Band")))
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
    }

    @Test fun safeNames() {
        assertEquals("My_ Playlist", CsvCodec.safeName("My/ Playlist"))
        assertEquals("playlist", CsvCodec.safeName("   "))
    }
}
