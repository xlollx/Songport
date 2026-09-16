package com.xlollx.songport.sync

/**
 * Riconosce i link delle playlist pubbliche e li traduce in (servizio, id), cosi' si puo'
 * sincronizzare anche una playlist che non e' nostra incollandone l'indirizzo.
 * Kotlin puro: coperto dai test.
 */
object PlaylistLinks {

    data class Ref(val providerId: String, val playlistId: String)

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Spotify: https://open.spotify.com/playlist/37i9dQ… oppure spotify:playlist:37i9dQ…
        Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?playlist/|spotify:playlist:)([A-Za-z0-9]+)""") to "spotify",
        // Apple Music: https://music.apple.com/it/playlist/nome/pl.u-xxxx
        Regex("""music\.apple\.com/[^/]+/playlist/[^/]*/?(pl\.[A-Za-z0-9-]+)""") to "apple",
        Regex("""music\.apple\.com/[^/]+/playlist/(pl\.[A-Za-z0-9-]+)""") to "apple",
        // YouTube / YouTube Music: ?list=PL…
        Regex("""(?:music\.)?youtube\.com/[^\s]*[?&]list=([A-Za-z0-9_-]+)""") to "youtube",
        Regex("""youtu\.be/[^\s]*[?&]list=([A-Za-z0-9_-]+)""") to "youtube",
        // Deezer: https://www.deezer.com/it/playlist/1234567
        Regex("""deezer\.com/(?:[a-z]{2}/)?playlist/(\d+)""") to "deezer",
        Regex("""deezer\.page\.link/playlist/(\d+)""") to "deezer",
        // TIDAL: https://tidal.com/browse/playlist/uuid
        Regex("""tidal\.com/(?:browse/)?playlist/([0-9a-fA-F-]{36})""") to "tidal",
    )

    /** @return il riferimento alla playlist, oppure null se il testo non e' un link riconosciuto. */
    fun parse(text: String): Ref? {
        val s = text.trim()
        if (s.isEmpty()) return null
        for ((regex, provider) in RULES) {
            val m = regex.find(s) ?: continue
            val id = m.groupValues[1]
            if (id.isNotBlank()) return Ref(provider, id)
        }
        return null
    }
}
