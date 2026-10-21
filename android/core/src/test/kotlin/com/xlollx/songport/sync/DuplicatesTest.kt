package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicatesTest {
    private fun t(id: String, title: String, artist: String, isrc: String? = null) =
        Track(id = id, title = title, artists = listOf(artist), isrc = isrc)

    @Test fun groupsByIdIsrcAndText() {
        val list = listOf(
            t("1", "Get Lucky", "Daft Punk", "GBTDF1300003"),
            t("2", "Take It Easy", "Eagles"),
            t("1", "Get Lucky", "Daft Punk", "GBTDF1300003"),          // stesso id
            t("3", "Get Lucky (Radio Edit)", "Daft Punk", "GBTDF1300003"), // stesso ISRC
            t("4", "take it easy", "The Eagles"),                       // stesso testo normalizzato
            t("5", "Imagine", "John Lennon"),
        )
        val groups = Duplicates.groups(list)
        assertEquals(2, groups.size)
        assertEquals(listOf("1", "1", "3"), groups[0].map { it.id })
        assertEquals(listOf("2", "4"), groups[1].map { it.id })
        assertEquals(listOf("1", "3", "4"), Duplicates.extras(list).map { it.id })
    }

    @Test fun noDuplicates() {
        assertEquals(0, Duplicates.groups(listOf(t("1", "A", "x"), t("2", "B", "y"))).size)
    }
}
