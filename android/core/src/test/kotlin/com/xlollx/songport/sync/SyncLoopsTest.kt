package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 1.3.3 diagnostics report: runs that repeat the same writes forever.
 *
 * - "Liked songs" (Spotify to YouTube Music's Liked music, Mirror on): "+5" on every run, the target
 *   never growing past 2081. Liking those five "worked", but Liked music does not list them (videos,
 *   not songs), so each run took them for missing and liked them again.
 * - "Metal & More" (Spotify to Amazon Music, Mirror on): "-1" on every run, the target staying at
 *   1141. Amazon answered the removal, but the entry stayed.
 */
class SyncLoopsTest {
    private lateinit var env: ContractEnv
    private lateinit var src: MemoryService
    private lateinit var dst: LoopingService
    private lateinit var engine: SyncEngine

    private fun t(id: String, title: String, artist: String = "Band") =
        Track(id = id, title = title, artists = listOf(artist), album = "Album", durationMs = 200_000)

    @Before fun setUp() {
        env = ContractEnv()
        src = MemoryService("msrc")
        dst = LoopingService("ytmx")
        Providers.testDoubles = listOf(src, dst)
        engine = SyncEngine(env)
    }

    @After fun tearDown() { Providers.testDoubles = emptyList() }

    private fun job(name: String, target: String, mirror: Boolean = true): SyncJob = SyncJob(
        id = "job-" + System.nanoTime(), name = name,
        source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("ytmx", target, target), mirrorRemovals = mirror,
    ).also { env.store.upsertJob(it) }

    private suspend fun rerun(j: SyncJob) = engine.run(env.store.job(j.id)!!)

    // ------------------------------------------------------------------ added, but never listed

    private val liked = MusicProvider.LIKED_ID
    private val songs = listOf(t("a", "Alpha"), t("b", "Beta"))
    private val videos = listOf("Morning Vlog", "Unboxing", "Tour Diary", "Live at Home", "Q&A").mapIndexed { i, title -> t("v${i + 1}", title, "Vlogger") }

    private fun likedLoop() {
        src.lists["p"] = (songs + videos + t("c", "Gamma")).toMutableList()
        dst.catalogue += songs.map { it.copy(id = "y" + it.id) } + videos.map { it.copy(id = "y" + it.id) } + t("yc", "Gamma")
        dst.lists[liked] = songs.map { it.copy(id = "y" + it.id) }.toMutableList()
        // Liked, but Liked music never lists them.
        dst.hidden += videos.map { "y" + it.id }
    }

    @Test fun tracksLikedButNeverListedAreNotLikedAgainOnTheNextRun() = runBlocking {
        likedLoop()
        val j = job("Liked songs", liked)
        val first = engine.run(j)
        assertEquals("error=${first.error} notes=${first.notes}", 6, first.added)
        assertEquals(6, dst.addAttempts.size)

        // The owner's loop: +5 every run. Now the five are known to be there, unlisted: +0.
        dst.addAttempts.clear()
        val second = rerun(j)
        assertEquals("error=${second.error} notes=${second.notes}", 0, second.added)
        assertTrue(second.addedTracks.isEmpty())
        assertEquals(0, second.removed)
        assertTrue(dst.addAttempts.toString(), dst.addAttempts.isEmpty())
        val line = env.logs.single { it.contains("added before but not shown") }
        assertEquals("[engine] Liked songs: 5 tracks added before but not shown by ytmx: " +
            "Vlogger – Morning Vlog; Vlogger – Unboxing; Vlogger – Tour Diary; Vlogger – Live at Home; Vlogger – Q&A", line)

        // And on the runs after: still +0, said once.
        val third = rerun(j)
        assertEquals(0, third.added)
        assertTrue(dst.addAttempts.toString(), dst.addAttempts.isEmpty())
        assertEquals(1, env.logs.count { it.contains("added before but not shown") })
    }

    @Test fun aTrackTheUserTookOffTheTargetComesBackAfterTheMemoryExpires() = runBlocking {
        likedLoop()
        val j = job("Liked songs", liked)
        engine.run(j)
        rerun(j)
        // A month later the memory has expired: the five are liked again, which also brings back one
        // the user may have unliked on the target in the meantime.
        env.store.update { d -> d.copy(presentUnlisted = d.presentUnlisted.mapValues { it.value - 31L * 24 * 3_600_000 }) }
        dst.addAttempts.clear()
        val later = rerun(j)
        assertEquals("error=${later.error} notes=${later.notes}", 5, later.added)
        assertEquals(videos.map { "y" + it.id }.toSet(), dst.addAttempts.toSet())
        // Still not listed after that: known again, +0 from the next run.
        dst.addAttempts.clear()
        assertEquals(0, rerun(j).added)
        assertTrue(dst.addAttempts.isEmpty())
    }

    @Test fun aTrackListedOnceThatTheUserRemovesLaterIsAddedBackAtOnce() = runBlocking {
        likedLoop()
        dst.hidden.clear()
        val j = job("Liked songs", liked)
        assertEquals(6, engine.run(j).added)
        // The next run lists it: nothing to add.
        assertEquals(0, rerun(j).added)
        // Then taken off the target by the user: an ordinary missing track, added back at once.
        dst.lists[liked]!!.removeAll { it.id == "yv1" }
        dst.addAttempts.clear()
        val r = rerun(j)
        assertEquals(1, r.added)
        assertEquals(listOf("yv1"), dst.addAttempts)
        assertTrue(env.logs.none { it.contains("added before but not shown") })
    }

    // ------------------------------------------------------------------ removed, but never gone

    private fun metal() {
        src.lists["p"] = mutableListOf(t("a", "Alpha"), t("b", "Beta"))
        dst.catalogue += listOf(t("B0A", "Alpha"), t("B0B", "Beta"))
        dst.lists["t"] = mutableListOf(t("B0A", "Alpha"), t("B0B", "Beta"), t("B0STUCK", "Stuck", "Gone Band"))
        // Amazon answers the removal of this entry, and keeps it.
        dst.sticky += "B0STUCK"
    }

    @Test fun anEntryARemovalDoesNotTakeAwayIsNotRemovedAgainOnEveryRun() = runBlocking {
        metal()
        val j = job("Metal & More", "t")
        val first = engine.run(j)
        assertEquals("error=${first.error} notes=${first.notes}", 1, first.removed)
        assertTrue(env.logs.toString(), env.logs.contains("[engine] Metal & More: 1 track removed from ytmx: Gone Band – Stuck"))

        // The owner's loop: -1 every run. Listed again after the removal: kept, said once, nothing removed.
        dst.removeAttempts.clear()
        val second = rerun(j)
        assertEquals("error=${second.error} notes=${second.notes}", 0, second.removed)
        assertTrue(second.removedTracks.isEmpty())
        assertTrue(dst.removeAttempts.toString(), dst.removeAttempts.isEmpty())
        assertEquals("[engine] Metal & More: ytmx keeps 1 track that removing does not take away: Gone Band – Stuck",
            env.logs.single { it.contains("removing does not take away") })

        val third = rerun(j)
        assertEquals(0, third.removed)
        assertTrue(dst.removeAttempts.isEmpty())
        assertEquals(1, env.logs.count { it.contains("removing does not take away") })
        assertEquals(3, dst.lists["t"]!!.size)
    }

    @Test fun anEntryTheServiceReportsAsStillThereCountsAsNotRemoved() = runBlocking {
        metal()
        dst.reportsKept = true
        val j = job("Metal & More", "t")
        val first = engine.run(j)
        assertEquals("error=${first.error} notes=${first.notes}", 0, first.removed)
        assertTrue(first.removedTracks.isEmpty())
        assertTrue(env.logs.none { it.contains("removed from ytmx") })
        assertEquals(1, env.logs.count { it.contains("ytmx keeps 1 track that removing does not take away: Gone Band – Stuck") })
        dst.removeAttempts.clear()
        assertEquals(0, rerun(j).removed)
        assertTrue(dst.removeAttempts.isEmpty())
        assertEquals(1, env.logs.count { it.contains("removing does not take away") })
    }

    @Test fun aRemovalThatSticksIsNotHeldOnTheNextRun() = runBlocking {
        metal()
        dst.sticky.clear()
        val j = job("Metal & More", "t")
        assertEquals(1, engine.run(j).removed)
        assertEquals(2, dst.lists["t"]!!.size)
        // Another track leaves the source: removed as usual.
        src.lists["p"]!!.removeAll { it.id == "b" }
        val r = rerun(j)
        assertEquals(1, r.removed)
        assertEquals(listOf("B0B"), r.removedTracks.map { it.id })
        assertTrue(env.logs.none { it.contains("removing does not take away") })
    }

    @Test fun aTrackTheUserPutBackFromAReportIsNotTakenForOneTheServiceKeeps() = runBlocking {
        metal()
        dst.sticky.clear()
        val j = job("Metal & More", "t")
        val first = engine.run(j)
        assertEquals(1, first.removed)
        engine.restoreRemoved(env.store.job(j.id)!!, first)
        assertEquals(3, dst.lists["t"]!!.size)
        // Mirror removes it again, as before: the user put it back on a target that follows the source.
        assertEquals(1, rerun(j).removed)
        assertTrue(env.logs.none { it.contains("removing does not take away") })
    }

    // ------------------------------------------------------------------ the error line

    @Test fun aServiceErrorIsLoggedByItsMessageWithoutAClassName() = runBlocking {
        src.failure = ProviderException("SoundCloud (web): the SoundCloud web session has expired")
        val r = engine.run(job("Liked songs", "t"))
        assertEquals("SoundCloud (web): the SoundCloud web session has expired", r.error)
        assertTrue(env.logs.toString(), env.logs.contains("[engine] Liked songs: SoundCloud (web): the SoundCloud web session has expired"))
        assertFalse(env.logs.toString(), env.logs.any { it.contains("ProviderException") })
    }
}

/**
 * A target that loops the way the report shows: [hidden] tracks are added (no refusal, no error) but
 * never listed, like YouTube's Liked music with a liked video; [sticky] entries are listed and stay
 * after a removal that raised no error, like the Amazon Music entry. With [reportsKept] the service
 * checks its listing after removing and says which entries stayed.
 */
class LoopingService(private val inner: MemoryService) : MusicProvider by inner {
    constructor(serviceId: String) : this(MemoryService(serviceId))
    val catalogue get() = inner.catalogue
    val lists get() = inner.lists
    val hidden = HashSet<String>()
    val sticky = HashSet<String>()
    var reportsKept = false
    val addAttempts = ArrayList<String>()
    val removeAttempts = ArrayList<String>()

    override val supportsLikedTarget: Boolean get() = true
    override suspend fun playlists(ctx: Env) = inner.playlists(ctx)
    override suspend fun search(ctx: Env, track: Track) = inner.search(ctx, track)
    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = inner.tracks(ctx, playlistId).filter { it.id !in hidden }
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        addAttempts += tracks.map { it.id }
        val list = inner.lists.getOrPut(playlistId) { ArrayList() }
        list += tracks.filter { t -> list.none { it.id == t.id } }
    }
    override suspend fun addTracksReportingPresent(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        addTracks(ctx, playlistId, tracks)
        return emptySet()
    }
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) {
        removeAttempts += tracks.map { it.id }
        inner.removeTracks(ctx, playlistId, tracks.filter { it.id !in sticky })
    }
    override suspend fun removeTracksReportingKept(ctx: Env, playlistId: String, tracks: List<Track>): Set<String> {
        removeTracks(ctx, playlistId, tracks)
        if (!reportsKept) return emptySet()
        val listed = tracks(ctx, playlistId).map { it.id }.toSet()
        return tracks.map { it.id }.filter { it in listed }.toSet()
    }
}
