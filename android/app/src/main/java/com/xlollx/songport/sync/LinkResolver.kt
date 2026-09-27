package com.xlollx.songport.sync

import com.xlollx.songport.net.Http

/**
 * A pasted link to a playlist reference. Plain links are read as they are; a short share link
 * (open.spotify.com/s/…, spotify.link, deezer.page.link, link.tidal.com…) is followed to where it
 * lands, and failing that the page there is searched for the playlist's address.
 */
object LinkResolver {
    private val URL = Regex("""https?://[^\s<>"']+""")

    suspend fun resolve(text: String): PlaylistLinks.Ref? {
        PlaylistLinks.parse(text)?.let { return it }
        val url = URL.find(text)?.value ?: return null
        val (landed, page) = runCatching { Http.follow(url) }.getOrElse { return null }
        PlaylistLinks.parse(landed)?.let { return it }
        return URL.findAll(page).mapNotNull { PlaylistLinks.parse(it.value) }.firstOrNull()
            ?: Regex("""spotify:playlist:[A-Za-z0-9]+""").find(page)?.value?.let { PlaylistLinks.parse(it) }
    }
}
