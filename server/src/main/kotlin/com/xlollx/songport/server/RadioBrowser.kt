package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Finding a station by name in radio-browser.info, the free, community-run directory of some
 * 50,000 internet radios with their stream addresses (no key, no account). Its servers are mirrors:
 * the next one is tried when one does not answer.
 */
object RadioBrowser {
    data class Station(val name: String, val stream: String, val homepage: String, val country: String, val codec: String, val bitrate: Int, val favicon: String)

    private val MIRRORS = listOf("de1", "fi1", "nl1", "de2")
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    fun search(name: String, limit: Int = 20): List<Station> {
        val q = URLEncoder.encode(name.trim(), Charsets.UTF_8)
        var last: Exception? = null
        for (m in MIRRORS) {
            try {
                val req = HttpRequest.newBuilder(URI("https://$m.api.radio-browser.info/json/stations/search?name=$q&limit=$limit&hidebroken=true&order=votes&reverse=true"))
                    .timeout(Duration.ofSeconds(12)).header("User-Agent", "Trackhop").build()
                val body = client.send(req, HttpResponse.BodyHandlers.ofString()).body()
                return Json.parseToJsonElement(body).jsonArray.mapNotNull { e ->
                    val o = e.jsonObject
                    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                    val stream = s("url_resolved").ifEmpty { s("url") }
                    if (stream.isEmpty()) null
                    else Station(s("name"), stream, s("homepage"), s("country"), s("codec"), s("bitrate").toIntOrNull() ?: 0, s("favicon"))
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("The radio directory did not answer: ${last?.message}")
    }
}
