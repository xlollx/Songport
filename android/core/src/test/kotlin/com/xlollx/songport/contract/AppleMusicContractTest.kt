package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Apple Music API (developer.apple.com/documentation/applemusicapi), shapes per the reference. */
class AppleMusicContractTest : ContractTest() {
    private val am = AppleMusicProvider()
    private val api = "api.music.apple.com"

    @Before fun signIn() {
        BuildDefaults.values = mapOf("APPLE_DEVELOPER_TOKEN" to "DEVJWT")
        env.tokens.set("apple", Tokens("MUT", userName = "Apple Music", extra = mapOf("storefront" to "it")))
    }

    private fun lib(i: Int, catalog: Boolean = true, playable: Boolean = true, name: String = "Song $i") =
        """{"id":"i.lib$i","type":"library-songs","href":"/v1/me/library/songs/i.lib$i","attributes":{"name":${q(name)},"artistName":"Daft Punk & Pharrell Williams",
           "albumName":"Random Access Memories","durationInMillis":${240000 + i},"releaseDate":"2013-05-17","dateAdded":"2024-02-02T10:00:00Z",
           "genreNames":["Electronic"],"trackNumber":8,"hasLyrics":true
           ${if (playable) ",\"playParams\":{\"id\":\"i.lib$i\",\"kind\":\"song\",\"isLibrary\":true,\"reporting\":true" + (if (catalog) ",\"catalogId\":\"${617000000 + i}\"" else "") + "}" else ""}}}"""

    @Test fun libraryPlaylistTracksFollowRelativeNext() = runBlocking {
        json("""{"next":"/v1/me/library/playlists/p.X/tracks?offset=100","data":[${(0 until 100).joinToString(",") { lib(it) }}],"meta":{"total":102}}""")
        json("""{"data":[${lib(100, catalog = false)},${lib(101, playable = false)},${lib(0)}],"meta":{"total":103}}""")
        val ts = am.tracks(env, "p.X")
        assertEquals(103, ts.size)
        assertEquals("617000000", ts[0].id)
        assertEquals("i.lib0", ts[0].itemId)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), ts[0].artists)
        assertEquals("i.lib100", ts[100].id) // no catalogue id: the library one
        assertEquals(false, ts[101].available)
        assertEquals("617000000", ts[102].id) // duplicate kept
        val r = next()
        assertCall(r, "GET", api, "/v1/me/library/playlists/p.X/tracks")
        assertEquals("Bearer DEVJWT", r.getHeader("Authorization"))
        assertEquals("MUT", r.getHeader("Music-User-Token"))
        assertEquals("100", next().query("offset"))
        noMoreRequests()
    }

    @Test fun catalogPlaylistNeedsNoUserToken() = runBlocking {
        env.tokens.clear("apple")
        json("""{"data":[{"id":"1440818839","type":"songs","attributes":{"name":${q(UNICODE)},"artistName":"Måneskin","albumName":"","durationInMillis":1,
            "isrc":"ITB001700001","contentRating":"explicit","releaseDate":"2017"}}]}""")
        val ts = am.tracks(env, "pl.u-abc")
        assertEquals(UNICODE, ts[0].title)
        assertEquals(true, ts[0].explicit)
        val r = next()
        assertTrue(r.pathOnly().startsWith("/v1/catalog/"))
        assertTrue(r.pathOnly().endsWith("/playlists/pl.u-abc/tracks"))
        assertNull(r.getHeader("Music-User-Token"))
    }

    @Test fun playlistsWithCanEdit() = runBlocking {
        json("""{"data":[{"id":"p.A","type":"library-playlists","attributes":{"name":${q(UNICODE)},"canEdit":true,"isPublic":false,"playParams":{"id":"p.A"}}},
            {"id":"p.B","type":"library-playlists","attributes":{"name":"Apple's","canEdit":false}}],"meta":{"total":2}}""")
        val ls = am.playlists(env)
        assertEquals(listOf(true, false), ls.map { it.ownedByMe })
        assertEquals("100", next().query("limit"))
    }

    @Test fun addInTwentyFivesInOrder() = runBlocking {
        repeat(3) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204)) }
        val tracks = (0 until 60).map { Track("${1440000000 + it}", "S") }
        am.addTracks(env, "p.X", tracks)
        val sent = (0 until 3).flatMap {
            val r = next()
            assertCall(r, "POST", api, "/v1/me/library/playlists/p.X/tracks")
            r.jsonBody()["data"].arr.map { d -> assertEquals("songs", d["type"].str); d["id"].str!! }
        }
        assertEquals(tracks.map { it.id }, sent)
    }

    @Test fun createWithUnicode() = runBlocking {
        json("""{"data":[{"id":"p.NEW","type":"library-playlists","attributes":{"name":${q(UNICODE)}}}]}""", 201)
        assertEquals("p.NEW", am.createPlaylist(env, UNICODE, LONG).id)
        val r = next()
        assertCall(r, "POST", api, "/v1/me/library/playlists")
        assertEquals(UNICODE, r.jsonBody()["attributes"]["name"].str)
        assertEquals(LONG, r.jsonBody()["attributes"]["description"].str)
    }

    @Test fun removeRenameDeleteAreRefusedWithoutARequest() = runBlocking {
        for (call in listOf<suspend () -> Unit>(
            { am.removeTracks(env, "p.X", listOf(Track("1", "a"))) },
            { am.renamePlaylist(env, "p.X", "n") },
            { am.deletePlaylist(env, "p.X") },
            { am.setPlaylistVisibility(env, "p.X", true) },
        )) try { call(); fail() } catch (e: ProviderException) { }
        noMoreRequests()
    }

    @Test fun userTokenRefusedClearsTheSession() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
        try { am.playlists(env); fail() } catch (e: ProviderException) { assertEquals("Apple Music API 401: no detail", e.message) }
        assertNull(env.tokens.get("apple"))
    }

    @Test fun noSubscriptionIsSaidPlainly() = runBlocking {
        json("""{"errors":[{"id":"X","title":"Forbidden","detail":"Forbidden","status":"403","code":"40300"}]}""", 403)
        try { am.playlists(env); fail() } catch (e: ProviderException) { assertEquals("apple_subscription_needed(Apple Music,Forbidden)", e.message) }
    }

    /** Regression: a non-JSON error page ends as a clear Apple Music error, not a JSON parser crash. */
    @Test fun nonJsonErrorBodyIsAClearError() = runBlocking {
        text("<html><head><title>500 Internal Server Error</title></head></html>", 500)
        try { am.playlists(env); fail() } catch (e: ProviderException) { assertTrue(e.message, e.message!!.startsWith("Apple Music API 500: <html>")) }
    }

    @Test fun searchByIsrcFilter() = runBlocking {
        json("""{"data":[{"id":"1","type":"songs","attributes":{"name":"A","artistName":"B","isrc":"USUM71703861"}}]}""")
        assertEquals(listOf("1"), am.search(env, Track("x", "A", listOf("B"), isrc = "usum71703861")).map { it.id })
        val r = next()
        assertCall(r, "GET", api, "/v1/catalog/it/songs")
        assertEquals("USUM71703861", r.query("filter[isrc]"))
    }

    @Test fun savedAlbumsByCatalogId() = runBlocking {
        json("""{"data":[{"id":"l.A","type":"library-albums","attributes":{"name":"Discovery","artistName":"Daft Punk","playParams":{"id":"l.A","catalogId":"697194953"}}},
            {"id":"l.B","type":"library-albums","attributes":{"name":"Home","artistName":"X"}}]}""")
        assertEquals(listOf("697194953", "l.B"), am.tracks(env, MusicProvider.ALBUMS_ID).map { it.id })
    }
}
