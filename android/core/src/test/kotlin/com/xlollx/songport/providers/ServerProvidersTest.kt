package com.xlollx.songport.providers

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the self-hosted connectors read from their servers' answers, without a server. */
class ServerProvidersTest {
    private fun t(title: String, artist: String) = Track(id = "$artist/$title", title = title, artists = listOf(artist))

    @Test fun subsonicReadsTheOpenSubsonicIsrcList() {
        val song = parseJson("""{"id":"s1","title":"Battery","artist":"Metallica","duration":312,"isrc":["USEE10001001","USEE10001099"]}""")
        assertEquals("USEE10001001", SubsonicProvider().toTrack(song)!!.isrc)
    }

    @Test fun subsonicReadsAPlainIsrcOrNone() {
        assertEquals("USEE10001001", SubsonicProvider().toTrack(parseJson("""{"id":"s1","title":"Battery","isrc":"USEE10001001"}"""))!!.isrc)
        assertNull(SubsonicProvider().toTrack(parseJson("""{"id":"s1","title":"Battery"}"""))!!.isrc)
        assertNull(SubsonicProvider().toTrack(parseJson("""{"id":"s1","title":"Battery","isrc":[]}"""))!!.isrc)
    }

    @Test fun jellyfinPutsTheSearchedArtistFirst() {
        val found = listOf(t("Home", "Måneskin"), t("Home", "Beyoncé"), t("Home (Live)", "Beyonce"), t("Home", "Other"))
        assertEquals(
            listOf("Beyoncé/Home", "Beyonce/Home (Live)", "Måneskin/Home", "Other/Home"),
            JellyfinProvider.byArtist(t("Home", "Beyoncé"), found).map { it.id },
        )
    }

    @Test fun jellyfinKeepsTheServerOrderWithoutAnArtist() {
        val found = listOf(t("Home", "Måneskin"), t("Home", "Beyoncé"))
        assertEquals(found, JellyfinProvider.byArtist(Track(id = "q", title = "Home"), found))
    }

    private fun e(item: String, entry: String) = Track(id = item, title = item, itemId = entry)

    @Test fun jellyfinDeletesEntriesWhenNoCopyHasToStay() {
        val list = listOf(e("a", "1"), e("b", "2"), e("c", "3"), e("b", "2"))
        assertNull(JellyfinProvider.keptAfterRemoving(list, listOf("3")))
        assertNull(JellyfinProvider.keptAfterRemoving(list, listOf("2", "2")))
    }

    @Test fun jellyfinRewritesTheListWhenACopyStays() {
        // Jellyfin 10.9+: both copies of b share entry id 2; removing one must keep the other.
        val list = listOf(e("a", "1"), e("b", "2"), e("c", "3"), e("b", "2"))
        assertEquals(listOf("a", "b"), JellyfinProvider.keptAfterRemoving(list, listOf("2", "3"))!!.map { it.id })
        assertEquals(listOf("a", "b", "c"), JellyfinProvider.keptAfterRemoving(list, listOf("2"))!!.map { it.id })
    }
}
