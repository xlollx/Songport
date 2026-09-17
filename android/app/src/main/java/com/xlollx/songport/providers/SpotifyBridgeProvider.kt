package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.R
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spotify through the companion app "Songport Bridge": the user signs in on Spotify's own page
 * inside the Bridge, no developer app needed. The Bridge hands over the web player's short-lived
 * Web API token, and everything else is the normal [SpotifyProvider] code.
 *
 * Not an official route: the token belongs to the web player, and Spotify's terms do not allow it.
 * The Bridge shows the notice before sign-in. The official path with your own client ID stays
 * available as [SpotifyProvider].
 */
class SpotifyBridgeProvider(slot: String = "") : SpotifyProvider(slot) {
    override val serviceId = SERVICE
    override val displayName = "Spotify (Bridge)"
    override val noteRes = R.string.provider_note_spotify_bridge
    override val supportsMultipleAccounts = false
    override val setupGuide: SetupGuide? = null
    override val installUrl = YouTubeBridgeProvider.INSTALL_URL
    override val notConfiguredRes = R.string.bridge_needed_hint

    override fun usesOwnCredentials(ctx: Context) = false
    override fun isConfigured(ctx: Context): Boolean =
        YouTubeBridgeProvider.installed(ctx) && runCatching { call(ctx, "spotify.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Context): Boolean =
        YouTubeBridgeProvider.installed(ctx) && runCatching { call(ctx, "spotify.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Context): String? = runCatching { call(ctx, "spotify.status").getString("account") }.getOrNull()

    override fun startAuth(ctx: Context) {
        if (!YouTubeBridgeProvider.installed(ctx)) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(installUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        ctx.startActivity(Intent(LOGIN_ACTION).setPackage(YouTubeBridgeProvider.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) { /* the Bridge completes the login itself */ }
    override fun disconnect(ctx: Context) { runCatching { call(ctx, "spotify.disconnect") } }

    /** The Bridge caches the token and renews it when the web player would. */
    override suspend fun tokens(ctx: Context): Tokens = withContext(Dispatchers.IO) {
        val b = call(ctx, "spotify.token")
        Tokens(
            accessToken = b.getString("token") ?: throw ProviderException("Spotify Bridge: no token"),
            expiresAt = b.getLong("expiresAt", 0),
            userId = b.getString("userId") ?: "",
            userName = b.getString("account") ?: "",
        )
    }

    // The 403 explanation of the official route (developer mode, own key) does not apply here.
    override fun friendlyApiError(ctx: Context, resp: HttpResponse): String? = null

    private fun call(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        if (!YouTubeBridgeProvider.installed(ctx)) throw ProviderException(ctx.getString(R.string.ytm_not_installed))
        val b = try {
            ctx.contentResolver.call(AUTHORITY, method, arg, extras)
        } catch (e: Exception) {
            throw ProviderException("Songport Bridge: ${e.message ?: e.javaClass.simpleName}")
        } ?: throw ProviderException("Songport Bridge: no answer")
        b.getString("error")?.let { throw ProviderException("Spotify (Bridge): $it") }
        return b
    }

    companion object {
        const val SERVICE = "spotify_bridge"
        const val LOGIN_ACTION = "com.xlollx.songport.ytmbridge.SPOTIFY_LOGIN"
        private val AUTHORITY: Uri = Uri.parse("content://com.xlollx.songport.ytmbridge.provider")
    }
}
