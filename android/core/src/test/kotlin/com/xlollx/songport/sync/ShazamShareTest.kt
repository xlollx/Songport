package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShazamShareTest {

    @Test fun readsTheEnglishShareSentence() {
        val t = ShazamShare.track("I used Shazam to discover Get Lucky by Daft Punk. https://www.shazam.com/track/66932935/get-lucky?referrer=share")!!
        assertEquals("66932935", t.id)
        assertEquals("Get Lucky", t.title)
        assertEquals(listOf("Daft Punk"), t.artists)
    }

    @Test fun theLastByIsTheArtistOne() {
        val t = ShazamShare.track("I used Shazam to discover Stand by Me by Ben E. King. https://www.shazam.com/track/1/stand-by-me")!!
        assertEquals("Stand by Me", t.title)
        assertEquals(listOf("Ben E. King"), t.artists)
    }

    @Test fun readsTheItalianSentenceAndTheSongPath() {
        val t = ShazamShare.track("Ho usato Shazam per scoprire Zitti e buoni di Måneskin. https://www.shazam.com/it-it/song/556071474/zitti-e-buoni")!!
        assertEquals("556071474", t.id)
        assertEquals("Zitti e buoni", t.title)
        assertEquals(listOf("Måneskin"), t.artists)
    }

    @Test fun anUnknownWordingFallsBackToTheTitleInTheLink() {
        val t = ShazamShare.track("Guarda qui https://www.shazam.com/track/66932935/get-lucky-feat-pharrell-williams")!!
        assertEquals("get lucky feat pharrell williams", t.title)
        assertTrue(t.artists.isEmpty())
        assertEquals("get lucky feat pharrell williams", ShazamShare.query(t))
    }

    @Test fun otherLinksAreNotShazam() {
        assertNull(ShazamShare.track("https://open.spotify.com/track/abc"))
        assertFalse(ShazamShare.isShazam("I used Shazam once"))
        assertEquals("Daft Punk - Get Lucky", ShazamShare.query(com.xlollx.songport.model.Track("1", "Get Lucky", listOf("Daft Punk"))))
    }

    @Test fun aShazamLinkIsATrackLink() {
        assertEquals(TrackLinks.Ref("shazam", "66932935"), TrackLinks.parse("https://www.shazam.com/track/66932935/get-lucky"))
    }
}
