package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/** Which tracks of a playlist can stand for it on a card: one per album, so the mosaic is not four times the same cover. */
object CoverPicks {
    /**
     * The album a track comes from, as a key: title normalised the way the matcher does it
     * ("Hail to the King (Deluxe)" and "Hail To The King" are one album) plus the first artist.
     * A track without an album is its own key.
     */
    fun albumKey(t: Track): String {
        val album = Matcher.normalizeTitle(t.album)
        if (album.isBlank()) return "id:" + t.id
        val artist = t.artists.firstOrNull()?.let { Matcher.normalizeArtist(it) }.orEmpty()
        return "$album|$artist"
    }
}
