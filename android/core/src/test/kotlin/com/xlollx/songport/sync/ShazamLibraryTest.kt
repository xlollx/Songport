package com.xlollx.songport.sync

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.net.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShazamLibraryTest {

    @Test fun findsMyShazamTracksWhateverItsCase() {
        val lists = listOf(Playlist("1", "Road Trip"), Playlist("2", "my shazam tracks ", 42))
        assertEquals("2", ShazamLibrary.find(lists)?.id)
    }

    @Test fun theExactNameComesFirstAndTheUsersOwnBeforeAFollowedOne() {
        val lists = listOf(
            Playlist("a", "Shazam Party"),
            Playlist("b", "My Shazam Tracks", ownedByMe = false),
            Playlist("c", "My Shazam Tracks"),
        )
        assertEquals("c", ShazamLibrary.find(lists)?.id)
        assertEquals("b", ShazamLibrary.find(lists.take(2))?.id)
    }

    @Test fun aLocalizedNameWithShazamInItIsFound() {
        assertEquals("it", ShazamLibrary.find(listOf(Playlist("x", "Chill"), Playlist("it", "I miei brani di Shazam")))?.id)
        assertEquals("de", ShazamLibrary.find(listOf(Playlist("de", "Meine Shazam-Titel")))?.id)
    }

    @Test fun shazamsPublicChartsAreNotTheUsersShazams() {
        assertNull(ShazamLibrary.find(listOf(Playlist("top", "Shazam Top 200", ownedByMe = false))))
        assertNull(ShazamLibrary.find(listOf(Playlist("1", "Road Trip"), Playlist("2", "Liked Songs"))))
        assertNull(ShazamLibrary.find(emptyList()))
    }

    @Test fun onlySpotifyAndAppleMusicHostIt() {
        listOf("spotify", "spotify_bridge", "apple", "apple_bridge").forEach { assertTrue(it, ShazamLibrary.canHost(it)) }
        listOf("deezer", "ytm", "youtube", "tidal", "csv", "soundcloud", "shazam", "").forEach { assertFalse(it, ShazamLibrary.canHost(it)) }
    }

    @Test fun theShazamFlagIsOptionalInStoredSyncs() {
        // A sync saved before the flag existed reads as a plain sync, and the flag round-trips.
        val old = """{"id":"j","name":"Road Trip","source":{"provider":"spotify","playlistId":"p"},"target":{"provider":"deezer"}}"""
        assertFalse(json.decodeFromString(SyncJob.serializer(), old).shazam)
        val job = SyncJob("j", "Shazams", PlaylistRef("spotify", "p", ShazamLibrary.NAME), PlaylistRef("deezer"), shazam = true)
        assertTrue(json.decodeFromString(SyncJob.serializer(), json.encodeToString(SyncJob.serializer(), job)).shazam)
    }
}
