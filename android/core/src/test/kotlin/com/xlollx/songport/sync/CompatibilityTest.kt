package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {
    private fun t(id: String, title: String, artist: String, isrc: String? = null, ms: Long = 200_000) =
        Track(id = id, title = title, artists = listOf(artist), durationMs = ms, isrc = isrc)

    @Test fun identicalPlaylistsScoreFull() {
        val a = listOf(t("1", "Master of Puppets", "Metallica"), t("2", "Hail to the King", "Avenged Sevenfold"))
        val b = listOf(t("x", "Master Of Puppets (Remastered)", "Metallica"), t("y", "Hail to the King", "Avenged Sevenfold"))
        val r = Compatibility.compare(a, b)
        assertEquals(2, r.common.size)
        assertEquals(100, r.commonPercent)
        assertEquals(100, r.score)
        assertEquals(setOf("Metallica", "Avenged Sevenfold"), r.sharedArtists.toSet())
        assertTrue(r.forA.isEmpty() && r.forB.isEmpty())
    }

    @Test fun nothingInCommonScoresZero() {
        val r = Compatibility.compare(listOf(t("1", "Blue", "Eiffel 65")), listOf(t("2", "Enter Sandman", "Metallica")))
        assertEquals(0, r.common.size)
        assertEquals(0, r.score)
        assertTrue(r.sharedArtists.isEmpty())
    }

    @Test fun isrcMatchesAcrossSpellingsAndSuggestionsFollowSharedArtists() {
        val a = listOf(
            t("1", "Song One", "Artist A", isrc = "USABC1234567"),
            t("2", "Only Here", "Artist A"),
            t("3", "Lonely", "Artist C"),
        )
        val b = listOf(
            t("9", "Canzone Uno", "Artist A", isrc = "USABC1234567"),
            t("8", "Deep Cut", "Artist A"),
            t("7", "Far Away", "Artist B"),
        )
        val r = Compatibility.compare(a, b)
        assertEquals(1, r.common.size)
        assertEquals("1", r.common[0].a.id); assertEquals("9", r.common[0].b.id)
        assertEquals(listOf("Artist A"), r.sharedArtists)
        // What A would like from B: B's other Artist A track; and the other way round.
        assertEquals(listOf("8"), r.forA.map { it.id })
        assertEquals(listOf("2"), r.forB.map { it.id })
        // 1 of 3 tracks (70% × 1/3) + 1 of 2 artists (30% × 1/2) = 38.
        assertEquals(38, r.score)
        assertEquals(33, r.commonPercent)
    }

    @Test fun smallerPlaylistIsTheBase() {
        val a = listOf(t("1", "Alpha", "X"), t("2", "Beta", "Y"))
        val b = (1..50).map { t("b$it", "Other $it", "Z$it") } + a.map { it.copy(id = "copy" + it.id) }
        val r = Compatibility.compare(a, b)
        assertEquals(2, r.common.size)
        assertEquals(100, r.commonPercent)
        assertEquals(100, r.score)
    }
}
