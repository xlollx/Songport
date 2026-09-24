package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.BuildConfig

/**
 * Discovery of a "connector plugin": a separately installed app that implements Songport's open
 * connector interface (a ContentProvider answering `call()` methods, plus login activities).
 *
 * The plugin advertises itself with an activity handling [ACTION] and a `<meta-data>` entry naming
 * its provider authority. Songport never bundles, downloads or installs a plugin: it only uses one
 * the user has already installed. The direct-download build may show where to get one; the Google
 * Play build does not link to anything and simply offers the services when a plugin is present.
 *
 * The GitHub and F-Droid build carries the same connectors inside ([BuiltIn]): every call and
 * sign-in then stays in this app, and an installed plugin is not used.
 */
object BridgePlugin {
    const val ACTION = "com.xlollx.songport.action.CONNECTOR"
    private const val META_AUTHORITY = "com.xlollx.songport.connector.authority"

    // Known implementation for plugin versions that predate the discovery action (full build only).
    private const val LEGACY_PACKAGE = "com.xlollx.songport.ytmbridge"
    private const val LEGACY_AUTHORITY = "com.xlollx.songport.ytmbridge.provider"

    /** True when the connectors are built into this app (GitHub and F-Droid build). */
    val builtIn: Boolean get() = BuiltIn.available

    /** Where to get a plugin: nowhere to go when the connectors are built in, and never in the Play build. */
    val installUrl: String? = if (BuildConfig.PLUGIN_LINKS && !BuiltIn.available) "https://github.com/xlollx/Songport-YTM-Bridge/releases/latest" else null

    class Info(val packageName: String, val authority: Uri)

    @Volatile private var cache: Pair<Long, Info?>? = null

    fun find(ctx: Context): Info? {
        cache?.let { (at, info) -> if (System.currentTimeMillis() - at < 10_000) return info }
        val pm = ctx.packageManager
        val ri = runCatching { pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA) }.getOrNull()?.firstOrNull()
        val info = if (ri != null) {
            val authority = ri.activityInfo.metaData?.getString(META_AUTHORITY) ?: LEGACY_AUTHORITY
            Info(ri.activityInfo.packageName, Uri.parse("content://$authority"))
        } else if (BuildConfig.PLUGIN_LINKS && runCatching { pm.getPackageInfo(LEGACY_PACKAGE, 0) }.isSuccess) {
            Info(LEGACY_PACKAGE, Uri.parse("content://$LEGACY_AUTHORITY"))
        } else null
        cache = System.currentTimeMillis() to info
        return info
    }

    fun installed(ctx: Context): Boolean = builtIn || find(ctx) != null
    fun packageName(ctx: Context): String? = if (builtIn) ctx.packageName else find(ctx)?.packageName
    fun authority(ctx: Context): Uri = find(ctx)?.authority ?: Uri.parse("content://$LEGACY_AUTHORITY")

    /** One call to the connectors: in-process when built in, otherwise through the plugin's provider. */
    fun call(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
        if (builtIn) BuiltIn.call(ctx, method, arg, extras)
        else ctx.contentResolver.call(authority(ctx), method, arg, extras)

    /** Opens a connector's sign-in: the built-in screen, or the plugin's through its action. */
    fun startLogin(ctx: Context, action: String) {
        val intent = BuiltIn.loginIntent(ctx, action) ?: Intent(action).setPackage(packageName(ctx) ?: return)
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
