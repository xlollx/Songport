package com.xlollx.songport.sync

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageStatsTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, s: Int = 0, zone: ZoneId = rome) =
        ZonedDateTime.of(y, m, d, h, min, s, 0, zone).toInstant().toEpochMilli()

    private fun job(id: String, src: String, dst: String, name: String = id) =
        SyncJob(id, name, PlaylistRef(src, "p-$id", "src $id"), PlaylistRef(dst, "t-$id", "dst $id"))

    private var seq = 0
    private fun report(
        jobId: String, started: Long, added: Int = 0, sourceCount: Int = 0, unmatched: Int = 0, ignored: Int = 0,
        addedTracks: List<Track> = emptyList(), error: String? = null, partial: Boolean = false,
    ) = SyncReport(
        id = "r${seq++}", jobId = jobId, jobName = "name $jobId", startedEpoch = started, durationMs = 1_000,
        sourceCount = sourceCount, added = added, unmatched = List(unmatched) { "u$it" }, ignored = ignored,
        addedTracks = addedTracks, error = error, partial = partial,
    )

    private fun song(artist: String, title: String = "t", kind: String = "") = Track(id = "$artist/$title", title = title, artists = listOf(artist), kind = kind)

    @Test fun emptyStoreHasNothingToShow() {
        val s = UsageStats.compute(StoreData(), at(2026, 10, 27), rome)
        assertTrue(s.isEmpty)
        assertEquals(0, s.added)
        assertEquals(0, s.addedThisMonth)
        assertEquals(0L, s.sinceEpoch)
        assertNull(s.route)
        assertNull(s.longest)
        assertNull(s.matchPercent)
        assertTrue(s.topArtists.isEmpty())
        assertFalse(s.historyCapped)
        assertEquals(0L, s.secondsSaved)
    }

    @Test fun aggregatesRunsTracksAndTheMostUsedRoute() {
        val jobs = listOf(job("a", "spotify", "ytm"), job("b", "spotify@2", "youtube"), job("c", "deezer", "tidal"))
        // Newest first, as the store keeps them.
        val reports = listOf(
            report("c", at(2026, 10, 20), added = 500, sourceCount = 500),
            report("b", at(2026, 10, 10), added = 3, sourceCount = 40),
            report("a", at(2026, 10, 5), added = 10, sourceCount = 100),
            report("a", at(2026, 9, 1), added = 0, error = "boom"),
            report("a", at(2026, 8, 1), added = 7),
            report("gone", at(2026, 7, 1), added = 2),
        )
        val s = UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome)
        assertEquals("failed runs are not syncs completed", 5, s.runs)
        assertEquals(522, s.added)
        assertEquals(513, s.addedThisMonth)
        assertEquals(3, s.syncs)
        assertEquals(at(2026, 7, 1), s.sinceEpoch)
        // spotify>youtube (two accounts, two YouTube routes) ran three times; deezer>tidal once, with more tracks.
        val r = s.route!!
        assertEquals(3, r.runs)
        assertEquals(20, r.added)
        assertEquals("the logos follow the most recent run", "spotify@2", r.sourceProvider)
        assertEquals("youtube", r.targetProvider)
        assertEquals(522L * UsageStats.SECONDS_PER_TRACK, s.secondsSaved)
    }

    @Test fun matchRateAndLongestUseEachSyncsLatestRunThatReadTheSource() {
        val jobs = listOf(job("a", "spotify", "ytm", name = "Road trip"), job("b", "deezer", "tidal"))
        val reports = listOf(
            // "Unchanged" runs read nothing: the one before counts.
            report("a", at(2026, 10, 3), sourceCount = 0),
            report("a", at(2026, 10, 2), added = 1, sourceCount = 120, unmatched = 10, ignored = 20),
            report("a", at(2026, 10, 1), added = 5, sourceCount = 90, unmatched = 50),
            report("b", at(2026, 10, 1), added = 2, sourceCount = 50, unmatched = 0),
        )
        val s = UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome)
        // a: 100 looked for (20 ignored), 90 found; b: 50 of 50.
        assertEquals(150, s.matchSearched)
        assertEquals(140, s.matchFound)
        assertEquals(93, s.matchPercent)
        val l = s.longest!!
        assertEquals("Road trip", l.name)
        assertEquals(120, l.tracks)
        assertEquals("spotify", l.sourceProvider)
        assertEquals("ytm", l.targetProvider)
    }

    @Test fun matchRateNeverRoundsUpToAHundred() {
        val jobs = listOf(job("a", "spotify", "ytm"))
        val reports = listOf(report("a", at(2026, 10, 2), sourceCount = 1000, unmatched = 1))
        assertEquals(99, UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome).matchPercent)
    }

    @Test fun unfinishedRunsAreLeftOut() {
        val jobs = listOf(job("a", "spotify", "ytm"))
        val reports = listOf(
            report("a", at(2026, 10, 9), sourceCount = 80, unmatched = 40, partial = true),
            report("a", at(2026, 10, 8), added = 4, sourceCount = 80, unmatched = 0),
        )
        val s = UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome)
        assertEquals(1, s.runs)
        assertEquals(100, s.matchPercent)
        assertEquals(at(2026, 10, 8), s.sinceEpoch)
    }

    @Test fun monthBoundariesFollowTheLocalCalendar() {
        val jobs = listOf(job("a", "spotify", "ytm"))
        val reports = listOf(
            report("a", at(2026, 11, 1, 0, 0, 0), added = 1000), // next month: not "this month"
            report("a", at(2026, 10, 31, 23, 59, 59), added = 100),
            report("a", at(2026, 10, 1, 0, 0, 0), added = 10),
            report("a", at(2026, 9, 30, 23, 59, 59), added = 1),
        )
        val s = UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 15), rome)
        assertEquals(110, s.addedThisMonth)
        assertEquals(1111, s.added)
        // The same instant is still September in New York: the zone decides the month.
        val ny = ZoneId.of("America/New_York")
        val (start, end) = UsageStats.monthBounds(at(2026, 10, 1, 2, 0, 0), ny)
        assertEquals(at(2026, 9, 1, 0, 0, 0, ny), start)
        assertEquals(at(2026, 10, 1, 0, 0, 0, ny), end)
        // February of a leap year ends on the 29th.
        val (fs, fe) = UsageStats.monthBounds(at(2028, 2, 29, 23), rome)
        assertEquals(at(2028, 2, 1, 0), fs)
        assertEquals(at(2028, 3, 1, 0), fe)
    }

    @Test fun oldReportsWithoutTrackListsKeepTheCountsButNoArtists() {
        val jobs = listOf(job("a", "spotify", "ytm"))
        // Runs from before the added list was kept: a count, no tracks.
        val reports = List(5) { report("a", at(2026, 10, it + 1), added = 200, sourceCount = 400) }
        val s = UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome)
        assertEquals(1000, s.added)
        assertEquals(0, s.artistSample)
        assertTrue(s.topArtists.isEmpty())
    }

    @Test fun topArtistsNeedEnoughListedSongs() {
        val jobs = listOf(job("a", "spotify", "ytm"))
        val few = List(UsageStats.MIN_TRACKS_FOR_ARTISTS - 1) { song("Metallica", "s$it") }
        val s1 = UsageStats.compute(StoreData(jobs = jobs, reports = listOf(report("a", at(2026, 10, 1), added = few.size, addedTracks = few))), at(2026, 10, 27), rome)
        assertTrue(s1.topArtists.isEmpty())

        val tracks = List(12) { song("Metallica", "m$it") } + List(5) { song("metallica ", "x$it") } +
            List(4) { song("Avenged Sevenfold", "a$it") } + List(4) { song("Ghost", "g$it") } +
            song("One Hit") + List(9) { song("Album Artist", "al$it", kind = Track.KIND_ALBUM) } +
            Track(id = "noartist", title = "Untitled")
        // Spread over an old run (count only) and two new ones.
        val reports = listOf(
            report("a", at(2026, 10, 3), added = 20, addedTracks = tracks.take(20)),
            report("a", at(2026, 10, 2), added = tracks.size - 20, addedTracks = tracks.drop(20)),
            report("a", at(2026, 10, 1), added = 300),
        )
        val s = UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome)
        assertEquals("songs with an artist only", 26, s.artistSample)
        assertEquals(
            listOf(UsageStats.ArtistCount("Metallica", 17), UsageStats.ArtistCount("Avenged Sevenfold", 4), UsageStats.ArtistCount("Ghost", 4)),
            s.topArtists,
        )
    }

    @Test fun aFullHistorySaysSo() {
        val jobs = listOf(job("a", "spotify", "ytm"))
        val reports = List(5) { report("a", at(2026, 10, it + 1), added = 1) }
        assertTrue(UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome, historyLimit = 5).historyCapped)
        assertFalse(UsageStats.compute(StoreData(jobs = jobs, reports = reports), at(2026, 10, 27), rome, historyLimit = 6).historyCapped)
    }

    @Test fun runsOfDeletedSyncsCountButHaveNoRoute() {
        val reports = listOf(report("gone", at(2026, 10, 1), added = 9, sourceCount = 9))
        val s = UsageStats.compute(StoreData(reports = reports), at(2026, 10, 27), rome)
        assertEquals(1, s.runs)
        assertEquals(9, s.added)
        assertNull(s.route)
        assertNull(s.longest)
        assertNull(s.matchPercent)
    }
}
