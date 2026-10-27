package com.xlollx.songport.contract

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.SoundCloudProvider
import com.xlollx.songport.sync.SoundCloudPublic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * SoundCloud read through the web player's interface (api-v2, not an official API): shapes as the
 * player receives them, kept to what the existing parser tests already pin.
 */
class SoundCloudContractTest : ContractTest() {
    private val sc = SoundCloudProvider
    private val cid = "a".repeat(16) + "B".repeat(16)
    private val cid2 = "C".repeat(32)

    @Before fun forgetClientId() {
        SoundCloudPublic::class.java.getDeclaredField("clientId").apply { isAccessible = true }.set(null, null)
    }

    private fun home() = text("""<html><script crossorigin src="https://a-v2.sndcdn.com/assets/0-aa.js"></script>
        <script crossorigin src="https://a-v2.sndcdn.com/assets/55-bb.js"></script></html>""", 200)
    private fun script(id: String) = text("""!function(){var e={env:"production",client_id:"$id",x:1}}();""", 200, "application/javascript")

    private fun full(i: Int, title: String = "Song $i") = """{"kind":"track","id":$i,"title":${q(title)},"duration":30000,"full_duration":${200000 + i},
        "permalink_url":"https://soundcloud.com/label/song-$i","policy":"ALLOW","user":{"username":"Label"},"publisher_metadata":{"artist":"Artist $i","isrc":"GBABC21000${i % 10}"}}"""
    private fun stub(i: Int) = """{"id":$i,"kind":"track","monetization_model":"NOT_APPLICABLE","policy":"ALLOW"}"""

    @Test fun setWithStubsFetchedInFiftiesKeepsOrder() = runBlocking {
        home(); script(cid)
        val listed = (1..5).map { full(it) } + (6..125).map { stub(it) } + listOf(full(1))
        json("""{"kind":"playlist","id":9,"title":${q(UNICODE)},"track_count":126,"sharing":"public","last_modified":"2024-01-01T00:00:00Z","tracks":[${listed.joinToString(",")}]}""")
        // The /tracks answer is in any order and leaves out a track that went private.
        json("[${(6..55).reversed().joinToString(",") { full(it) }}]")
        json("[${(56..105).filter { it != 60 }.joinToString(",") { full(it) }}]")
        json("[${(106..125).joinToString(",") { full(it) }}]")
        val ts = sc.tracks(env, "label/sets/mix")
        assertEquals((1..125).filter { it != 60 }.map { "$it" } + "1", ts.map { it.id })
        assertCall(next(), "GET", "soundcloud.com", "/")
        assertEquals("/assets/55-bb.js", next().pathOnly())
        val res = next()
        assertCall(res, "GET", "api-v2.soundcloud.com", "/resolve")
        assertEquals("https://soundcloud.com/label/sets/mix", res.query("url"))
        assertEquals(cid, res.query("client_id"))
        val batches = (0 until 3).map { val r = next(); assertEquals("/tracks", r.pathOnly()); assertEquals(cid, r.query("client_id")); r.query("ids")!!.split(',').size }
        assertEquals(listOf(50, 50, 20), batches)
        noMoreRequests()
    }

    @Test fun refusedClientIdIsFetchedAgainOnce() = runBlocking {
        home(); script(cid)
        json("{}", 401)
        home(); script(cid2)
        json("""{"kind":"playlist","title":"T","tracks":[${full(1)}]}""")
        assertEquals(1, sc.tracks(env, "u/sets/s").size)
        repeat(2) { next() }
        assertEquals(cid, next().query("client_id"))
        repeat(2) { next() }
        assertEquals(cid2, next().query("client_id"))
    }

    @Test fun refusedTwiceMeansThePlayerChanged() = runBlocking {
        home(); script(cid); json("{}", 403); home(); script(cid2); json("{}", 403)
        try { sc.tracks(env, "u/sets/s"); fail() } catch (e: ProviderException) { assertEquals("soundcloud_player_changed", e.message) }
    }

    @Test fun missingSetIsNotPublic() = runBlocking {
        home(); script(cid); json("""{"code":404}""", 404)
        try { sc.tracks(env, "u/sets/gone"); fail() } catch (e: ProviderException) { assertEquals(404, e.code); assertEquals("soundcloud_not_public", e.message) }
    }

    @Test fun likesFollowNextHref() = runBlocking {
        home(); script(cid)
        json("""{"kind":"user","id":77,"username":"dj","likes_count":3}""")
        json("""{"collection":[{"created_at":"2024-01-02T00:00:00Z","kind":"like","track":${full(1)}},{"created_at":"2024-01-01T00:00:00Z","kind":"like","playlist":{"id":5,"kind":"playlist"}}],
            "next_href":"https://api-v2.soundcloud.com/users/77/likes?offset=2024-01-01T00%3A00%3A00.000Z%2C1&limit=200&linked_partitioning=1"}""")
        json("""{"collection":[{"created_at":"2023-12-31T00:00:00Z","kind":"like","track":${full(2, title = LONG)}},{"created_at":"2023-12-30T00:00:00Z","kind":"like","track":${full(1)}}],"next_href":null}""")
        val ts = sc.tracks(env, "dj/likes")
        assertEquals(listOf("1", "2"), ts.map { it.id })
        assertEquals(LONG, ts[1].title)
        repeat(3) { next() }
        val p1 = next()
        assertEquals("/users/77/likes", p1.pathOnly()); assertEquals("200", p1.query("limit"))
        val p2 = next()
        assertEquals("2024-01-01T00:00:00.000Z,1", p2.query("offset"))
        assertEquals(listOf(cid), p2.queryAll("client_id"))
    }

    /**
     * Regression: a next_href that starts with the old client id ("?client_id=...&offset=...") lost
     * its "?" when the id was cut out, and the next page went to a path that does not exist.
     */
    @Test fun nextHrefStartingWithTheClientIdKeepsItsQuery() = runBlocking {
        home(); script(cid)
        json("""{"kind":"user","id":77,"username":"dj"}""")
        json("""{"collection":[{"created_at":"2024-01-02T00:00:00Z","kind":"like","track":${full(1)}}],
            "next_href":"https://api-v2.soundcloud.com/users/77/likes?client_id=$cid&offset=abc&limit=200"}""")
        json("""{"collection":[{"created_at":"2024-01-01T00:00:00Z","kind":"like","track":${full(2)}}],"next_href":null}""")
        assertEquals(listOf("1", "2"), sc.tracks(env, "dj/likes").map { it.id })
        repeat(4) { next() }
        val p2 = next()
        assertEquals("/users/77/likes", p2.pathOnly())
        assertEquals("abc", p2.query("offset"))
        assertEquals(listOf(cid), p2.queryAll("client_id"))
    }

    @Test fun readOnlyWithoutARequest() = runBlocking {
        try { sc.addTracks(env, "u/sets/s", listOf(Track("1", "a"))); fail() } catch (e: ProviderException) { assertTrue(e.message!!.startsWith("error_read_only")) }
        noMoreRequests()
    }
}
