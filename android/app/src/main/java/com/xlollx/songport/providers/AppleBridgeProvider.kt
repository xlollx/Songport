package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.get
import com.xlollx.songport.net.str

/**
 * Apple Music through the companion app "Songport Bridge": the user signs in with their Apple ID on
 * music.apple.com inside the Bridge, no Apple Developer Program needed. The Bridge hands over the
 * web player's developer token and the music user token, and everything else is the normal
 * [AppleMusicProvider] code.
 *
 * Not an official route: those tokens belong to the web player. The Bridge shows the notice before
 * sign-in. The official path with your own developer token stays available as [AppleMusicProvider].
 */
class AppleBridgeProvider(slot: String = "") : AppleMusicProvider(slot) {
    override val serviceId = SERVICE
    override val displayName = if (BridgePlugin.builtIn) "Apple Music (web)" else "Apple Music (plugin)"
    override val route: MusicProvider.Route? get() = MusicProvider.Route.EASY
    override val routeNoteRes: Int? get() = R.string.route_note_web
    override val familyName: String get() = "Apple Music"
    override val noteRes = R.string.provider_note_apple_bridge
    override val supportsMultipleAccounts = false
    override val pluginBased = true
    override val setupGuide: SetupGuide? = null
    override val installUrl = BridgePlugin.installUrl
    override val notConfiguredRes = R.string.bridge_needed_hint

    // Tokens come from the Bridge; cached briefly so a playlist listing does not call it per request.
    @Volatile private var cache: Pair<Long, Bundle>? = null

    private fun tokens(ctx: Context): Bundle {
        val now = System.currentTimeMillis()
        cache?.let { (at, b) -> if (now - at < 5 * 60_000) return b }
        val b = call(ctx, "apple.tokens")
        cache = now to b
        return b
    }

    override fun usesOwnCredentials(ctx: Context) = false
    // The Bridge's own reason (not signed in, token not found) is more useful than "no developer token".
    override fun developerToken(ctx: Context): String =
        runCatching { tokens(ctx) }.getOrElse { throw ProviderException(it.message ?: "$displayName: no tokens") }.getString("developerToken") ?: ""
    override fun userToken(ctx: Context): String? = runCatching { tokens(ctx).getString("userToken") }.getOrNull()
    override fun storefront(ctx: Context): String = runCatching { tokens(ctx).getString("storefront") }.getOrNull() ?: super.storefront(ctx)

    override fun isConfigured(ctx: Context): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "apple.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Context): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "apple.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Context): String? = runCatching { call(ctx, "apple.status").getString("account") }.getOrNull()
    override fun canRead(ctx: Context, playlistId: String): Boolean = isConnected(ctx)

    // The web player's backend (amp-api.music.apple.com) takes the same two tokens and, unlike the
    // public API, removes tracks, renames and deletes library playlists: what music.apple.com does.
    override val canRemoveTracks: Boolean get() = true
    override val canRenamePlaylists: Boolean get() = true
    override val canDeletePlaylists: Boolean get() = true

    private suspend fun amp(ctx: Context, method: String, path: String, body: kotlinx.serialization.json.JsonElement? = null) {
        val dev = developerToken(ctx)
        val user = userToken(ctx)
        if (dev.isBlank() || user == null) throw ProviderException(ctx.getString(R.string.error_not_connected, displayName))
        val headers = mapOf(
            "Authorization" to "Bearer $dev", "Music-User-Token" to user, "Accept" to "application/json",
            "Origin" to "https://music.apple.com", "Referer" to "https://music.apple.com/",
        )
        val resp = com.xlollx.songport.net.Http.send(method, "https://amp-api.music.apple.com$path", headers, body?.let { com.xlollx.songport.net.Http.jsonBody(it.toString()) })
        if (!resp.ok) {
            val detail = com.xlollx.songport.net.parseJson(resp.body)["errors"][0]["detail"].str ?: resp.body.take(200)
            if (resp.code == 403) throw ProviderException(ctx.getString(R.string.apple_subscription_needed, displayName, detail))
            throw ProviderException("$displayName ${resp.code}: $detail")
        }
    }

    override suspend fun renamePlaylist(ctx: Context, playlistId: String, name: String) {
        amp(ctx, "PATCH", "/v1/me/library/playlists/$playlistId", com.xlollx.songport.net.jsonObj("attributes" to mapOf("name" to name)))
    }

    override val canSetVisibility: Boolean get() = true
    override suspend fun setPlaylistVisibility(ctx: Context, playlistId: String, public: Boolean) {
        amp(ctx, "PATCH", "/v1/me/library/playlists/$playlistId", com.xlollx.songport.net.jsonObj("attributes" to mapOf("isPublic" to public)))
    }

    override suspend fun deletePlaylist(ctx: Context, playlistId: String) {
        amp(ctx, "DELETE", "/v1/me/library/playlists/$playlistId")
    }

    /** Entries go by their library id (i.xxx), which the playlist listing carries as itemId. */
    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val missing = tracks.filter { it.itemId == null }.map { it.id }.toSet()
        val byCatalog = if (missing.isNotEmpty()) tracks(ctx, playlistId).filter { it.id in missing }.groupBy { it.id } else emptyMap()
        val libIds = tracks.flatMap { t -> if (t.itemId != null) listOf(t.itemId) else byCatalog[t.id].orEmpty().mapNotNull { it.itemId } }.distinct()
        for (lib in libIds) amp(ctx, "DELETE", "/v1/me/library/playlists/$playlistId/tracks?ids[library-songs]=${Http.enc(lib)}&mode=all")
    }

    override fun startAuth(ctx: Context) {
        val pkg = BridgePlugin.packageName(ctx)
        if (pkg == null) {
            installUrl?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        BridgePlugin.startLogin(ctx, LOGIN_ACTION)
    }

    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }
    override fun disconnect(ctx: Context) { cache = null; runCatching { call(ctx, "apple.disconnect") } }

    private fun call(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!BridgePlugin.installed(ctx)) throw ProviderException(ctx.getString(R.string.ytm_not_installed))
        val b = try {
            BridgePlugin.call(ctx, method, arg, extras)
        } catch (e: Exception) {
            throw ProviderException("Songport Bridge: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Songport Bridge: no answer")
        b.getString("error")?.let { throw ProviderException("Apple Music (Bridge): $it") }
        return b
    }

    companion object {
        const val SERVICE = "apple_bridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.APPLE_LOGIN"
    }
}
