package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Amazon Music through the companion app "Songport Bridge", experimental.
 *
 * Amazon has no open API and its official one is reserved to approved partners, so the Bridge uses
 * the web player's interface, as it does for YouTube Music. Search and reading a playlist by id work;
 * listing the library and writing (create, add, remove) are still being mapped, and the Bridge
 * answers those with a clear "not available yet" until then.
 */
class AmazonBridgeProvider(override val slot: String = "") : MusicProvider {
    override val serviceId = SERVICE
    override val displayName = "Amazon Music"
    override val brandColor = 0xFF1AB0D6
    override val noteRes = R.string.provider_note_amazon
    override val supportsMultipleAccounts = false
    override val authDomain = "amazon.com"
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredRes = R.string.amazon_not_installed_hint
    override val searchParallelism = 1
    override val pluginBased = true

    /** "Configured" means a Bridge new enough to know Amazon Music is installed. */
    override fun isConfigured(ctx: Context): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "amazon.status").containsKey("connected") }.getOrDefault(false)

    override fun isConnected(ctx: Context): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "amazon.status").getBoolean("connected", false) }.getOrDefault(false)

    override fun accountName(ctx: Context): String? =
        runCatching { call(ctx, "amazon.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Context) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION)
    }

    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }

    override fun disconnect(ctx: Context) { runCatching { call(ctx, "amazon.disconnect") } }

    override suspend fun playlists(ctx: Context): List<Playlist> = io(ctx, "amazon.playlists") { j ->
        j.arr.mapNotNull { p -> Playlist(p["id"].str ?: return@mapNotNull null, p["name"].str ?: "", p["count"]?.long?.toInt() ?: -1) }
    }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> =
        io(ctx, "amazon.tracks", playlistId) { j -> j.arr.mapNotNull { toTrack(it) } }

    override suspend fun search(ctx: Context, track: Track): List<Track> {
        val q = (track.artists.take(2) + track.title).joinToString(" ")
        return io(ctx, "amazon.search", q) { j -> j.arr.mapNotNull { toTrack(it) } }
    }

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist =
        io(ctx, "amazon.create", null, Bundle().apply { putString("name", name) }) { j ->
            Playlist(j["id"].str ?: throw ProviderException("Amazon Music: playlist not created"), name, 0)
        }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            // One web call per track: keep the batches small so a failure is reported precisely.
            tracks.chunked(25).forEach { chunk ->
                call(ctx, "amazon.add", playlistId, Bundle().apply {
                    putStringArray("ids", chunk.map { it.id }.toTypedArray())
                    putStringArray("titles", chunk.map { it.title }.toTypedArray())
                })
            }
        }
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withContext(Dispatchers.IO) {
            call(ctx, "amazon.remove", playlistId, Bundle().apply {
                putStringArray("ids", tracks.map { it.id }.toTypedArray())
                putStringArray("entryIds", tracks.map { it.itemId ?: "" }.toTypedArray())
            })
        }
    }

    private fun toTrack(j: JsonElement?): Track? {
        val id = j["id"].str ?: return null
        return Track(
            id = id,
            title = j["title"].str ?: return null,
            artists = j["artists"].arr.mapNotNull { it.str },
            album = j["album"].str ?: "",
            durationMs = j["durationMs"].long ?: 0,
            itemId = j["setVideoId"].str,
        )
    }

    private suspend fun <T> io(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null, map: (JsonElement?) -> T): T =
        withContext(Dispatchers.IO) { map(parseJson(call(ctx, method, arg, extras).getString("json") ?: "")) }

    private fun call(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.getString(R.string.ytm_not_installed))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras)
        } catch (e: Exception) {
            throw ProviderException("Songport Bridge: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Songport Bridge: no answer")
        b.getString("error")?.let { throw ProviderException("Amazon Music: $it") }
        return b
    }

    companion object {
        const val SERVICE = "amazon"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.AMAZON_LOGIN"
    }
}
