package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

class PlaylistOpsTest {

    private fun t(id: String, title: String, artist: String, album: String = "") = Track(id, title, listOf(artist), album)

    @Test fun mergeKeepsOrderAndDropsDuplicates() {
        val a = listOf(t("1", "Get Lucky", "Daft Punk"), t("2", "Around the World", "Daft Punk"))
        val b = listOf(t("1", "Get Lucky", "Daft Punk"), t("3", "One More Time", "Daft Punk"))
        assertEquals(listOf("1", "2", "1", "3"), PlaylistOps.merge(listOf(a, b), dropDuplicates = false).map { it.id })
        assertEquals(listOf("1", "2", "3"), PlaylistOps.merge(listOf(a, b), dropDuplicates = true).map { it.id })
    }

    @Test fun splitsInParts() {
        val list = (1..7).map { t("$it", "T$it", "A") }
        val parts = PlaylistOps.split(list, 3)
        assertEquals(listOf(3, 3, 1), parts.map { it.size })
        assertEquals("7", parts.last().single().id)
        assertEquals(7, PlaylistOps.split(list, 0).size) // size below 1 is clamped, not a crash
    }

    @Test fun sortsByArtistThenAlbumThenTitle() {
        val list = listOf(
            t("1", "Zebra", "Beatles", "Abbey Road"),
            t("2", "Anthem", "beatles", "Abbey Road"),
            t("3", "Song", "Abba", "Gold"),
            t("4", "Émile", "Beatles", "1"),
        )
        val sorted = PlaylistOps.sort(list, PlaylistOps.SortKey.ARTIST, Locale.ENGLISH)
        assertEquals(listOf("3", "4", "2", "1"), sorted.map { it.id })
        assertEquals(listOf("2", "4", "3", "1"), PlaylistOps.sort(list, PlaylistOps.SortKey.TITLE, Locale.ENGLISH).map { it.id })
        assertEquals(listOf("4", "3", "2", "1"), PlaylistOps.sort(list, PlaylistOps.SortKey.REVERSE).map { it.id })
    }

    @Test fun shuffleKeepsEveryTrackAndSpreadsArtists() {
        val list = (1..6).map { t("a$it", "T$it", "Same") } + (1..6).map { t("b$it", "T$it", "Other") }
        val out = PlaylistOps.shuffle(list, Random(7))
        assertEquals(list.toSet(), out.toSet())
        assertEquals(list.size, out.size)
        // Two artists, six tracks each: a spread shuffle alternates them without a single repeat.
        assertTrue(out.zipWithNext().none { (x, y) -> x.artists == y.artists })
    }

    @Test fun sortsByYearAddedAndDurationWithUnknownsLast() {
        val list = listOf(
            Track("1", "A", listOf("X"), year = 2010, addedAt = 300, durationMs = 200_000),
            Track("2", "B", listOf("X"), year = 0, addedAt = 100, durationMs = 0),
            Track("3", "C", listOf("X"), year = 1999, addedAt = 0, durationMs = 100_000),
        )
        assertEquals(listOf("3", "1", "2"), PlaylistOps.sort(list, PlaylistOps.SortKey.YEAR).map { it.id })
        assertEquals(listOf("2", "1", "3"), PlaylistOps.sort(list, PlaylistOps.SortKey.ADDED).map { it.id })
        assertEquals(listOf("3", "1", "2"), PlaylistOps.sort(list, PlaylistOps.SortKey.DURATION).map { it.id })
    }
}
