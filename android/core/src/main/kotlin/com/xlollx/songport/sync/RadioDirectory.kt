package com.xlollx.songport.sync

import com.xlollx.songport.net.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Finding a station by name in radio-browser.info, the free, community-run directory of some
 * 50,000 internet radios with their stream addresses (no key, no account). Its servers are mirrors:
 * the next one is tried when one does not answer. The same search as the server's.
 */
object RadioDirectory {
    data class Station(val name: String, val stream: String, val homepage: String, val country: String, val codec: String, val bitrate: Int)

    private val MIRRORS = listOf("de1", "fi1", "nl1", "de2")

    /** The stations whose name holds [name], the most voted first. Throws when no mirror answers. */
    suspend fun search(name: String, limit: Int = 20): List<Station> {
        val q = Http.enc(name.trim())
        var last: Exception? = null
        for (m in MIRRORS) {
            try {
                val r = Http.send(
                    "GET", "https://$m.api.radio-browser.info/json/stations/search?name=$q&limit=$limit&hidebroken=true&order=votes&reverse=true",
                    headers = mapOf("User-Agent" to "Trackhop"), maxRateLimitRetries = 1,
                )
                if (!r.ok) { last = IllegalStateException("HTTP ${r.code}"); continue }
                return parse(r.body)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("The radio directory did not answer: ${last?.message}")
    }

    /** The directory's answer as stations: those without a stream address are left out. */
    fun parse(body: String): List<Station> =
        (com.xlollx.songport.net.json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
            val stream = s("url_resolved").ifEmpty { s("url") }
            if (stream.isEmpty() || !(stream.startsWith("http://") || stream.startsWith("https://"))) null
            else Station(s("name"), stream, s("homepage"), s("country"), s("codec"), s("bitrate").toIntOrNull() ?: 0)
        }
}
