package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.net.Http

/**
 * A pasted link to a playlist reference. Plain links are read as they are; a short share link
 * (open.spotify.com/s/…, spotify.link, deezer.page.link, link.tidal.com…) is followed to where it
 * lands, and failing that the page there is searched for the playlist's address. Each step is
 * written to the connections log, so a link that will not resolve can be looked into.
 */
object LinkResolver {
    private val URL = Regex("""https?://[^\s<>"'\\]+""")
    private val ESCAPED = Regex("""https?:\\/\\/[^\s<>"']+""")
    private val URI = Regex("""spotify:playlist:[A-Za-z0-9]+""")

    suspend fun resolve(ctx: Context, text: String): PlaylistLinks.Ref? {
        PlaylistLinks.parse(text)?.let { return it }
        val url = URL.find(text)?.value ?: return null
        for (ua in listOf(Http.DESKTOP_UA, Http.MOBILE_UA)) {
            val attempt = runCatching { Http.follow(url, ua) }
            val landing = attempt.getOrNull()
            if (landing == null) {
                Diagnostics.log(ctx, "link", "follow failed for $url: ${attempt.exceptionOrNull()?.message ?: "error"}")
                continue
            }
            Diagnostics.log(ctx, "link", "$url -> HTTP ${landing.code} ${landing.url.take(160)} (${landing.page.length} chars)")
            PlaylistLinks.parse(landing.url)?.let { return it }
            // The page itself: a canonical/og:url link, a JSON-escaped address, or a spotify: uri.
            val inPage = URL.findAll(landing.page).mapNotNull { PlaylistLinks.parse(it.value) }.firstOrNull()
                ?: ESCAPED.findAll(landing.page).mapNotNull { PlaylistLinks.parse(it.value.replace("\\/", "/")) }.firstOrNull()
                ?: URI.find(landing.page)?.value?.let { PlaylistLinks.parse(it) }
            if (inPage != null) return inPage
            Diagnostics.log(ctx, "link", "no playlist in the page: " + landing.page.replace(Regex("\\s+"), " ").take(300))
        }
        return null
    }
}
