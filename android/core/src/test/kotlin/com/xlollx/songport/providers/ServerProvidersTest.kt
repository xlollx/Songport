package com.xlollx.songport.providers

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.it.TestEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder

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
            Matcher.byArtist(t("Home", "Beyoncé"), found).map { it.id },
        )
    }

    @Test fun jellyfinKeepsTheServerOrderWithoutAnArtist() {
        val found = listOf(t("Home", "Måneskin"), t("Home", "Beyoncé"))
        assertEquals(found, Matcher.byArtist(Track(id = "q", title = "Home"), found))
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

/**
 * The connectors against a stand-in server: a local HTTP server answering as the real ones did in
 * LiveServersIT (Airsonic-Advanced, Gonic, Plex, Jellyfin 10.8), so the fixes hold without docker.
 */
class FakeServerProvidersTest {
    private class Req(val method: String, val path: String, val query: Map<String, List<String>>, val body: String)

    private val requests = mutableListOf<Req>()
    private var answer: (Req) -> Pair<Int, String> = { 404 to "" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val q = (ex.requestURI.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map { kv ->
                URLDecoder.decode(kv.substringBefore('='), "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            }.groupBy({ it.first }, { it.second })
            val req = Req(ex.requestMethod, ex.requestURI.path, q, ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val (code, body) = synchronized(requests) { requests += req; answer(req) }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val ctx = TestEnv()

    @After fun stop() = server.stop(0)

    // ------------------------------------------------------------------ Subsonic

    private fun subsonic(version: String, extra: String = "") = """{"subsonic-response":{"status":"ok","version":"$version"$extra}}"""

    @Test fun subsonicSpeaksTheVersionOfAnOlderServer() = runBlocking {
        // Airsonic-Advanced 11 (API 1.15.0) refuses 1.16.1 with error 30, and names its own version.
        answer = { r ->
            if (r.query["v"]?.single() == "1.15.0") 200 to subsonic("1.15.0", ""","playlists":{"playlist":[{"id":1,"name":"Mix","songCount":2,"owner":"admin"}]}""")
            else 200 to """{"subsonic-response":{"status":"failed","version":"1.15.0","error":{"code":30,"message":"Incompatible Airsonic REST protocol version. Server must upgrade."}}}"""
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        assertEquals(listOf("Mix"), p.playlists(ctx).map { it.name })
        // Remembered: after the login, every call goes out with 1.15.0 at once.
        assertEquals(listOf("1.16.1", "1.15.0", "1.15.0"), requests.map { it.query["v"]?.single() })
        assertEquals(listOf(null, null, null), requests.map { it.query["p"] })
    }

    @Test fun subsonicRemovesTheFirstEntryAsAmpacheReadsIt() = runBlocking {
        answer = { 200 to subsonic("1.16.1") }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        p.removeTracks(ctx, "pl", listOf(Track(id = "a", title = "A", itemId = "0"), Track(id = "b", title = "B", itemId = "4")))
        val update = requests.single { it.path.endsWith("updatePlaylist") }
        // Ampache ignores songIndexToRemove=0; 00 is the same index for every server.
        assertEquals(listOf("00", "4"), update.query["songIndexToRemove"])
    }

    @Test fun subsonicStillFailsOnOtherErrors() = runBlocking {
        answer = { 200 to """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password."}}}""" }
        val e = runCatching { SubsonicProvider().login(ctx, url, "admin", "bad") }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("Wrong username") == true)
        assertEquals(1, requests.size)
    }

    @Test fun subsonicExplainsAmpachesSubsonicPassword() = runBlocking {
        // Ampache 8.3, the account password as token: error 40, and the answer says it is Ampache.
        answer = { 200 to """{"subsonic-response":{"status":"failed","version":"1.16.1","type":"ampache","serverVersion":"8.3.0","openSubsonic":true,"error":{"code":40,"message":"Wrong username or password.","helpUrl":"https://ampache.org/api/subsonic"}}}""" }
        val e = runCatching { SubsonicProvider().login(ctx, url, "it", "account-password") }.exceptionOrNull()
        assertEquals("error_ampache_subsonic_password", e?.message)
        assertEquals(1, requests.size)
    }

    @Test fun subsonicSearchesTheTitleAloneWhenArtistAndTitleFindNothing() = runBlocking {
        // Gonic matches the query against titles: "metallica battery" finds nothing, "home" both Homes.
        answer = { r ->
            when (r.path.substringAfterLast('/')) {
                "search3" -> 200 to subsonic("1.16.1", if (r.query["query"]?.single() == "home") {
                    ""","searchResult3":{"song":[{"id":"m","title":"Home","artist":"Måneskin"},{"id":"b","title":"Home","artist":"Beyoncé"}]}"""
                } else ""","searchResult3":{}""")
                else -> 200 to subsonic("1.16.1")
            }
        }
        val p = SubsonicProvider()
        p.login(ctx, url, "admin", "pw")
        val found = p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé")))
        assertEquals(listOf("b", "m"), found.map { it.id })
        assertEquals(listOf("beyonce home", "home"), requests.filter { it.path.endsWith("search3") }.map { it.query["query"]?.single() })
    }

    // ------------------------------------------------------------------ Plex

    @Test fun plexSearchesByTitleAndPutsTheArtistFirst() = runBlocking {
        answer = { r ->
            when (r.path) {
                "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                "/hubs/search" -> 200 to if (r.query["query"]?.single() == "home") {
                    """{"MediaContainer":{"Hub":[{"type":"artist","Metadata":[{"ratingKey":"1","title":"Home"}]},{"type":"track","Metadata":[
                        {"ratingKey":"5","title":"Home","grandparentTitle":"Måneskin","duration":8046},
                        {"ratingKey":"7","title":"Home","grandparentTitle":"Beyoncé","duration":8000}]}]}}"""
                } else """{"MediaContainer":{"Hub":[{"type":"track"}]}}"""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        assertEquals(listOf("7", "5"), p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé"))).map { it.id })
        assertEquals(listOf("home"), requests.filter { it.path == "/hubs/search" }.map { it.query["query"]?.single() })
    }

    @Test fun plexCreatesAnEmptyPlaylistOnTheLibrary() = runBlocking {
        // Plex 1.43: POST /playlists with the library's uri and no item makes an empty audio playlist.
        answer = { r ->
            when {
                r.path == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                r.path == "/playlists" && r.method == "POST" ->
                    200 to """{"MediaContainer":{"size":1,"Metadata":[{"ratingKey":"21","type":"playlist","title":"${r.query["title"]?.single()}","smart":false,"playlistType":"audio","leafCount":0}]}}"""
                r.path == "/playlists/21" && r.method == "PUT" -> 200 to ""
                else -> 404 to ""
            }
        }
        val p = PlexProvider()
        assertTrue(p.canCreatePlaylists && p.canRenamePlaylists && p.canDeletePlaylists)
        p.login(ctx, url, "", "token")
        val pl = p.createPlaylist(ctx, "Café & Co", "Made with Songport")
        assertEquals("21" to "Café & Co", pl.id to pl.name)
        val post = requests.single { it.method == "POST" }
        assertEquals(listOf("audio"), post.query["type"])
        assertEquals(listOf("0"), post.query["smart"])
        assertEquals(listOf("Café & Co"), post.query["title"])
        assertEquals(listOf("server://abc/com.plexapp.plugins.library"), post.query["uri"])
        assertEquals(listOf("Made with Songport"), requests.single { it.method == "PUT" }.query["summary"])
    }

    @Test fun plexCreatesThePlaylistEvenWhenTheSummaryIsRefused() = runBlocking {
        answer = { r ->
            when {
                r.path == "/identity" -> 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}"""
                r.path == "/playlists" && r.method == "POST" -> 200 to """{"MediaContainer":{"Metadata":[{"ratingKey":"9","title":"Mix"}]}}"""
                else -> 400 to ""
            }
        }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        assertEquals("9", p.createPlaylist(ctx, "Mix", "x").id)
    }

    @Test fun plexSaysSoWhenThePlaylistIsNotMade() = runBlocking {
        answer = { r -> if (r.path == "/identity") 200 to """{"MediaContainer":{"machineIdentifier":"abc"}}""" else 400 to "Bad Request" }
        val p = PlexProvider()
        p.login(ctx, url, "", "token")
        val e = runCatching { p.createPlaylist(ctx, "Mix", "") }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("400") == true)
    }

    // ------------------------------------------------------------------ Jellyfin 10.8

    private fun jellyfin108(r: Req): Pair<Int, String> = when {
        r.path == "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok","User":{"Id":"u1"}}"""
        // No "update playlist" before 10.9.
        r.path == "/Playlists/p1" && r.method == "POST" -> 404 to ""
        r.path == "/Users/u1/Items/p1" -> 200 to """{"Id":"p1","Name":"Old","Type":"Playlist","Genres":[],"Tags":[]}"""
        r.path == "/Items/p1" && r.method == "POST" -> 204 to ""
        // No accent folding before 10.9: "energie" finds nothing, "Énergie" finds the song.
        r.path == "/Items" -> 200 to if (r.query["searchTerm"]?.single() == "Énergie") {
            """{"Items":[{"Id":"e","Name":"Énergie (feat. Pharrell)","Artists":["Beyoncé"],"RunTimeTicks":80000000}]}"""
        } else """{"Items":[]}"""
        else -> 404 to ""
    }

    @Test fun jellyfin108RenamesThroughTheItem() = runBlocking {
        answer = ::jellyfin108
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        p.renamePlaylist(ctx, "p1", "New")
        val write = requests.single { it.path == "/Items/p1" }
        val item = parseJson(write.body)
        assertEquals("New", item["Name"].str)
        assertEquals("p1", item["Id"].str) // the rest of the item goes back as read
        assertEquals("Playlist", item["Type"].str)
    }

    @Test fun jellyfin108FindsAccentedTitlesAsWritten() = runBlocking {
        answer = ::jellyfin108
        val p = JellyfinProvider()
        p.login(ctx, url, "admin", "pw")
        val found = p.search(ctx, Track(id = "q", title = "Énergie (feat. Pharrell)", artists = listOf("Beyoncé")))
        assertEquals(listOf("e"), found.map { it.id })
        assertEquals(listOf("energie", "Énergie"), requests.filter { it.path == "/Items" }.map { it.query["searchTerm"]?.single() })
    }
}
