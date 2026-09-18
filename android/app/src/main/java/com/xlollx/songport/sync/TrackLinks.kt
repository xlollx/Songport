package com.xlollx.songport.sync

/**
 * Riconosce il link di un singolo brano e lo traduce in (servizio, id): nella revisione dei non
 * trovati si puo' incollare l'indirizzo del brano giusto invece di cercarlo a parole.
 * Kotlin puro: coperto dai test.
 */
object TrackLinks {

    data class Ref(val service: String, val trackId: String)

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Spotify: https://open.spotify.com/track/4uLU6… oppure spotify:track:4uLU6…
        Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?track/|spotify:track:)([A-Za-z0-9]+)""") to "spotify",
        // Apple Music: https://music.apple.com/it/album/nome/123456?i=654321 (il brano e' "i"),
        // oppure https://music.apple.com/it/song/nome/654321
        Regex("""music\.apple\.com/[^\s]*[?&]i=(\d+)""") to "apple",
        Regex("""music\.apple\.com/[^/]+/song/(?:[^/?\s]*/)?(\d+)""") to "apple",
        // YouTube / YouTube Music: watch?v=ID, youtu.be/ID
        Regex("""(?:music\.)?youtube\.com/[^\s]*[?&]v=([A-Za-z0-9_-]{11})""") to "youtube",
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})""") to "youtube",
        // Amazon Music: https://music.amazon.it/tracks/B0ABC…, oppure albums/B0…?trackAsin=B0…
        Regex("""music\.amazon\.[a-z.]+/[^\s]*[?&]trackAsin=([A-Z0-9]{10})(?![A-Z0-9])""") to "amazon",
        Regex("""music\.amazon\.[a-z.]+/tracks/([A-Z0-9]{10})(?![A-Z0-9])""") to "amazon",
        // Deezer: https://www.deezer.com/it/track/1234567
        Regex("""deezer\.com/(?:[a-z]{2}/)?track/(\d+)""") to "deezer",
        // TIDAL: https://tidal.com/browse/track/1234567
        Regex("""tidal\.com/(?:browse/)?track/(\d+)""") to "tidal",
    )

    /** @return il riferimento al brano, oppure null se il testo non e' un link riconosciuto. */
    fun parse(text: String): Ref? {
        val s = text.trim()
        if (s.isEmpty()) return null
        for ((regex, service) in RULES) {
            val m = regex.find(s) ?: continue
            val id = m.groupValues[1]
            if (id.isNotBlank()) return Ref(service, id)
        }
        return null
    }

    /** True se un link di [service] riguarda il servizio [serviceId] di un connettore (anche via Bridge). */
    fun matches(service: String, serviceId: String): Boolean = when (service) {
        "spotify" -> serviceId == "spotify" || serviceId == "spotify_bridge"
        "apple" -> serviceId == "apple" || serviceId == "apple_bridge"
        "youtube" -> serviceId == "youtube" || serviceId == "ytm"
        else -> serviceId == service
    }
}
