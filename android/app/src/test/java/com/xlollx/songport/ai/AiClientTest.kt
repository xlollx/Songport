package com.xlollx.songport.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiClientTest {

    @Test fun readsJsonArrayEvenWithProseAndFences() {
        val text = """
            Here is your playlist:
            ```json
            [
              {"artist": "Daft Punk", "title": "Get Lucky", "album": "Random Access Memories"},
              {"artist": "Daft Punk", "title": "get lucky"},
              {"artist": "Phoenix", "title": "1901"}
            ]
            ```
            Enjoy!
        """.trimIndent()
        val tracks = AiClient.parseTracks(text)
        assertEquals(2, tracks.size) // the repeated track is dropped, case aside
        assertEquals("Get Lucky", tracks[0].title)
        assertEquals(listOf("Daft Punk"), tracks[0].artists)
        assertEquals("Random Access Memories", tracks[0].album)
        assertEquals("1901", tracks[1].title)
    }

    @Test fun fallsBackToPlainLines() {
        val tracks = AiClient.parseTracks("1. Daft Punk - Get Lucky\n2. Phoenix - 1901\n")
        assertEquals(listOf("Get Lucky", "1901"), tracks.map { it.title })
    }
}
