package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleParserTest {
    @Test fun artistDashTitle() {
        val (title, artists) = TitleParser.parseYouTube("Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams", "DaftPunkVEVO")
        assertEquals("Get Lucky ft. Pharrell Williams", title)
        assertEquals(listOf("Daft Punk"), artists)
    }

    @Test fun topicChannel() {
        val (title, artists) = TitleParser.parseYouTube("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", "Daft Punk - Topic")
        assertEquals("Get Lucky (feat. Pharrell Williams & Nile Rodgers)", title)
        assertEquals(listOf("Daft Punk"), artists)
    }

    @Test fun noSeparatorUsesChannel() {
        val (title, artists) = TitleParser.parseYouTube("Bohemian Rhapsody [Official Video Remastered]", "Queen Official")
        assertEquals("Bohemian Rhapsody", title)
        assertEquals(listOf("Queen"), artists)
    }

    @Test fun multipleArtists() {
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), TitleParser.splitArtists("Calvin Harris, Dua Lipa"))
        assertEquals(listOf("Artist1", "Artist2"), TitleParser.splitArtists("Artist1 x Artist2"))
        assertEquals(listOf("Lil Nas X"), TitleParser.splitArtists("Lil Nas X"))
    }

    @Test fun pipeJunkRemoved() {
        val (title, _) = TitleParser.parseYouTube("Måneskin - ZITTI E BUONI | Official Video", "Måneskin")
        assertEquals("ZITTI E BUONI", title)
    }

    @Test fun durations() {
        assertEquals(205_000, Durations.parseIso8601("PT3M25S"))
        assertEquals(3_720_000, Durations.parseIso8601("PT1H2M"))
        assertEquals(0, Durations.parseIso8601(null))
        assertEquals(0, Durations.parseIso8601("garbage"))
    }
}
