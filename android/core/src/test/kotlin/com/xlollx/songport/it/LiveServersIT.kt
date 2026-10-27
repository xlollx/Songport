package com.xlollx.songport.it

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.TokenVault
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.CredentialsProvider
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.SubsonicProvider
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * The Subsonic and Jellyfin connectors against real servers (Navidrome, Jellyfin), driven the
 * way the app drives them: credentials login, then the provider API and the sync engine.
 *
 * Skipped unless the servers are given, so CI and F-Droid builds never need them:
 *   SONGPORT_IT_NAVIDROME_URL / _USER / _PASS, SONGPORT_IT_JELLYFIN_URL / _USER / _PASS
 * scripts/live_servers.sh up starts both with the same library and prints them: 12 tagged songs by
 * Metallica, Beyoncé, Måneskin and Daft Punk, the same files on both servers.
 */
class LiveServersIT {
    private fun env(name: String) = System.getenv(name).orEmpty()
    private val nd = env("SONGPORT_IT_NAVIDROME_URL")
    private val jf = env("SONGPORT_IT_JELLYFIN_URL")

    private lateinit var ctx: TestEnv
    private val sub = SubsonicProvider()
    private val jel = JellyfinProvider()
    private val run = UUID.randomUUID().toString().take(6)
    private val made = mutableListOf<Pair<MusicProvider, String>>()

    @Before fun setUp() {
        assumeTrue("live servers not configured", nd.isNotBlank() && jf.isNotBlank())
        ctx = TestEnv()
        FileStore.install(ctx.store)
        Providers.testDoubles = listOf(sub, jel)
        runBlocking {
            sub.login(ctx, nd, env("SONGPORT_IT_NAVIDROME_USER"), env("SONGPORT_IT_NAVIDROME_PASS"))
            jel.login(ctx, jf, env("SONGPORT_IT_JELLYFIN_USER"), env("SONGPORT_IT_JELLYFIN_PASS"))
        }
    }

    @After fun tearDown() {
        if (!::ctx.isInitialized) return
        runBlocking {
            made.forEach { (p, id) -> runCatching { p.deletePlaylist(ctx, id) } }
            // Leave the favourites as found: nothing starred.
            for (p in listOf(sub, jel)) runCatching { p.removeTracks(ctx, MusicProvider.LIKED_ID, p.tracks(ctx, MusicProvider.LIKED_ID)) }
        }
        Providers.testDoubles = emptyList()
    }

    private suspend fun song(p: MusicProvider, artist: String, title: String): Track =
        p.search(ctx, Track(id = "q", title = title, artists = listOf(artist), album = "", durationMs = 0))
            .firstOrNull { it.title == title && it.artists.any { a -> a == artist } }
            ?: throw AssertionError("${p.displayName}: search for $artist – $title found nothing usable")

    private fun names(ts: List<Track>) = ts.map { "${it.artists.firstOrNull()} – ${it.title}" }

    // ------------------------------------------------------------------ per provider

    private fun searches(p: MusicProvider) = runBlocking {
        song(p, "Metallica", "Battery")                                   // exact
        song(p, "Beyoncé", "Café Noir")                                   // accents
        song(p, "Beyoncé", "Énergie (feat. Pharrell)")                    // feat. in the title
        song(p, "Metallica", "Orion (Remastered 2017)")                   // remaster suffix
        song(p, "Måneskin", "Home"); song(p, "Beyoncé", "Home")          // same title, two artists
        // As a sync searches: the source's spelling, without the decorations.
        val viaPlain = p.search(ctx, Track(id = "q", title = "Orion", artists = listOf("Metallica"), album = "", durationMs = 0))
        assertTrue("${p.displayName}: 'Orion' should find the remaster, got ${names(viaPlain)}", viaPlain.any { it.title.startsWith("Orion") })
        val noAccent = p.search(ctx, Track(id = "q", title = "Cafe Noir", artists = listOf("Beyonce"), album = "", durationMs = 0))
        println("${p.displayName}: unaccented search -> ${names(noAccent)}")
    }

    private fun playlistLifecycle(p: MusicProvider) = runBlocking {
        val name = "IT $run ${p.serviceId}"
        val pl = p.createPlaylist(ctx, name, "")
        made += p to pl.id
        assertTrue("${p.displayName}: new playlist listed", p.playlists(ctx).any { it.id == pl.id && it.name == name })
        val a = song(p, "Daft Punk", "One More Time"); val b = song(p, "Måneskin", "Coraline"); val c = song(p, "Metallica", "Battery")
        p.addTracks(ctx, pl.id, listOf(a, b, c))
        p.addTracks(ctx, pl.id, listOf(b)) // a duplicate, as a user could have
        val read = p.tracks(ctx, pl.id)
        assertEquals("${p.displayName}: order after adds", listOf(a.id, b.id, c.id, b.id), read.map { it.id })
        assertTrue("${p.displayName}: durations read", read.all { it.durationMs in 4_000..12_000 })
        // Remove the first Coraline and Battery: one call, positions or entry ids as read.
        p.removeTracks(ctx, pl.id, listOf(read[1], read[2]))
        assertEquals("${p.displayName}: after removing two entries", listOf(a.id, b.id), p.tracks(ctx, pl.id).map { it.id })
        if (p.canRenamePlaylists) {
            p.renamePlaylist(ctx, pl.id, "$name renamed")
            assertTrue("${p.displayName}: renamed", p.playlists(ctx).any { it.id == pl.id && it.name == "$name renamed" })
        }
        p.deletePlaylist(ctx, pl.id); made.remove(p to pl.id)
        assertTrue("${p.displayName}: deleted", p.playlists(ctx).none { it.id == pl.id })
    }

    private fun likes(p: MusicProvider) = runBlocking {
        val a = song(p, "Beyoncé", "Home"); val b = song(p, "Daft Punk", "Digital Love")
        p.addTracks(ctx, MusicProvider.LIKED_ID, listOf(a, b))
        assertEquals("${p.displayName}: starred", setOf(a.id, b.id), p.tracks(ctx, MusicProvider.LIKED_ID).map { it.id }.toSet())
        p.removeTracks(ctx, MusicProvider.LIKED_ID, listOf(a))
        assertEquals("${p.displayName}: unstarred one", setOf(b.id), p.tracks(ctx, MusicProvider.LIKED_ID).map { it.id }.toSet())
    }

    @Test fun navidromeSearch() = searches(sub)
    @Test fun jellyfinSearch() = searches(jel)
    @Test fun navidromePlaylists() = playlistLifecycle(sub)
    @Test fun jellyfinPlaylists() = playlistLifecycle(jel)
    @Test fun navidromeLikes() = likes(sub)
    @Test fun jellyfinLikes() = likes(jel)

    @Test fun navidromeReadsIsrc() = runBlocking {
        val t = song(sub, "Metallica", "Battery")
        assertEquals("Navidrome lists the ISRC of the file (OpenSubsonic)", "USEE10001001", t.isrc)
    }

    // ------------------------------------------------------------------ the engine, both ways

    private fun job(src: MusicProvider, srcId: String, dst: MusicProvider, dstId: String?, mirror: Boolean): SyncJob {
        val j = SyncJob(
            id = UUID.randomUUID().toString(), name = "IT $run",
            source = PlaylistRef(src.id, srcId, srcId), target = PlaylistRef(dst.id, dstId, dstId ?: "IT $run target"),
            mirrorRemovals = mirror,
        )
        ctx.store.upsertJob(j)
        return j
    }

    @Test fun navidromePlaylistToJellyfin() = runBlocking {
        val engine = SyncEngine(ctx)
        val src = sub.createPlaylist(ctx, "IT $run source", ""); made += sub to src.id
        val picks = listOf(
            song(sub, "Metallica", "Battery"), song(sub, "Beyoncé", "Café Noir"), song(sub, "Beyoncé", "Énergie (feat. Pharrell)"),
            song(sub, "Metallica", "Orion (Remastered 2017)"), song(sub, "Måneskin", "Home"), song(sub, "Beyoncé", "Home"),
        )
        sub.addTracks(ctx, src.id, picks)
        val j = job(sub, src.id, jel, null, mirror = true)
        val first = engine.run(j)
        val why = "error=${first.error} notes=${first.notes} unmatched=${first.unmatched} review=${first.reviewTracks.size}"
        assertEquals(why, null, first.error)
        assertEquals(why, 6, first.added)
        val target = ctx.store.job(j.id)!!.target.playlistId!!
        made += jel to target
        val onJf = jel.tracks(ctx, target)
        assertEquals(why, names(picks), names(onJf))
        // A second run changes nothing.
        val again = engine.run(ctx.store.job(j.id)!!)
        assertEquals("second run: ${again.notes} ${again.error}", 0, again.added)
        assertEquals("second run: ${again.notes} ${again.error}", 0, again.removed)
        // The source loses two tracks: the mirror removes them on Jellyfin.
        val read = sub.tracks(ctx, src.id)
        sub.removeTracks(ctx, src.id, listOf(read[0], read[4]))
        val third = engine.run(ctx.store.job(j.id)!!)
        assertEquals("mirror: ${third.notes} ${third.error}", 2, third.removed)
        assertEquals(names(sub.tracks(ctx, src.id)), names(jel.tracks(ctx, target)))
    }

    @Test fun jellyfinFavouritesToNavidrome() = runBlocking {
        val engine = SyncEngine(ctx)
        val favs = listOf(song(jel, "Daft Punk", "Something About Us (feat. Romanthony)"), song(jel, "Måneskin", "Zitti e buoni"), song(jel, "Metallica", "Master of Puppets"))
        jel.addTracks(ctx, MusicProvider.LIKED_ID, favs)
        val j = job(jel, MusicProvider.LIKED_ID, sub, MusicProvider.LIKED_ID, mirror = false)
        val first = engine.run(j)
        val why = "error=${first.error} notes=${first.notes} unmatched=${first.unmatched}"
        assertEquals(why, null, first.error)
        assertEquals(why, 3, first.added)
        assertEquals(names(favs).toSet(), names(sub.tracks(ctx, MusicProvider.LIKED_ID)).toSet())
        val again = engine.run(ctx.store.job(j.id)!!)
        assertEquals("second run: ${again.notes}", 0, again.added)
    }
}

/** The platform for these tests: a temporary folder, tokens in memory, texts by name. */
class TestEnv : Env {
    override val dataDir: File = Files.createTempDirectory("songport-it").toFile()
    override val store = FileStore(dataDir)
    private val vault = HashMap<String, Tokens>()
    override val tokens = object : TokenVault {
        override fun get(providerId: String) = vault[providerId]
        override fun set(providerId: String, tokens: Tokens) { vault[providerId] = tokens }
        override fun clear(providerId: String) { vault.remove(providerId) }
    }
    override val redirectUri = ""
    override fun text(name: String, vararg args: Any) = name + if (args.isEmpty()) "" else args.joinToString(",", "(", ")")
    override fun log(tag: String, message: String) = println("[$tag] $message")
    override fun startAuth(provider: MusicProvider, mode: AuthMode) = Unit
    private val kv = HashMap<String, KeyValues>()
    override fun keyValues(name: String): KeyValues = kv.getOrPut(name) {
        val m = HashMap<String, String>()
        object : KeyValues {
            override fun get(key: String) = m[key]
            override fun set(key: String, value: String) { m[key] = value }
            override val size get() = m.size
            override fun clear() = m.clear()
        }
    }
    override fun shrinkJpeg(raw: ByteArray, maxSide: Int, maxBytes: Int): ByteArray? = null
}
