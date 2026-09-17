package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.xlollx.songport.BuildConfig

/**
 * Discovery of a "connector plugin": a separately installed app that implements Songport's open
 * connector interface (a ContentProvider answering `call()` methods, plus login activities).
 *
 * The plugin advertises itself with an activity handling [ACTION] and a `<meta-data>` entry naming
 * its provider authority. Songport never bundles, downloads or installs a plugin: it only uses one
 * the user has already installed. The direct-download build may show where to get one; the Google
 * Play build does not link to anything and simply offers the services when a plugin is present.
 */
object BridgePlugin {
    const val ACTION = "com.xlollx.songport.action.CONNECTOR"
    private const val META_AUTHORITY = "com.xlollx.songport.connector.authority"

    // Known implementation for plugin versions that predate the discovery action (full build only).
    private const val LEGACY_PACKAGE = "com.xlollx.songport.ytmbridge"
    private const val LEGACY_AUTHORITY = "com.xlollx.songport.ytmbridge.provider"

    /** Where to get a plugin, shown only in the direct-download build. */
    val installUrl: String? = if (BuildConfig.PLUGIN_LINKS) "https://github.com/xlollx/Songport-YTM-Bridge/releases/latest" else null

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

    fun installed(ctx: Context): Boolean = find(ctx) != null
    fun packageName(ctx: Context): String? = find(ctx)?.packageName
    fun authority(ctx: Context): Uri = find(ctx)?.authority ?: Uri.parse("content://$LEGACY_AUTHORITY")
}
