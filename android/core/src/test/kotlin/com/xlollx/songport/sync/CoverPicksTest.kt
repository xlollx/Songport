package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverPicksTest {
    private fun t(id: String, album: String, artist: String = "Avenged Sevenfold") =
        Track(id = id, title = "Song $id", artists = listOf(artist), album = album, durationMs = 200_000)

    @Test
    fun editionsOfOneAlbumShareAKey() {
        assertEquals(CoverPicks.albumKey(t("1", "Hail to the King")), CoverPicks.albumKey(t("2", "Hail To The King (Deluxe)")))
    }

    @Test
    fun differentAlbumsAndArtistsDiffer() {
        assertNotEquals(CoverPicks.albumKey(t("1", "Hail to the King")), CoverPicks.albumKey(t("2", "Nightmare")))
        assertNotEquals(CoverPicks.albumKey(t("1", "Greatest Hits", "A")), CoverPicks.albumKey(t("2", "Greatest Hits", "B")))
    }

    @Test
    fun aTrackWithoutAlbumStandsAlone() {
        assertNotEquals(CoverPicks.albumKey(t("1", "")), CoverPicks.albumKey(t("2", "")))
    }

    @Test
    fun aPlaylistOfOneAlbumThenOthersKeepsOrder() {
        val list = listOf(t("1", "Hail to the King"), t("2", "Master of Puppets", "Metallica"), t("3", "Hail to the King"), t("4", "Hail to the King"), t("5", "Nightmare"))
        assertEquals(listOf("1", "2", "5"), list.distinctBy { CoverPicks.albumKey(it) }.map { it.id })
    }
}
