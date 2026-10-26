package com.xlollx.songport.sync

import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import com.xlollx.songport.model.UnplayableEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnplayableTest {
    private fun t(id: String, title: String, artist: String, album: String = "", ms: Long = 200_000, isrc: String? = null, available: Boolean? = null) =
        Track(id = id, title = title, artists = listOf(artist), album = album, durationMs = ms, isrc = isrc, available = available)

    @Test fun tidalAvailability() {
        assertTrue(Unplayable.tidalStreamable(listOf("STREAM", "DJ"))!!)
        assertFalse(Unplayable.tidalStreamable(listOf("DJ"))!!)
        assertFalse(Unplayable.tidalStreamable(emptyList())!!)
        assertNull(Unplayable.tidalStreamable(null))
    }

    @Test fun ytmDisplayPolicy() {
        assertTrue(Unplayable.ytmAvailable(null))
        assertTrue(Unplayable.ytmAvailable("MUSIC_ITEM_RENDERER_DISPLAY_POLICY_DEFAULT"))
        assertFalse(Unplayable.ytmAvailable("MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"))
    }

    @Test fun rankedSkipsItselfAndOtherDeadOnes() {
        val dead = t("dead", "Throne", "Bring Me The Horizon", "That's the Spirit", 191_000, "GBAHS1500230")
        val same = t("ok", "Throne", "Bring Me The Horizon", "That's the Spirit", 191_000, "GBAHS1500230")
        val alsoDead = t("grey", "Throne", "Bring Me The Horizon", "That's the Spirit", 191_000, "GBAHS1500230", available = false)
        val live = t("live", "Throne - Live at Wembley", "Bring Me The Horizon", "Live at Wembley", 240_000)
        val other = t("x", "Imagine", "John Lennon")
        val r = Unplayable.ranked(dead, listOf(dead, alsoDead, live, other, same))
        assertEquals("ok", r.first().track.id)
        assertTrue(r.none { it.track.id == "dead" || it.track.id == "grey" || it.track.id == "x" })
    }

    @Test fun proposalPrefersTheServicesOwnRelink() {
        val dead = t("dead", "Get Lucky", "Daft Punk")
        val relinked = t("re", "Get Lucky", "Daft Punk")
        val entry = UnplayableEntry(dead, 3, Unplayable.REASON_MARKET, relinked)
        val p = Unplayable.proposal(entry, listOf(t("c", "Get Lucky", "Daft Punk")))
        assertEquals("re", p!!.track.id)
        assertEquals(1.0, p.score, 0.0)
        assertTrue(Unplayable.confident(p))
    }

    @Test fun proposalNullWhenNothingConvincing() {
        val entry = UnplayableEntry(t("dead", "Get Lucky", "Daft Punk"), 0)
        assertNull(Unplayable.proposal(entry, listOf(t("c", "Imagine", "John Lennon"))))
        assertFalse(Unplayable.confident(null, MatchPolicy()))
    }

    @Test fun orderAfterPutsReplacementsWhereTheDeadEntriesWere() {
        val a = t("a", "A", "x"); val b = t("b", "B", "x"); val c = t("c", "C", "x"); val d = t("d", "D", "x")
        val deadB = UnplayableEntry(t("b0", "B", "x"), 1)
        val deadD = UnplayableEntry(t("d0", "D", "x"), 3)
        // After adding b and d at the end and removing b0 and d0, the service lists: a c b d.
        val current = listOf(a, c, b, d)
        val ordered = Unplayable.orderAfter(current, listOf(deadD to d, deadB to b))
        assertEquals(listOf("a", "b", "c", "d"), ordered.map { it.id })
        // A replacement the listing does not hold is ignored.
        assertEquals(listOf("a", "c"), Unplayable.orderAfter(listOf(a, c), listOf(deadB to b)).map { it.id })
    }
}
