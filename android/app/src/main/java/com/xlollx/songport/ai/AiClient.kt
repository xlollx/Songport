package com.xlollx.songport.ai

import android.content.Context
import com.xlollx.songport.data.TokenStore
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.PlaylistFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Generatore di playlist con l'AI dell'utente. Songport non ha un'AI propria: l'utente porta la
 * chiave del suo servizio (OpenAI, Anthropic, Google Gemini o qualunque endpoint compatibile con
 * l'API di OpenAI, anche locale) e l'app gli manda solo la descrizione scritta qui. La risposta
 * e' una lista "artista - titolo" che poi passa dalla normale ricerca sul servizio di destinazione,
 * quindi errori e allucinazioni finiscono fra i "non trovati", da rivedere come sempre.
 */
object AiClient {

    enum class Vendor(val label: String, val defaultBase: String, val keyUrl: String) {
        OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys"),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey"),
        /** Ollama, LM Studio, OpenRouter, Mistral, Groq…: tutti parlano l'API chat di OpenAI. */
        CUSTOM("OpenAI-compatible", "", ""),
    }

    data class Config(val vendor: Vendor, val apiKey: String, val model: String, val baseUrl: String) {
        val base: String get() = (baseUrl.ifBlank { vendor.defaultBase }).trimEnd('/')
        val complete: Boolean get() = model.isNotBlank() && (apiKey.isNotBlank() || vendor == Vendor.CUSTOM) && base.isNotBlank()
    }

    data class Prompt(
        val description: String,
        val count: Int,
        /** Brani di riferimento ("simili a"), gia' in forma "Artista - Titolo". */
        val seeds: List<String> = emptyList(),
        val language: String = "",
    )

    private const val STORE_ID = "__ai__"

    fun config(ctx: Context): Config? = TokenStore(ctx).get(STORE_ID)?.let { t ->
        Config(
            vendor = runCatching { Vendor.valueOf(t.extra["vendor"] ?: "") }.getOrDefault(Vendor.OPENAI),
            apiKey = t.accessToken,
            model = t.extra["model"].orEmpty(),
            baseUrl = t.extra["baseUrl"].orEmpty(),
        )
    }

    fun save(ctx: Context, c: Config) {
        TokenStore(ctx).set(STORE_ID, Tokens(accessToken = c.apiKey, extra = mapOf("vendor" to c.vendor.name, "model" to c.model, "baseUrl" to c.baseUrl)))
    }

    fun clear(ctx: Context) = TokenStore(ctx).clear(STORE_ID)

    // Generare 50 brani puo' prendere piu' dei 40 s del client condiviso.
    private val client = Http.client.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

    private suspend fun post(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(Http.jsonBody(body)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ProviderException(errorMessage(resp.code, text))
            text
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val j = runCatching { parseJson(body) }.getOrNull()
        val msg = j["error"]["message"].str ?: j["error"].str ?: j["message"].str
        return "AI $code" + (msg?.let { ": ${it.take(200)}" } ?: "")
    }

    /** I modelli disponibili con questa chiave, per non farli scrivere a mano. */
    suspend fun models(c: Config): List<String> = withContext(Dispatchers.IO) {
        val (url, headers) = when (c.vendor) {
            Vendor.ANTHROPIC -> "${c.base}/models?limit=100" to mapOf("x-api-key" to c.apiKey, "anthropic-version" to ANTHROPIC_VERSION)
            Vendor.GEMINI -> "${c.base}/models?pageSize=200&key=${Http.enc(c.apiKey)}" to emptyMap<String, String>()
            else -> "${c.base}/models" to bearer(c)
        }
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        val text = client.newCall(req).execute().use { resp ->
            val t = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ProviderException(errorMessage(resp.code, t))
            t
        }
        val j = parseJson(text)
        val ids = when (c.vendor) {
            Vendor.GEMINI -> j["models"].arr.filter { m -> m["supportedGenerationMethods"].arr.any { it.str == "generateContent" } }
                .mapNotNull { it["name"].str?.removePrefix("models/") }
            else -> j["data"].arr.mapNotNull { it["id"].str }
        }
        ids.distinct().sorted()
    }

    private fun bearer(c: Config): Map<String, String> = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")

    private const val ANTHROPIC_VERSION = "2023-06-01"

    private fun system(r: Prompt): String = """
        You build music playlists. Reply with ONLY a JSON array, no prose, no code fences.
        Each element is {"artist": "...", "title": "...", "album": "..."} with real, existing recordings;
        use the main artist's name as it appears on streaming services and the original song title,
        with no year, no annotations. Exactly ${r.count} distinct tracks, no duplicates, no repeated artist
        more than 3 times unless asked. ${if (r.language.isNotBlank()) "Song choice may follow this request language: ${r.language}." else ""}
    """.trimIndent()

    private fun user(r: Prompt): String = buildString {
        append("Playlist request: ").append(r.description.trim()).append('\n')
        if (r.seeds.isNotEmpty()) {
            append("The listener already likes these; pick tracks in the same spirit, do not include them:\n")
            r.seeds.forEach { append("- ").append(it).append('\n') }
        }
        append("Number of tracks: ").append(r.count)
    }

    /** La playlist proposta, come brani senza id (li trovera' la ricerca del servizio di destinazione). */
    suspend fun generate(c: Config, r: Prompt): List<Track> {
        val text = when (c.vendor) {
            Vendor.ANTHROPIC -> {
                val body = jsonObj(
                    "model" to c.model, "max_tokens" to 8192, "system" to system(r),
                    "messages" to jsonArr(listOf(jsonObj("role" to "user", "content" to user(r)))),
                )
                val resp = post("${c.base}/messages", mapOf("x-api-key" to c.apiKey, "anthropic-version" to ANTHROPIC_VERSION), body.toString())
                parseJson(resp)["content"].arr.mapNotNull { it["text"].str }.joinToString("")
            }
            Vendor.GEMINI -> {
                val body = jsonObj(
                    "systemInstruction" to jsonObj("parts" to jsonArr(listOf(jsonObj("text" to system(r))))),
                    "contents" to jsonArr(listOf(jsonObj("role" to "user", "parts" to jsonArr(listOf(jsonObj("text" to user(r))))))),
                    "generationConfig" to jsonObj("responseMimeType" to "application/json"),
                )
                val resp = post("${c.base}/models/${c.model}:generateContent?key=${Http.enc(c.apiKey)}", emptyMap(), body.toString())
                parseJson(resp)["candidates"][0]["content"]["parts"].arr.mapNotNull { it["text"].str }.joinToString("")
            }
            else -> {
                val body = jsonObj(
                    "model" to c.model,
                    "messages" to jsonArr(listOf(jsonObj("role" to "system", "content" to system(r)), jsonObj("role" to "user", "content" to user(r)))),
                )
                val resp = post("${c.base}/chat/completions", bearer(c), body.toString())
                parseJson(resp)["choices"][0]["message"]["content"].str ?: ""
            }
        }
        return parseTracks(text)
    }

    /** Dal testo del modello alla lista: si isola l'array JSON (i modelli aggiungono a volte prosa o recinti). */
    fun parseTracks(text: String): List<Track> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        val body = if (start >= 0 && end > start) text.substring(start, end + 1) else text
        val tracks = PlaylistFiles.parseJson(body).ifEmpty { PlaylistFiles.parseText(text) }
        return tracks.distinctBy { (it.artists.firstOrNull().orEmpty() + "|" + it.title).lowercase() }
    }
}
