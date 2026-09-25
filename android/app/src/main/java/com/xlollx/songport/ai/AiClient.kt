package com.xlollx.songport.ai

import android.content.Context
import com.xlollx.songport.data.TokenStore
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.int
import com.xlollx.songport.net.get
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.PlaylistFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    enum class Vendor(val label: String, val defaultBase: String, val keyUrl: String, val limitsUrl: String = "") {
        OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "https://platform.openai.com/settings/organization/limits"),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys", "https://console.anthropic.com/settings/limits"),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey", "https://aistudio.google.com/apikey"),
        /** Livello gratuito generoso; parla l'API di OpenAI. */
        GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "https://console.groq.com/settings/limits"),
        /** Molti modelli, alcuni gratuiti (quelli con ":free"); parla l'API di OpenAI. */
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "https://openrouter.ai/settings/keys"),
        /** Ollama, LM Studio, Mistral…: tutti parlano l'API chat di OpenAI. */
        CUSTOM("OpenAI-compatible", "", ""),
        ;
        /** Where a key costs nothing to try. */
        val freeTier: Boolean get() = this == GEMINI || this == GROQ || this == OPENROUTER
    }

    data class Config(val vendor: Vendor, val apiKey: String, val model: String, val baseUrl: String) {
        val base: String get() = (baseUrl.ifBlank { vendor.defaultBase }).trimEnd('/')
        /** The only host the key is ever sent to. */
        val host: String get() = runCatching { java.net.URI(base).host }.getOrNull() ?: base
        val complete: Boolean get() = model.isNotBlank() && (apiKey.isNotBlank() || vendor == Vendor.CUSTOM) && base.isNotBlank()
    }

    data class Prompt(
        val description: String,
        val count: Int,
        /** Brani di riferimento ("simili a"), gia' in forma "Artista - Titolo". */
        val seeds: List<String> = emptyList(),
        val language: String = "",
        /** Brani gia' nella playlist quando la si allunga: stesso spirito, nessuno di questi. */
        val existing: List<String> = emptyList(),
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
        // "Sovraccarico, riprova" (503/529) e i limiti di frequenza (429) passano da soli in pochi
        // secondi: si riprova tre volte con attese crescenti prima di darlo per fallito.
        var attempt = 0
        var result = 0 to ""
        while (true) {
            result = client.newCall(req).execute().use { resp -> resp.code to (resp.body?.string() ?: "") }
            if (result.first in RETRY_CODES && attempt < 3) {
                attempt++
                delay(3000L * attempt)
                continue
            }
            break
        }
        if (result.first !in 200..299) throw ProviderException(errorMessage(result.first, result.second))
        result.second
    }

    private val RETRY_CODES = setOf(429, 500, 502, 503, 529)

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
        // Anthropic lists newest first; the others come in no useful order.
        if (c.vendor == Vendor.ANTHROPIC) ids.distinct() else ids.distinct().sorted()
    }

    private val DATED = Regex("""-(\d{4}-\d{2}-\d{2}|\d{4}|\d{2}-\d{2}|\d{3}|preview-\d.*|exp.*)$""")
    private val NOT_CHAT = listOf(
        "embedding", "embed", "aqa", "tts", "audio", "image", "imagen", "veo", "vision", "live", "realtime",
        "transcribe", "whisper", "dall-e", "moderation", "search", "instruct", "davinci", "babbage", "ada", "curie",
        "codex", "computer-use", "learnlm", "gemma", "robotics", "native", "deep-research",
    )

    /**
     * La lista dell'API contiene di tutto: modelli per immagini, audio, embedding, versioni datate
     * e generazioni ritirate. Qui restano quelli che chattano e sono correnti; l'elenco intero si
     * apre a parte. Anthropic espone solo i modelli attivi, e' gia' pulito.
     */
    fun curated(vendor: Vendor, models: List<String>): List<String> {
        if (vendor == Vendor.ANTHROPIC || vendor == Vendor.CUSTOM) return models
        // OpenRouter is chosen for its free models: those first, the paid catalogue behind "show all".
        if (vendor == Vendor.OPENROUTER) return models.filter { it.endsWith(":free") }.ifEmpty { models }
        if (vendor == Vendor.GROQ) return models.filter { m -> val l = m.lowercase(); NOT_CHAT.none { it in l } && "guard" !in l }
        val chat = models.filter { m -> val l = m.lowercase(); NOT_CHAT.none { it in l } && "-exp" !in l && "experimental" !in l }
        val current = chat.filter { m ->
            val l = m.lowercase()
            when (vendor) {
                Vendor.GEMINI -> l.startsWith("gemini-") && !l.startsWith("gemini-1.")
                else -> (l.startsWith("gpt-") && !l.startsWith("gpt-3")) || Regex("""^o\d""").containsMatchIn(l)
            }
        }
        // A dated or preview variant is hidden when its plain name is also there.
        val plain = current.filter { !DATED.containsMatchIn(it) }.toSet()
        val kept = current.filter { m -> m in plain || DATED.replace(m, "") !in plain }
        return kept.sortedWith(compareByDescending<String> { versionOf(it) }.thenBy { it })
    }

    private fun versionOf(m: String): Double = Regex("""(\d+(?:\.\d+)?)""").find(m)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

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
        val wish = r.description.trim().ifEmpty { "Continue this playlist in the same spirit as the tracks it already contains (genre, era, mood, energy)." }
        append("Playlist request: ").append(wish).append('\n')
        if (r.seeds.isNotEmpty()) {
            append("The listener already likes these; pick tracks in the same spirit, do not include them:\n")
            r.seeds.forEach { append("- ").append(it).append('\n') }
        }
        if (r.existing.isNotEmpty()) {
            append("The playlist already contains these tracks; add new ones that fit with them, and do not repeat any of them:\n")
            r.existing.forEach { append("- ").append(it).append('\n') }
        }
        append("Number of NEW tracks to return: ").append(r.count)
    }

    /** La playlist proposta, come brani senza id (li trovera' la ricerca del servizio di destinazione). */
    suspend fun generate(c: Config, r: Prompt): List<Track> = parseTracks(complete(c, system(r), user(r)))

    data class Suggestion(val match: Int?, val queries: List<String>, val note: String)

    /**
     * Aiuto nella revisione di un brano non trovato: dati il brano d'origine e i risultati gia' visti,
     * il modello indica quale risultato e' lo stesso brano (se c'e'), oppure con quali altre parole
     * cercarlo (titolo originale, artista principale, versione), o dice che sul servizio non c'e'.
     */
    suspend fun suggestMatch(c: Config, source: Track, candidates: List<Track>, service: String, language: String): Suggestion {
        val system = """
            You help a playlist sync app match one recording on $service. Reply with ONLY a JSON object:
            {"match": <1-based number of the candidate that is the same recording, or null>,
             "queries": [<up to 3 alternative search strings likely to find it on $service, empty if it is probably not there>],
             "note": "<one short sentence in $language explaining, for a person>"}
            A remaster, the album version or the same song on a compilation counts as a match; a live, cover,
            karaoke, remix or sped-up version does not. Good alternative queries: the original title when the
            given one is a translation or alias, the main artist when the listed one is a guest, the title alone.
        """.trimIndent()
        val user = buildString {
            append("Track to find: ").append(source.toString())
            if (source.album.isNotBlank()) append(" · album: ").append(source.album)
            if (source.durationMs > 0) append(" · ").append(source.durationMs / 1000).append(" s")
            append('\n')
            if (candidates.isEmpty()) append("No candidates were found.\n")
            else {
                append("Candidates found on ").append(service).append(":\n")
                candidates.take(15).forEachIndexed { i, t ->
                    append(i + 1).append(". ").append(t.toString())
                    if (t.album.isNotBlank()) append(" · ").append(t.album)
                    if (t.durationMs > 0) append(" · ").append(t.durationMs / 1000).append(" s")
                    append('\n')
                }
            }
        }
        val text = complete(c, system, user)
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        val j = parseJson(if (start >= 0 && end > start) text.substring(start, end + 1) else text)
        val match = j["match"].int?.takeIf { it in 1..candidates.size }
        val queries = j["queries"].arr.mapNotNull { it.str?.trim()?.takeIf { q -> q.isNotEmpty() } }.take(3)
        return Suggestion(match, queries, j["note"].str.orEmpty())
    }

    /**
     * Prova il modello con una richiesta minima: chiave, quota, modello ritirato o non abilitato
     * danno errori diversi, e conviene vederli subito invece che a playlist descritta. Null = va bene.
     */
    suspend fun check(c: Config): String? = try {
        val text = complete(c, "Reply with the single word OK.", "ping")
        if (text.isBlank()) "Empty answer" else null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Una richiesta, una risposta testuale, qualunque sia il fornitore. */
    private suspend fun complete(c: Config, system: String, user: String): String {
        return when (c.vendor) {
            Vendor.ANTHROPIC -> {
                val body = jsonObj(
                    "model" to c.model, "max_tokens" to 8192, "system" to system,
                    "messages" to jsonArr(listOf(jsonObj("role" to "user", "content" to user))),
                )
                val resp = post("${c.base}/messages", mapOf("x-api-key" to c.apiKey, "anthropic-version" to ANTHROPIC_VERSION), body.toString())
                parseJson(resp)["content"].arr.mapNotNull { it["text"].str }.joinToString("")
            }
            Vendor.GEMINI -> {
                val body = jsonObj(
                    "systemInstruction" to jsonObj("parts" to jsonArr(listOf(jsonObj("text" to system)))),
                    "contents" to jsonArr(listOf(jsonObj("role" to "user", "parts" to jsonArr(listOf(jsonObj("text" to user)))))),
                    "generationConfig" to jsonObj("responseMimeType" to "application/json"),
                )
                val resp = post("${c.base}/models/${c.model}:generateContent?key=${Http.enc(c.apiKey)}", emptyMap(), body.toString())
                parseJson(resp)["candidates"][0]["content"]["parts"].arr.mapNotNull { it["text"].str }.joinToString("")
            }
            else -> {
                val body = jsonObj(
                    "model" to c.model,
                    "messages" to jsonArr(listOf(jsonObj("role" to "system", "content" to system), jsonObj("role" to "user", "content" to user))),
                )
                // OpenRouter asks apps to say who they are; the others ignore the headers.
                val headers = bearer(c) + mapOf("HTTP-Referer" to "https://github.com/xlollx/Songport", "X-Title" to "Songport")
                val resp = post("${c.base}/chat/completions", headers, body.toString())
                parseJson(resp)["choices"][0]["message"]["content"].str ?: ""
            }
        }
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
