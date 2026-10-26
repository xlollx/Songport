package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPromptTest {
    private fun t(artist: String, title: String = "x") = Track(id = "$artist$title", title = title, artists = listOf(artist), album = "", durationMs = 0)

    @Test
    fun topArtistsByFrequencyThenFirstSeen() {
        val tracks = listOf(t("Metallica"), t("Avenged Sevenfold"), t("avenged sevenfold"), t("Linkin Park"), t("Metallica"), t("Avenged Sevenfold"), t("Dope"))
        assertEquals(listOf("Avenged Sevenfold", "Metallica", "Linkin Park", "Dope"), CoverPrompt.topArtists(tracks, 5))
        assertEquals(listOf("Avenged Sevenfold", "Metallica"), CoverPrompt.topArtists(tracks, 2))
    }

    @Test
    fun promptCarriesTitleArtistsDescriptionAndTheRules() {
        val p = CoverPrompt.build("Metal & More", listOf("Metallica", "Avenged Sevenfold"), "  late night\n drive  ")
        assertTrue(p.contains("\"Metal & More\""))
        assertTrue(p.contains("Metallica, Avenged Sevenfold"))
        assertTrue(p.contains("Mood and theme: late night drive."))
        assertTrue(p.contains("No text"))
        assertTrue(p.contains("no real people's faces"))
        assertTrue(p.startsWith("Square album cover art"))
    }

    @Test
    fun noDescriptionNoArtistsStillValid() {
        val p = CoverPrompt.build("Chill", emptyList())
        assertFalse(p.contains("sounds like"))
        assertFalse(p.contains("Mood and theme"))
    }
}
