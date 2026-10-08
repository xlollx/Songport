package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider

/**
 * A music service in memory: a catalogue to search (by normalised title) and playlists to read
 * and write. Records what the engine asked of it.
 */
class FakeProvider(override val serviceId: String, catalogue: List<Track> = emptyList()) : MusicProvider {
    val catalogue = catalogue.toMutableList()
    val lists = LinkedHashMap<String, MutableList<Track>>()
    var version: String? = "v1"
    val reorders = ArrayList<List<String>>()
    val searches = ArrayList<String>()

    override val displayName: String get() = serviceId
    override val brandColor: Long = 0xFF888888
    override val noteRes: Int = 0
    override val requiresAuth: Boolean = false
    override val searchParallelism: Int = 1
    override val canReorder: Boolean get() = true

    override fun isConfigured(ctx: Context) = true
    override fun isConnected(ctx: Context) = true
    override fun accountName(ctx: Context): String? = "tester"
    override fun startAuth(ctx: Context) {}
    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) {}
    override fun disconnect(ctx: Context) {}

    override suspend fun playlists(ctx: Context): List<Playlist> = lists.map { (id, t) -> Playlist(id, id, t.size) }
    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> =
        lists[playlistId]?.toList() ?: throw ProviderException("$serviceId: no playlist $playlistId")
    override suspend fun search(ctx: Context, track: Track): List<Track> {
        searches += track.title
        val wanted = Matcher.normalizeTitle(track.title)
        return catalogue.filter { Matcher.normalizeTitle(it.title) == wanted }
    }
    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        lists[name] = ArrayList()
        return Playlist(name, name, 0)
    }
    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        lists.getOrPut(playlistId) { ArrayList() } += tracks
    }
    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        val ids = tracks.map { it.id }.toSet()
        lists[playlistId]?.removeAll { it.id in ids }
    }
    override suspend fun playlistVersion(ctx: Context, playlistId: String): String? = version
    override suspend fun reorderTracks(ctx: Context, playlistId: String, current: List<Track>, ordered: List<Track>) {
        reorders += ordered.map { it.id }
        lists[playlistId] = ordered.toMutableList()
    }
}
