package com.xlollx.songport.server

import com.xlollx.songport.sync.RadioDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Finding a station by name in radio-browser.info, the free, community-run directory of some
 * 50,000 internet radios with their stream addresses (no key, no account). Its servers come and
 * go: the live ones are those [RadioDirectory.servers] finds, asked in turn until one answers.
 */
object RadioBrowser {
    data class Station(val name: String, val stream: String, val homepage: String, val country: String, val codec: String, val bitrate: Int, val favicon: String)

    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    fun search(name: String, limit: Int = 20): List<Station> {
        val q = URLEncoder.encode(name.trim(), Charsets.UTF_8)
        val hosts = RadioDirectory.servers { h -> InetAddress.getAllByName(h).map { it.hostAddress.orEmpty() } }
        if (hosts.isEmpty()) throw IllegalStateException(UNAVAILABLE)
        for (host in hosts) {
            try {
                val req = HttpRequest.newBuilder(URI("https://$host/json/stations/search?name=$q&limit=$limit&hidebroken=true&order=votes&reverse=true"))
                    .timeout(Duration.ofSeconds(12)).header("User-Agent", "Trackhop").build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) continue
                return Json.parseToJsonElement(resp.body()).jsonArray.mapNotNull { e ->
                    val o = e.jsonObject
                    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val stream = s("url_resolved").ifEmpty { s("url") }
                    if (stream.isEmpty()) null
                    else Station(s("name"), stream, s("homepage"), s("country"), s("codec"), s("bitrate").toIntOrNull() ?: 0, s("favicon"))
                }
            } catch (_: Exception) {
            }
        }
        throw IllegalStateException(UNAVAILABLE)
    }

    private const val UNAVAILABLE = "The station directory (radio-browser.info) is not answering right now. Try again in a while, or paste the station's stream address below."
}
