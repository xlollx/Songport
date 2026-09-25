package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.model.ProviderException

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
    override fun developerToken(ctx: Context): String = runCatching { tokens(ctx).getString("developerToken") }.getOrNull() ?: ""
    override fun userToken(ctx: Context): String? = runCatching { tokens(ctx).getString("userToken") }.getOrNull()
    override fun storefront(ctx: Context): String = runCatching { tokens(ctx).getString("storefront") }.getOrNull() ?: super.storefront(ctx)

    override fun isConfigured(ctx: Context): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "apple.status").containsKey("connected") }.getOrDefault(false)
    override fun isConnected(ctx: Context): Boolean =
        BridgePlugin.installed(ctx) && runCatching { call(ctx, "apple.status").getBoolean("connected", false) }.getOrDefault(false)
    override fun accountName(ctx: Context): String? = runCatching { call(ctx, "apple.status").getString("account") }.getOrNull()
    override fun canRead(ctx: Context, playlistId: String): Boolean = isConnected(ctx)

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
