package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistLinksTest {
    private fun ref(url: String) = PlaylistLinks.parse(url)

    @Test fun spotify() {
        assertEquals(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            ref("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            ref("https://open.spotify.com/intl-it/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            ref("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test fun appleMusic() {
        assertEquals(PlaylistLinks.Ref("apple", "pl.u-8aAVZjvtLLlN2o"),
            ref("https://music.apple.com/it/playlist/le-mie-canzoni/pl.u-8aAVZjvtLLlN2o"))
        assertEquals(PlaylistLinks.Ref("apple", "pl.f4d106fed2bd41149aaacabb233eb5eb"),
            ref("https://music.apple.com/us/playlist/pl.f4d106fed2bd41149aaacabb233eb5eb"))
    }

    @Test fun youtube() {
        assertEquals(PlaylistLinks.Ref("youtube", "PLabc-123_x"),
            ref("https://www.youtube.com/playlist?list=PLabc-123_x"))
        assertEquals(PlaylistLinks.Ref("youtube", "PLabc-123_x"),
            ref("https://music.youtube.com/playlist?list=PLabc-123_x&si=zz"))
    }

    @Test fun deezerAndTidal() {
        assertEquals(PlaylistLinks.Ref("deezer", "1234567"), ref("https://www.deezer.com/it/playlist/1234567"))
        assertEquals(PlaylistLinks.Ref("deezer", "1234567"), ref("https://deezer.com/playlist/1234567"))
        assertEquals(PlaylistLinks.Ref("tidal", "12345678-1234-1234-1234-123456789abc"),
            ref("https://tidal.com/browse/playlist/12345678-1234-1234-1234-123456789abc"))
    }

    @Test fun rejectsJunk() {
        assertNull(ref(""))
        assertNull(ref("ciao"))
        assertNull(ref("https://example.com/playlist/1"))
        assertNull(ref("https://open.spotify.com/album/1A2B3C"))
    }
}
