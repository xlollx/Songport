package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * The Shazam sync's pages, through a real server: Shazam's playlist is looked for on a connected
 * Spotify, here a fake one under Spotify's own service id (the real connector needs the network),
 * so the page's rule (only Spotify and Apple Music can hold it) is the one that applies.
 */
class ShazamWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    /** Spotify, signed in, with the playlists in [lists]. */
    private class FakeSpotify(val lists: MutableList<Playlist>) : MusicProvider {
        override val serviceId = "spotify"
        override val displayName = "Spotify"
        override val brandColor = 0xFF1DB954
        override val noteName = "provider_note_spotify"
        override fun isConfigured(ctx: Env) = true
        override fun isConnected(ctx: Env) = true
        override fun accountName(ctx: Env) = "alex"
        override fun startAuth(ctx: Env) {}
        override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
        override fun disconnect(ctx: Env) {}
        override suspend fun playlists(ctx: Env) = lists.toList()
        override suspend fun tracks(ctx: Env, playlistId: String) = emptyList<Track>()
        override suspend fun search(ctx: Env, track: Track) = emptyList<Track>()
        override suspend fun createPlaylist(ctx: Env, name: String, description: String) = Playlist("new", name, 0)
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {}
    }

    private val spotifyLists = mutableListOf(Playlist("road", "Road Trip", 8), Playlist("top", "Shazam Top 200", 200, ownedByMe = false))

    @Before fun start() {
        val data = tmp.newFolder("data")
        File(data, "csv").mkdirs()
        Providers.install(listOf(Providers.Factory("spotify") { FakeSpotify(spotifyLists) }), singletons = listOf(LocalFilesProvider))
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() {
        server.stop(0)
        installServerConnectors()
    }

    private fun get(path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String) =
        client.send(
            HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun add(service: String) = assertEquals(303, post("/connectors/add", "service=$service").statusCode())

    @Test fun withoutSpotifyThePageSaysHowToTurnItOnAndWhereToConnect() {
        add("csv")
        val home = get("/").body()
        assertTrue(home.contains("href=\"/syncs/shazam\"") && home.contains("New Shazam sync"))
        val page = get("/syncs/shazam").body()
        assertTrue(page, page.contains("Turn on Shazam's playlist") && page.contains("Settings › Connect to Spotify"))
        assertTrue("no Spotify connected: the way to connect it", page.contains("href=\"/connectors\""))
        assertTrue(page.contains("Check again"))
    }

    @Test fun shazamsChartsAreNotTheUsersShazams() {
        add("spotify"); add("csv")
        // Spotify is connected, but holds only a followed chart with "Shazam" in its name.
        val page = get("/syncs/shazam").body()
        assertTrue(page, page.contains("Turn on Shazam's playlist"))
        assertFalse("Spotify is connected already", page.contains("Then connect the same Spotify account"))
        assertFalse(page.contains("Your Shazams:"))
    }

    @Test fun aShazamSyncIsMadeFromMyShazamTracksAndWearsTheMark() {
        add("spotify"); add("csv")
        spotifyLists += Playlist("shz", "My Shazam Tracks", 12)
        val first = get("/syncs/shazam").body()
        assertTrue(first, first.contains("Your Shazams: <b>My Shazam Tracks</b> on Spotify (12 tracks)") && first.contains("name=\"dst\""))
        val step = get("/syncs/shazam?dst=csv").body()
        assertTrue(step, step.contains("name=\"srcPlaylist\" value=\"shz\"") && step.contains("value=\"HOURLY\" selected"))

        // Another playlist's id in the form: refused, nothing made.
        val forged = post("/syncs/shazam", "src=spotify&srcPlaylist=road&dst=csv&dstPlaylist=&schedule=HOURLY")
        assertTrue(forged.headers().firstValue("Location").orElse("").contains("err="))
        // Nor from a service that cannot hold Shazam's playlist.
        val fromFile = post("/syncs/shazam", "src=csv&srcPlaylist=shz&dst=csv&dstPlaylist=&schedule=HOURLY")
        assertTrue(fromFile.headers().firstValue("Location").orElse("").contains("err="))
        assertTrue(env.store.data.jobs.isEmpty())

        val made = post("/syncs/shazam", "src=spotify&srcPlaylist=shz&dst=csv&dstPlaylist=&name=&schedule=HOURLY")
        assertEquals(303, made.statusCode())
        assertTrue(made.headers().firstValue("Location").orElse("").startsWith("/syncs/"))
        val job = env.store.data.jobs.single()
        assertTrue(job.shazam)
        assertEquals("spotify", job.source.provider)
        assertEquals("shz", job.source.playlistId)
        assertEquals("My Shazam Tracks", job.name)
        assertEquals("My Shazam Tracks", job.target.playlistName)
        assertEquals(Schedule.HOURLY, job.schedule)
        assertFalse(job.mirrorRemovals)

        // The card wears Shazam's mark, the API says it is a Shazam sync.
        // On the home page: once on "New Shazam sync", once on the card.
        val home = get("/").body()
        assertEquals(2, Regex("aria-label=\"Shazam sync\"").findAll(home).count())
        assertTrue(get("/syncs/${URLEncoder.encode(job.id, "UTF-8")}").body().contains("aria-label=\"Shazam sync\""))
        val json = Api.job(env, job, running = false).toString()
        assertTrue(json, json.contains("\"shazam\":true"))
        assertTrue(Api.job(env, job.copy(shazam = false), running = false).toString().contains("\"shazam\":false"))
    }
}
