package com.xlollx.songport.ytmbridge

import android.content.Context
import android.webkit.WebSettings

/**
 * The desktop browser identity the web connectors use: Chrome on Windows, at the version of this
 * phone's own WebView. A fixed version ages, and the players check it ("Update Chrome" on Amazon,
 * "unsupported browser" on Spotify); the WebView's version is the one Google ships to this phone.
 */
object BridgeUa {
    const val FALLBACK = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    @Volatile private var cached: String? = null

    fun desktop(ctx: Context): String = cached ?: runCatching {
        val own = WebSettings.getDefaultUserAgent(ctx.applicationContext)
        val ver = Regex("""Chrome/(\d+(?:\.\d+)*)""").find(own)?.groupValues?.get(1) ?: return@runCatching FALLBACK
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }.getOrDefault(FALLBACK).also { cached = it }
}
