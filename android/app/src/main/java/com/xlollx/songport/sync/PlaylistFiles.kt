package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Import/export di playlist come file, in tutti i formati che i servizi musicali (o i loro
 * esportatori) producono davvero. E' il ponte per i servizi senza API aperta — Amazon Music,
 * Qobuz, SoundCloud — e per i backup: qualunque cosa si riesca a esportare da li' entra qui.
 *
 * Formati riconosciuti in lettura:
 *  - **CSV / TSV / CSV con `;`** (Exportify, TuneMyMusic, Soundiiz, "Esporta playlist" di Apple Music)
 *  - **M3U / M3U8** (la maggior parte dei player desktop e Android)
 *  - **XML plist** (Apple Music / iTunes → File › Libreria › Esporta libreria)
 *  - **JSON** (export di Amazon "Request My Data", Soundiiz, backup vari)
 *  - **testo semplice**, una riga per brano: "Artista - Titolo"
 *
 * In scrittura: CSV (completo, con ISRC) e M3U (per i player).
 * Kotlin puro, niente Android: coperto dai test.
 */
object PlaylistFiles {

    enum class Format { CSV, M3U, ITUNES_XML, JSON, TEXT }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TITLE_KEYS = listOf("title", "trackname", "name", "track", "song", "songname", "tracktitle", "titolo", "brano")
    private val ARTIST_KEYS = listOf("artist", "artists", "artistname", "artistnames", "albumartist", "artista", "artisti", "performer", "creator")
    private val ALBUM_KEYS = listOf("album", "albumname", "albumtitle")
    private val DURATION_KEYS = listOf("durationms", "duration", "length", "time", "totaltime", "durata")
    private val ISRC_KEYS = listOf("isrc")

    fun detect(fileName: String, text: String): Format {
        val name = fileName.lowercase()
        val head = text.take(4000).trimStart().removePrefix("﻿")
        return when {
            head.startsWith("#EXTM3U") || name.endsWith(".m3u") || name.endsWith(".m3u8") -> Format.M3U
            head.contains("<plist") || (name.endsWith(".xml") && head.startsWith("<")) -> Format.ITUNES_XML
            head.startsWith("{") || head.startsWith("[") || name.endsWith(".json") -> Format.JSON
            firstLine(text)?.any { it == ',' || it == ';' || it == '\t' } == true -> Format.CSV
            else -> Format.TEXT
        }
    }

    fun parse(fileName: String, text: String): List<Track> = when (detect(fileName, text)) {
        Format.M3U -> parseM3u(text)
        Format.ITUNES_XML -> parseItunesXml(text)
        Format.JSON -> parseJson(text)
        Format.CSV -> CsvCodec.parse(text)
        Format.TEXT -> parseText(text)
    }

    // ---------------------------------------------------------------- M3U

    private val EXTINF = Regex("""^#EXTINF:\s*(-?\d+(?:\.\d+)?)?\s*(?:[^,]*)?,\s*(.*)$""")

    fun parseM3u(text: String): List<Track> {
        val out = ArrayList<Track>()
        var pendingSeconds = 0L
        var pendingLabel: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val m = EXTINF.find(line) ?: continue
                pendingSeconds = m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }?.toLong() ?: 0
                pendingLabel = m.groupValues[2].trim().ifBlank { null }
                continue
            }
            val label = pendingLabel ?: fileNameLabel(line)
            val (artist, title) = splitArtistTitle(label)
            if (title.isNotBlank()) {
                out += CsvCodec.withStableId(
                    Track(id = "", title = title, artists = artist, durationMs = pendingSeconds * 1000)
                )
            }
            pendingSeconds = 0
            pendingLabel = null
        }
        return out
    }

    private fun fileNameLabel(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .replace('_', ' ').trim()

    fun toM3u(tracks: List<Track>): String = buildString {
        append("#EXTM3U\r\n")
        for (t in tracks) {
            val secs = if (t.durationMs > 0) t.durationMs / 1000 else -1
            append("#EXTINF:").append(secs).append(',')
            if (t.artists.isNotEmpty()) append(t.artistLine).append(" - ")
            append(t.title).append("\r\n")
            // Nessun file locale: lasciamo una riga di ricerca, i player la ignorano o la mostrano.
            append(t.artistLine).append(if (t.artists.isEmpty()) "" else " - ").append(t.title).append("\r\n")
        }
    }

    // ---------------------------------------------------------------- iTunes / Apple Music XML

    private val DICT_BLOCK = Regex("""<dict>((?:(?!<dict>|</dict>)[\s\S])*)</dict>""")
    private val STRING_ENTRY = Regex("""<key>([^<]+)</key>\s*<(string|integer|date|real)>([\s\S]*?)</\2>""")

    fun parseItunesXml(text: String): List<Track> {
        val out = ArrayList<Track>()
        for (block in DICT_BLOCK.findAll(text)) {
            val body = block.groupValues[1]
            val fields = HashMap<String, String>()
            for (e in STRING_ENTRY.findAll(body)) {
                fields[e.groupValues[1].lowercase().replace(" ", "")] = unescapeXml(e.groupValues[3])
            }
            // Solo i dizionari dei brani (hanno "Track ID"); gli altri sono playlist o metadati.
            if (!fields.containsKey("trackid")) continue
            val title = fields["name"]?.trim().orEmpty()
            if (title.isEmpty()) continue
            out += CsvCodec.withStableId(
                Track(
                    id = "",
                    title = title,
                    artists = CsvCodec.splitArtists(fields["artist"] ?: fields["albumartist"] ?: ""),
                    album = fields["album"].orEmpty(),
                    durationMs = fields["totaltime"]?.toLongOrNull() ?: 0,
                )
            )
        }
        return out
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#38;", "&").replace("&amp;", "&")

    // ---------------------------------------------------------------- JSON

    fun parseJson(text: String): List<Track> {
        val root = try { json.parseToJsonElement(text) } catch (e: Exception) { return emptyList() }
        val best = bestTrackArray(root) ?: return emptyList()
        val out = ArrayList<Track>()
        for (el in best) {
            val o = el as? JsonObject ?: continue
            val title = pick(o, TITLE_KEYS)?.trim().orEmpty()
            if (title.isEmpty()) continue
            out += CsvCodec.withStableId(
                Track(
                    id = "",
                    title = title,
                    artists = CsvCodec.splitArtists(pick(o, ARTIST_KEYS).orEmpty()),
                    album = pick(o, ALBUM_KEYS).orEmpty(),
                    durationMs = CsvCodec.parseDuration(pick(o, DURATION_KEYS).orEmpty()),
                    isrc = pick(o, ISRC_KEYS)?.takeIf { it.isNotBlank() },
                )
            )
        }
        return out
    }

    /** L'array di oggetti piu' lungo che sembra una lista di brani (ha una chiave "titolo"). */
    private fun bestTrackArray(root: JsonElement): JsonArray? {
        var best: JsonArray? = null
        fun visit(e: JsonElement) {
            when (e) {
                is JsonArray -> {
                    val objects = e.filterIsInstance<JsonObject>()
                    if (objects.isNotEmpty() && objects.any { pick(it, TITLE_KEYS) != null } &&
                        (best == null || objects.size > best!!.size)
                    ) best = e
                    e.forEach { visit(it) }
                }
                is JsonObject -> e.values.forEach { visit(it) }
                else -> {}
            }
        }
        visit(root)
        return best
    }

    /** Valore della prima chiave riconosciuta (confronto senza maiuscole/spazi/underscore). */
    private fun pick(o: JsonObject, keys: List<String>): String? {
        val normalized = o.entries.associate { (k, v) -> k.lowercase().replace(Regex("""[^a-z]"""), "") to v }
        for (k in keys) {
            val v = normalized[k] ?: continue
            val s = flatten(v)
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    /** Un campo puo' essere stringa, numero, lista di stringhe o lista di oggetti {name: …}. */
    private fun flatten(v: JsonElement): String? = when (v) {
        is JsonPrimitive -> v.content.takeIf { it != "null" }
        is JsonArray -> v.mapNotNull { flatten(it) }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        is JsonObject -> (v["name"] ?: v["title"] ?: v["artistName"])?.let { flatten(it) }
    }

    // ---------------------------------------------------------------- testo semplice

    private val LIST_PREFIX = Regex("""^\s*\d+\s*[.)\-]\s+""")

    fun parseText(text: String): List<Track> {
        val out = ArrayList<Track>()
        for (raw in text.lineSequence()) {
            val line = LIST_PREFIX.replace(raw.trim(), "").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val (artists, title) = splitArtistTitle(line)
            if (title.isNotBlank()) out += CsvCodec.withStableId(Track(id = "", title = title, artists = artists))
        }
        return out
    }

    private val DASH = Regex("""\s+[-–—]\s+""")

    /** "Artista - Titolo" (il formato di gran lunga piu' diffuso negli export testuali). */
    fun splitArtistTitle(label: String): Pair<List<String>, String> {
        val parts = DASH.split(label.trim(), 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            CsvCodec.splitArtists(parts[0]) to parts[1].trim()
        } else {
            emptyList<String>() to label.trim()
        }
    }

    private fun firstLine(text: String): String? = text.lineSequence().firstOrNull { it.isNotBlank() }
}
