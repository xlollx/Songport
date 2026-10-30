package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLinksTest {
    private fun ref(url: String) = TrackLinks.parse(url)

    @Test fun spotify() {
        assertEquals(TrackLinks.Ref("spotify", "4uLU6hMCjMI75M1A2tKUQC"), ref("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc"))
        assertEquals(TrackLinks.Ref("spotify", "4uLU6hMCjMI75M1A2tKUQC"), ref("https://open.spotify.com/intl-it/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals(TrackLinks.Ref("spotify", "4uLU6hMCjMI75M1A2tKUQC"), ref("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
    }

    @Test fun appleMusic() {
        assertEquals(TrackLinks.Ref("apple", "1440857781"), ref("https://music.apple.com/it/album/noise/1440857775?i=1440857781"))
        assertEquals(TrackLinks.Ref("apple", "1440857781"), ref("https://music.apple.com/us/song/noise/1440857781"))
    }

    @Test fun youtube() {
        assertEquals(TrackLinks.Ref("youtube", "dQw4w9WgXcQ"), ref("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM"))
        assertEquals(TrackLinks.Ref("youtube", "dQw4w9WgXcQ"), ref("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(TrackLinks.Ref("youtube", "dQw4w9WgXcQ"), ref("https://youtu.be/dQw4w9WgXcQ?si=xyz"))
    }

    @Test fun amazon() {
        assertEquals(TrackLinks.Ref("amazon", "B0CXYZ1234"), ref("https://music.amazon.it/tracks/B0CXYZ1234?marketplaceId=APJ6JRA9NG5V4"))
        assertEquals(TrackLinks.Ref("amazon", "B0CXYZ1234"), ref("https://music.amazon.com/albums/B0CABC1234?trackAsin=B0CXYZ1234"))
    }

    @Test fun others() {
        assertEquals(TrackLinks.Ref("deezer", "3135556"), ref("https://www.deezer.com/it/track/3135556"))
        assertEquals(TrackLinks.Ref("tidal", "77646168"), ref("https://tidal.com/browse/track/77646168"))
    }

    @Test fun notALink() {
        assertNull(ref("Daft Punk - Harder Better Faster Stronger"))
        assertNull(ref("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(ref(""))
    }

    @Test fun services() {
        assertTrue(TrackLinks.matches("youtube", "ytm"))
        assertTrue(TrackLinks.matches("spotify", "spotify_bridge"))
        assertTrue(TrackLinks.matches("deezer", "deezer_bridge"))
        // Deezer's ids are the same on both routes: one match cache for the two.
        assertEquals("deezer", com.xlollx.songport.data.FileStore.family("deezer_bridge@2"))
        assertTrue(TrackLinks.matches("amazon", "amazon"))
        assertFalse(TrackLinks.matches("spotify", "apple"))
    }

    @Test fun webUrls() {
        assertEquals("https://open.spotify.com/track/abc", TrackLinks.url("spotify_bridge", "abc"))
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", TrackLinks.url("ytm", "dQw4w9WgXcQ"))
        assertEquals("https://tidal.com/browse/track/12", TrackLinks.url("tidal", "12"))
        assertEquals("https://www.deezer.com/track/3135556", TrackLinks.url("deezer_bridge", "3135556"))
        assertNull(TrackLinks.url("subsonic", "12"))
        // Every address this gives is one parse() reads back to the same id.
        for (s in TrackLinks.SERVICES) assertEquals(s, TrackLinks.parse(TrackLinks.url(s, if (s == "youtube") "dQw4w9WgXcQ" else if (s == "amazon") "B0CXYZ1234" else "123")!!)!!.service)
    }
}
