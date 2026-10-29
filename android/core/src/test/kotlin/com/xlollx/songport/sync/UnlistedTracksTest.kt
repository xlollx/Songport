package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The "Metal & More" report (1.2.7, Spotify to Amazon Music, Mirror on): "+2 -0" on every run, the
 * same two tracks listed as added each time, the target count never growing. The playlist held
 * them, but Amazon's listing did not show them; the sync took them for missing, and Amazon refused
 * them as duplicates, which the sync counted as added. Here the target behaves the same way.
 */
class UnlistedTracksTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: UnlistingService
    private lateinit var engine: SyncEngine

    private fun t(id: String, title: String, artist: String = "Band") =
        Track(id = id, title = title, artists = listOf(artist), album = "Album", durationMs = 200_000)

    private val friend = t("sp-friend", "Talk to a Friend", "Rain City Drive")
    private val lie = t("sp-lie", "Bury a Lie", "Rain City Drive")

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = UnlistingService("amz")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
        src.lists["p"] = mutableListOf(t("sp-a", "Alpha"), friend, lie)
        dst.catalogue += listOf(t("B0A", "Alpha"), t("B0FRIEND", "Talk to a Friend", "Rain City Drive"), t("B0LIE", "Bury a Lie", "Rain City Drive"))
        // Amazon holds all three; its listing leaves the two Rain City Drive entries out.
        dst.lists["t"] = mutableListOf(t("B0A", "Alpha"), t("B0FRIEND", "Talk to a Friend", "Rain City Drive"), t("B0LIE", "Bury a Lie", "Rain City Drive"))
        dst.hidden += setOf("B0FRIEND", "B0LIE")
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(): SyncJob = SyncJob(
        id = "job-" + System.nanoTime(), name = "Metal & More",
        source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("amz", "t", "t"), mirrorRemovals = true,
    ).also { env.store.upsertJob(it) }

    @Test fun tracksTheServiceRefusesAsDuplicatesAreNotAddedAndNotTriedAgain() = runBlocking {
        val j = job()
        val first = engine.run(j)
        assertEquals("error=${first.error} notes=${first.notes}", null, first.error)
        // Asked once, refused as already there: present, not added.
        assertEquals(listOf("B0FRIEND", "B0LIE"), dst.addAttempts)
        assertEquals(0, first.added)
        assertTrue(first.addedTracks.isEmpty())
        assertEquals(0, first.removed)
        assertEquals(3, dst.lists["t"]!!.size)
        val line = env.logs.single { it.contains("missing from its playlist listing") }
        assertTrue(line, line.contains("2 tracks already on amz but missing from its playlist listing: Rain City Drive – Talk to a Friend; Rain City Drive – Bury a Lie"))

        // The next run knows they are there: +0, and nothing asked of the service.
        dst.addAttempts.clear()
        val second = engine.run(env.store.job(j.id)!!)
        assertEquals("error=${second.error} notes=${second.notes}", 0, second.added)
        assertEquals(0, second.removed)
        assertTrue(dst.addAttempts.toString(), dst.addAttempts.isEmpty())
        assertEquals(3, dst.lists["t"]!!.size)
        assertEquals(1, env.logs.count { it.contains("missing from its playlist listing") })
    }

    @Test fun untitledEntriesAreMatchedByIdAndNeverRemovedOnAGuess() = runBlocking {
        // The fixed listing: the two entries come by id, without a title.
        dst.hidden.clear()
        dst.lists["t"] = mutableListOf(t("B0A", "Alpha"), t("B0FRIEND", ""), t("B0LIE", ""), t("B0OLD", ""))
        // The cache already maps the source tracks to the ids Amazon holds (an earlier run added them).
        env.store.putMatches(mapOf(
            com.xlollx.songport.data.FileStore.cacheKey("msrc", "sp-friend", "amz") to "B0FRIEND",
            com.xlollx.songport.data.FileStore.cacheKey("msrc", "sp-lie", "amz") to "B0LIE",
        ))
        val r = engine.run(job())
        assertEquals("error=${r.error} notes=${r.notes}", 0, r.added)
        assertTrue(dst.addAttempts.toString(), dst.addAttempts.isEmpty())
        // B0OLD matches no source track, but an entry known by id alone is not removed.
        assertEquals(0, r.removed)
        assertEquals(4, dst.lists["t"]!!.size)
        assertTrue(env.logs.toString(), env.logs.any { it.contains("1 entries of the target listed without a title") })
    }

    @Test fun aServiceThatAddsReportsEveryTrackAsAdded() = runBlocking {
        // Nothing hidden and nothing there: the ordinary path, two additions counted and listed.
        dst.hidden.clear()
        dst.lists["t"] = mutableListOf(t("B0A", "Alpha"))
        val r = engine.run(job())
        assertEquals("error=${r.error} notes=${r.notes}", 2, r.added)
        assertEquals(listOf("B0FRIEND", "B0LIE"), r.addedTracks.map { it.id })
        assertTrue(env.logs.none { it.contains("missing from its playlist listing") })
    }
}

/**
 * A service like Amazon Music as 1.2.7 saw it: [hidden] entries are in the playlist but not in its
 * listing, and adding a track the playlist holds is refused as a duplicate (reported, not added).
 */
class UnlistingService(private val inner: MemoryService) : MusicProvider by inner {
    constructor(serviceId: String) : this(MemoryService(serviceId))
    val catalogue get() = inner.catalogue
    val lists get() = inner.lists
    val hidden = HashSet<String>()
    val addAttempts = ArrayList<String>()

    override val listsUntitledEntries: Boolean get() = true

    override suspend fun playlists(ctx: Env) = inner.playlists(ctx)
    override suspend fun search(ctx: Env, track: Track) = inner.search(ctx, track)
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = inner.tracks(ctx, playlistId).filter { it.id !in hidden }
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = inner.removeTracks(ctx, playlistId, tracks)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) { addTracksReportingPresent(ctx, playlistId, tracks) }
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        addAttempts += tracks.map { it.id }
        val list = inner.lists.getOrPut(playlistId) { ArrayList() }
        val held = list.map { it.id }.toSet()
        val (dup, fresh) = tracks.partition { it.id in held }
        list += fresh
        return dup.map { it.id }.toSet()
    }
}
