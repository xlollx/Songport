package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * YouTube Music through the optional companion app "Songport YTM Bridge".
 *
 * Songport itself never talks to YouTube here: it asks the Bridge, installed separately (not from
 * Google Play), through a ContentProvider. The Bridge holds the login and answers with playlists and
 * tracks. No Google Cloud project, no quota. The official Data API route stays available as
 * [YouTubeProvider] for whoever prefers it.
 */
class YouTubeBridgeProvider(override val slot: String = "") : MusicProvider {
    override val canRenamePlaylists: Boolean get() = BridgePlugin.builtIn
    override val canDeletePlaylists: Boolean get() = BridgePlugin.builtIn
    override val serviceId = SERVICE
    override val displayName = "YouTube Music"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteRes: Int? get() = R.string.route_note_web
    override val familyName: String get() = "YouTube Music"
    override val brandColor = 0xFFFF0000
    override val noteRes = R.string.provider_note_ytm
    override val supportsLikedSongs = true
    override val supportsLikedTarget = true
    /** Saved albums and subscribed artists: the built-in connectors know them, an old plugin may not. */
    override val supportsAlbums: Boolean get() = BridgePlugin.builtIn
    override val supportsArtists: Boolean get() = BridgePlugin.builtIn
    override val supportsRecent: Boolean get() = BridgePlugin.builtIn
    override val supportsMultipleAccounts = false
    override val authDomain = "accounts.google.com"
    override val revokeUrl = "https://myaccount.google.com/device-activity"
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredRes = R.string.ytm_not_installed_hint
    override val pluginBased = true
    // The web interface answers 403 to bursts of searches: one at a time, paced by the Bridge.
    // Due alla volta: Google segna l'indirizzo quando il ritmo non e' quello di una persona, e il
    // Bridge impone comunque una cadenza minima; oltre due non si guadagna nulla e si rischia il blocco.
    override val searchParallelism = 2

    /** "Configured" here means the Bridge app is installed. */
    override fun isConfigured(ctx: Context): Boolean = installed(ctx)

    override fun isConnected(ctx: Context): Boolean =
        installed(ctx) && runCatching { call(ctx, "status").getBoolean("connected", false) }.getOrDefault(false)

    override fun accountName(ctx: Context): String? =
        runCatching { call(ctx, "status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Context) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION)
    }

    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }

    override fun disconnect(ctx: Context) { runCatching { call(ctx, "disconnect") } }

    override suspend fun playlists(ctx: Context): List<Playlist> = io(ctx, "playlists") { j ->
        j.arr.mapNotNull { p ->
            val id = p["id"].str ?: return@mapNotNull null
            Playlist(id, p["name"].str ?: id, p["count"].int ?: -1)
        }
    }

    override suspend fun playlistInfo(ctx: Context, playlistId: String): Playlist = io(ctx, "playlistInfo", playlistId) { j ->
        Playlist(playlistId, j["name"].str ?: playlistId, j["count"].int ?: -1, ownedByMe = false)
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        var offset: Int? = 0
        while (offset != null) {
            val b = call(ctx, "tracks", playlistId, Bundle().apply { putInt("offset", offset!!) })
            out += parseJson(b.getString("json") ?: "[]").arr.mapNotNull { toTrack(it) }
            offset = if (b.containsKey("next")) b.getInt("next") else null
        }
        out
    }

    override fun webSearchUrl(ctx: Context, query: String): String = "https://music.youtube.com/search?q=" + android.net.Uri.encode(query)

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        val kind = when (track.kind) { Track.KIND_ALBUM -> "album"; Track.KIND_ARTIST -> "artist"; Track.KIND_PODCAST -> return emptyList(); else -> null }
        val q = if (kind == "artist") track.title else (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx, "search", q, Bundle().apply { kind?.let { putString("kind", it) } }) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    /** YouTube videos, for a song the music catalogue does not have (an old Bridge ignores the flag and repeats the songs). */
    override suspend fun searchWide(ctx: Context, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx, "search", q, Bundle().apply { putBoolean("videos", true) }) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    /** Titolo e canale da oEmbed di YouTube: pubblico, senza quota e senza passare dal Bridge. */
    override suspend fun track(ctx: Context, trackId: String): Track? {
        val r = com.xlollx.songport.net.Http.send("GET", "https://www.youtube.com/oembed?format=json&url=" +
            com.xlollx.songport.net.Http.enc("https://www.youtube.com/watch?v=$trackId"))
        if (!r.ok) return null
        val j = parseJson(r.body)
        val (title, artists) = com.xlollx.songport.sync.TitleParser.parseYouTube(j["title"].str ?: return null, j["author_name"].str)
        return Track(id = trackId, title = title, artists = artists)
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist =
        io(ctx, "create", null, Bundle().apply { putString("name", name); putString("description", description) }) { j ->
            Playlist(j["id"].str ?: throw ProviderException("YouTube Music: playlist not created"), name, 0)
        }

    override suspend fun renamePlaylist(ctx: Context, playlistId: String, name: String) {
        withContext(Dispatchers.IO) { call(ctx, "rename", playlistId, Bundle().apply { putString("name", name) }) }
    }

    override suspend fun deletePlaylist(ctx: Context, playlistId: String) {
        withContext(Dispatchers.IO) { call(ctx, "delete", playlistId) }
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            call(ctx, "add", playlistId, Bundle().apply {
                putStringArray("ids", tracks.map { it.id }.toTypedArray())
                // Albums are liked through their playlist id, which the row carried as uri when it had one.
                if (playlistId == MusicProvider.ALBUMS_ID) putStringArray("uris", tracks.map { it.uri ?: "" }.toTypedArray())
            })
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (playlistId == MusicProvider.ALBUMS_ID || playlistId == MusicProvider.ARTISTS_ID) {
            withContext(Dispatchers.IO) {
                call(ctx, "remove", playlistId, Bundle().apply {
                    putStringArray("ids", tracks.map { it.id }.toTypedArray())
                    putStringArray("uris", tracks.map { it.uri ?: "" }.toTypedArray())
                })
            }
            return
        }
        val items = tracks.joinToString(",", "[", "]") { t ->
            """{"videoId":${quote(t.id)}${t.itemId?.let { ",\"setVideoId\":${quote(it)}" } ?: ""}}"""
        }
        withContext(Dispatchers.IO) { call(ctx, "remove", playlistId, Bundle().apply { putString("json", items) }) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
            itemId = j["setVideoId"].str,
            uri = j["uri"].str,
            kind = when (j["kind"].str) { "album" -> Track.KIND_ALBUM; "artist" -> Track.KIND_ARTIST; else -> "" },
            year = j["year"].long?.toInt() ?: 0,
        )
    }

    private suspend fun <T> io(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!installed(ctx)) throw ProviderException(ctx.getString(R.string.ytm_not_installed))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras)
        } catch (e: Exception) {
            throw ProviderException("YouTube Music Bridge: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("YouTube Music Bridge: no answer")
        b.getString("error")?.let { throw ProviderException("YouTube Music: $it") }
        return b
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        const val SERVICE = "ytm"
        const val PACKAGE = "com.xlollx.songport.ytmbridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.LOGIN"
        fun installed(ctx: Context): Boolean = BridgePlugin.installed(ctx)
    }
}
