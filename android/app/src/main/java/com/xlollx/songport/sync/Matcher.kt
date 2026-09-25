package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Abbinamento di brani tra servizi diversi. Kotlin puro (nessuna dipendenza Android) per i test.
 *
 * Strategia: ISRC identico = stesso brano (1.0). Altrimenti punteggio 0..1 da titolo normalizzato,
 * artisti e differenza di durata. Le versioni ("Remastered 2011", "Radio Edit", "(feat. X)") vengono
 * rimosse dal titolo; "remix" viene invece mantenuto per non confondere un remix con l'originale.
 */
object Matcher {
    const val DEFAULT_THRESHOLD = 0.70
    /** Soglia piu' alta quando si confronta con brani GIA' presenti nella destinazione (niente ricerca). */
    const val EXISTING_THRESHOLD = 0.82
    /** Sotto questo punteggio l'abbinamento viene accettato ma segnalato "da verificare". */
    const val REVIEW_THRESHOLD = 0.86

    private val BRACKET_NOISE = Regex(
        """\s*[(\[][^)\]]*\b(feat\.?|ft\.?|featuring|remaster(ed)?|version|edit|(?<!re)mix|live|mono|stereo|deluxe|bonus|explicit|clean|official|video|audio|lyrics?|hd|4k|visuali[sz]er|radio|single|album|original|from|soundtrack|ost|anniversary|edition|\d{4})\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_TAIL = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val DASH_TAIL = Regex(
        """\s*[-–—]\s*((\d{4}\s+)?remaster(ed)?.*|radio edit.*|single version.*|album version.*|live.*|mono.*|stereo.*|deluxe.*|bonus track.*|extended.*|original mix.*|(from|de|dal)\s.*|\d{4}\s.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_TAIL = Regex("""\s+(feat\.?|ft\.?|featuring|vs\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}\s]""")

    /**
     * Parole che indicano una versione diversa dallo studio: se compaiono solo nel candidato
     * e non nell'originale, quasi sempre e' il brano sbagliato (il classico "(Live)" o la cover
     * karaoke che YouTube propone per prima). Controllate sul titolo grezzo, prima che la
     * normalizzazione tolga le parentesi.
     */
    private val VERSION_MARKERS = Regex(
        """\b(live|karaoke|instrumental|cover|tribute|sped\s*up|slowed|nightcore|8d|acoustic|acustic[ao]?|unplugged|demo|remix|rmx|a\s*cappella|acapella|reverb|lofi|lo-fi|parody|reaction|remaster(ed)?|mono|stereo)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun versionMarkers(title: String): Set<String> =
        VERSION_MARKERS.findAll(stripDiacritics(title).lowercase()).map { m ->
            m.groupValues[1].replace(Regex("""\s+"""), "").replace("-", "").removeSuffix("ed").removeSuffix("ao").removeSuffix("a")
        }.toSet()

    /**
     * Fattore 0..1 legato alle versioni: marcatori presenti solo nel candidato pesano molto
     * (0.55), presenti solo nell'originale un po' meno (0.8), perche' una sorgente "Live" senza
     * versione live sulla destinazione e' comunque un'approssimazione accettabile.
     * "remaster/mono/stereo" sono neutri: e' lo stesso brano.
     */
    fun versionFactor(srcTitle: String, candTitle: String): Double {
        val neutral = setOf("remaster", "mono", "stereo")
        val a = versionMarkers(srcTitle) - neutral
        val b = versionMarkers(candTitle) - neutral
        return when {
            a == b -> 1.0
            (b - a).isNotEmpty() -> 0.55
            else -> 0.8
        }
    }
    private val SPACES = Regex("""\s+""")
    private val MARKS = Regex("""\p{Mn}+""")

    fun stripDiacritics(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

    fun normalizeTitle(s: String): String {
        var t = stripDiacritics(s).lowercase()
        t = BRACKET_NOISE.replace(t, " ")
        t = FEAT_TAIL.replace(t, " ")
        t = DASH_TAIL.replace(t, " ")
        t = t.replace("&", " and ").replace("$", "s")
        t = NON_ALNUM.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    fun normalizeArtist(s: String): String {
        var a = stripDiacritics(s).lowercase()
        a = ARTIST_TAIL.replace(a, " ")
        a = a.replace("&", " and ").replace("$", "s")
        a = NON_ALNUM.replace(a, " ")
        a = SPACES.replace(a, " ").trim()
        return a.removePrefix("the ").trim()
    }

    /** Versioni compatte per costruire le query di ricerca. */
    fun searchTitle(title: String): String = normalizeTitle(title).ifBlank { title.trim() }
    fun searchArtist(artist: String): String = normalizeArtist(artist).ifBlank { artist.trim() }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** Similarita' 0..1 tra due stringhe gia' normalizzate: max tra ratio di Levenshtein, Jaccard sui token e contenimento. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val lev = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        val inter = ta.intersect(tb).size.toDouble()
        val jaccard = if (ta.isEmpty() || tb.isEmpty()) 0.0 else inter / ta.union(tb).size
        val contained = (ta.isNotEmpty() && tb.containsAll(ta)) || (tb.isNotEmpty() && ta.containsAll(tb))
        val containment = if (contained) 0.9 else 0.0
        return maxOf(lev, jaccard, containment)
    }

    fun artistScore(a: List<String>, b: List<String>): Double =
        artistScoreN(a.map { normalizeArtist(it) }.filter { it.isNotEmpty() }, b.map { normalizeArtist(it) }.filter { it.isNotEmpty() })

    private fun artistScoreN(na: List<String>, nb: List<String>): Double {
        if (na.isEmpty() || nb.isEmpty()) return 0.5 // sconosciuto: neutro
        var best = 0.0
        for (x in na) for (y in nb) best = max(best, similarity(x, y))
        val ja = na.joinToString(" "); val jb = nb.joinToString(" ")
        if (ja.contains(jb) || jb.contains(ja)) best = max(best, 0.9)
        return best
    }

    fun durationFactor(a: Long, b: Long): Double {
        if (a <= 0 || b <= 0) return 0.95
        val d = abs(a - b)
        return when {
            d <= 3_000 -> 1.0
            d <= 10_000 -> 0.93
            d <= 25_000 -> 0.8
            else -> 0.5
        }
    }

    /** Brano con titolo/artisti gia' normalizzati (evita di rifare le regex a ogni confronto). */
    class Norm(val track: Track, val title: String, val artists: List<String>, val isrc: String?)

    fun norm(t: Track) = Norm(t, normalizeTitle(t.title), t.artists.map { normalizeArtist(it) }.filter { it.isNotEmpty() }, t.isrcNorm)

    fun score(src: Track, cand: Track): Double = scoreN(norm(src), norm(cand))

    fun scoreN(s: Norm, c: Norm): Double {
        if (s.isrc != null && c.isrc != null && s.isrc == c.isrc) return 1.0
        val title = similarity(s.title, c.title)
        val artist = artistScoreN(s.artists, c.artists)
        val artistKnown = s.artists.isNotEmpty() && c.artists.isNotEmpty()
        val base = if (artistKnown) 0.6 * title + 0.4 * artist else 0.85 * title + 0.15 * artist
        return base * durationFactor(s.track.durationMs, c.track.durationMs) * versionFactor(s.track.title, c.track.title) *
            albumFactor(s.track.album, c.track.album)
    }

    /**
     * Stesso titolo e artista ma album diverso (singolo contro album, riedizione, compilation): quasi
     * sempre e' la stessa registrazione, ma non sempre, ed e' il cambio piu' lamentato dagli utenti
     * degli altri strumenti perche' passa in silenzio. Un piccolo fattore fa preferire il candidato
     * dello stesso album quando ce n'e' uno, e manda al riesame gli abbinamenti gia' al limite.
     */
    fun albumFactor(srcAlbum: String, candAlbum: String): Double {
        if (srcAlbum.isBlank() || candAlbum.isBlank()) return 1.0
        val a = normalizeTitle(srcAlbum)
        val b = normalizeTitle(candAlbum)
        if (a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a)) return 1.0
        return 0.97
    }

    fun best(src: Track, candidates: List<Track>, threshold: Double = DEFAULT_THRESHOLD): Track? =
        bestScored(src, candidates, threshold)?.track

    data class Scored(val track: Track, val score: Double)

    /** Come best(), ma con il punteggio: sotto REVIEW_THRESHOLD l'abbinamento va mostrato per conferma. */
    fun bestScored(src: Track, candidates: List<Track>, threshold: Double = DEFAULT_THRESHOLD): Scored? {
        if (candidates.isEmpty()) return null
        val s = norm(src)
        val best = candidates.map { Scored(it, scoreN(s, norm(it))) }.maxByOrNull { it.score } ?: return null
        return best.takeIf { it.score >= threshold }
    }

    /**
     * Indice dei brani di una playlist per cercare velocemente il "gia' presente": i candidati sono
     * solo i brani che condividono almeno un token del titolo (o l'ISRC), non tutta la lista.
     */
    class TrackIndex(tracks: List<Track>) {
        private val items = tracks.map { norm(it) }
        private val byToken = HashMap<String, MutableList<Int>>()
        private val byIsrc = HashMap<String, Int>()

        init {
            items.forEachIndexed { i, n ->
                n.title.split(' ').filter { it.isNotEmpty() }.toSet().forEach { byToken.getOrPut(it) { ArrayList() }.add(i) }
                n.isrc?.let { byIsrc.putIfAbsent(it, i) }
            }
        }

        fun best(src: Track, threshold: Double = EXISTING_THRESHOLD): Track? {
            val s = norm(src)
            s.isrc?.let { isrc -> byIsrc[isrc]?.let { return items[it].track } }
            val candIdx = LinkedHashSet<Int>()
            s.title.split(' ').filter { it.isNotEmpty() }.forEach { tok -> byToken[tok]?.let { candIdx.addAll(it) } }
            var bestScore = 0.0
            var best: Track? = null
            for (i in candIdx) {
                val sc = scoreN(s, items[i])
                if (sc > bestScore) { bestScore = sc; best = items[i].track }
            }
            return if (bestScore >= threshold) best else null
        }
    }
}
