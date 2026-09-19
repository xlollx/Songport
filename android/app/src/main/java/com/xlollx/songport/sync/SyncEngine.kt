package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import kotlinx.coroutines.sync.withPermit
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncPlan
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.TargetSearch
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.xlollx.songport.providers.Providers
import java.util.UUID

/**
 * Esegue una sincronizzazione origine -> destinazione in due tempi:
 *
 *  **plan()** legge origine e destinazione, riconosce i brani gia' presenti (cache abbinamenti,
 *  ISRC, titolo+artista+durata), cerca i mancanti e decide cosa aggiungere e cosa togliere.
 *  Non scrive nulla: e' quello che mostra l'anteprima.
 *
 *  **apply()** esegue il piano: aggiunte a blocchi, rimozioni, cache, report.
 *
 * Ogni abbinamento riuscito finisce nella cache, cosi' le sync successive non ripetono le ricerche.
 * I brani che l'utente ha messo fra gli "ignorati" del job non vengono ne' cercati ne' segnalati.
 */
/**
 * Attese visibili quando il servizio limita le richieste: brevi e crescenti. Il contatore si azzera
 * dopo un tratto di ricerche riuscite, cosi' una playlist lunga con limiti intermittenti arriva in
 * fondo; solo una serie di rifiuti senza progressi fa fermare con quanto trovato.
 */
private val WAIT_SECONDS = intArrayOf(10, 20, 40, 60, 120)
/** Quante attese dichiarate dal servizio (blocco dell'indirizzo) si accettano prima di fermarsi con quanto fatto. */
private const val MAX_BLOCK_WAITS = 8
private const val RESET_AFTER_CHUNKS = 15
/** Punteggio fittizio di un "non trovato" preso dalla cache dei fallimenti, mai mostrato. */
private const val MISS_SCORE = -1.0
/**
 * Attese crescenti quando la rete non risponde. Una sync programmata parte spesso mentre il telefono
 * si sta svegliando: WorkManager la lancia appena il sistema dichiara la rete "connessa", ma il DNS
 * puo' arrivare qualche decina di secondi dopo. Quindici secondi di tentativi non bastavano.
 */
private val NETWORK_RETRY_SECONDS = intArrayOf(5, 15, 30, 60, 120)

/** Errori di trasporto che passano da soli: risoluzione del nome, connessione rifiutata o caduta, timeout. */
internal fun isTransientNetwork(msg: String?): Boolean {
    val m = msg ?: return false
    return m.contains("Unable to resolve host", true) || m.contains("unreachable", true) || m.contains("Failed to connect", true) ||
        m.contains("timeout", true) || m.contains("timed out", true) || m.contains("connection abort", true) || m.contains("reset by peer", true)
}

/** Below the match threshold but close enough to be worth proposing in the review. */
private const val HINT_THRESHOLD = 0.45

class SyncEngine(private val ctx: Context) {
    private val store = Store.get(ctx)
    /** Best candidate under the threshold per source track searched in this run: the review's proposals. */
    private val hints = java.util.concurrent.ConcurrentHashMap<String, Track>()

    suspend fun run(job: SyncJob, onProgress: (Progress) -> Unit = {}): SyncReport {
        val started = System.currentTimeMillis()
        val reportId = UUID.randomUUID().toString()
        val report = try {
            val plan = plan(job, onProgress)
            // La ricerca e' finita: i brani non trovati e gli abbinamenti incerti si possono gia'
            // sistemare mentre l'aggiunta procede. Un report provvisorio li rende raggiungibili.
            if (plan.unmatched.isNotEmpty() || plan.uncertain.isNotEmpty()) {
                store.addReport(
                    SyncReport(
                        reportId, job.id, job.name, started, System.currentTimeMillis() - started,
                        sourceCount = plan.sourceCount, unmatched = plan.unmatched.map { it.toString() }, unmatchedTracks = plan.unmatched,
                        ignored = plan.ignored, reviewTracks = plan.uncertain, notes = plan.notes, partial = true,
                        suggestions = suggestionsFor(plan.unmatched),
                    ),
                )
            }
            apply(job, plan, started, reportId, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Il sistema ha fermato il worker (cambio di rete, limiti): non e' un esito della sync e
            // WorkManager la fara' ripartire; gli abbinamenti fatti finora sono gia' in cache.
            throw e
        } catch (e: Exception) {
            Diagnostics.log(ctx, "engine", "${job.name}: ${e.javaClass.simpleName}: ${e.message}")
            SyncReport(reportId, job.id, job.name, started, System.currentTimeMillis() - started,
                error = e.message ?: e.javaClass.simpleName)
        }
        store.addReport(report)
        return report
    }

    private fun providers(job: SyncJob): Pair<MusicProvider, MusicProvider> {
        val src = Providers.byId(job.source.provider) ?: throw ProviderException("Unknown service: ${job.source.provider}")
        val dst = Providers.byId(job.target.provider) ?: throw ProviderException("Unknown service: ${job.target.provider}")
        return src to dst
    }

    /** Calcola cosa farebbe la sync senza modificare nulla (a parte creare la destinazione se manca). */
    suspend fun plan(job: SyncJob, onProgress: (Progress) -> Unit = {}, createTarget: Boolean = true): SyncPlan {
        val (src, dst) = providers(job)
        val srcPlaylistId = job.source.playlistId ?: throw ProviderException("No source playlist selected")
        // L'origine puo' essere una playlist pubblica leggibile senza login (catalogo Apple Music).
        if (!src.canRead(ctx, srcPlaylistId)) throw ProviderException(ctx.getString(R.string.error_not_connected, src.displayName))
        if (!dst.isConnected(ctx)) throw ProviderException(ctx.getString(R.string.error_not_connected, dst.displayName))

        val notes = ArrayList<String>()

        onProgress(Progress(Progress.Step.FETCH_SOURCE))
        val allSource = patient(onProgress) { src.tracks(ctx, srcPlaylistId) }
        // Ignored in this sync, or declared absent from this destination service by any sync.
        val ignoredIds = job.ignoredSourceIds.toHashSet()
        val srcTracks = allSource.filter { it.id !in ignoredIds && !store.isIgnored(src.id, it.id, dst.id) }
        val ignored = allSource.size - srcTracks.size

        var targetId = job.target.playlistId
        var targetCreated = false
        if (targetId == null) {
            if (!createTarget) {
                // Anteprima senza destinazione: tutto cio' che si abbina verrebbe aggiunto.
                return previewWithoutTarget(job, src, dst, srcTracks, ignored, notes, onProgress)
            }
            onProgress(Progress(Progress.Step.CREATE_TARGET))
            val name = job.target.playlistName.ifBlank { job.source.playlistName.ifBlank { job.name } }
            val created = patient(onProgress) { dst.createPlaylist(ctx, name, MusicProvider.DESCRIPTION) }
            targetId = created.id
            targetCreated = true
            store.upsertJob(job.copy(target = job.target.copy(playlistId = created.id, playlistName = created.name)))
        }

        if (dst.serviceId == LocalFilesProvider.serviceId) notes += ctx.getString(R.string.note_file_target)

        onProgress(Progress(Progress.Step.FETCH_TARGET))
        // A playlist created a moment ago is empty: skip the fetch (saves quota, and YouTube's Data API
        // may not even know the playlist yet).
        val dstTracks = if (targetCreated) emptyList() else patient(onProgress) { dst.tracks(ctx, targetId) }
        val dstById = dstTracks.associateBy { it.id }
        val index = Matcher.TrackIndex(dstTracks)

        val matchedDstIds = HashSet<String>()
        val newCache = HashMap<String, String>()
        val toSearch = ArrayList<Track>()

        // 1) brani gia' presenti nella destinazione
        for (s in srcTracks) {
            val key = Store.cacheKey(src.id, s.id, dst.id)
            val found = store.cachedMatch(src.id, s.id, dst.id)?.let { dstById[it] } ?: index.best(s)
            if (found != null) {
                matchedDstIds += found.id
                newCache[key] = found.id
            } else toSearch += s
        }

        // 2) ricerca dei mancanti
        val toAdd = LinkedHashMap<String, Track>()
        val unmatched = ArrayList<Track>()
        val uncertain = ArrayList<MatchReview>()
        var searched = 0
        val interrupted = searchMissing(src, dst, toSearch, onProgress) { s, found, score ->
            searched++
            SyncState.item(
                job.id,
                SyncState.LiveItem(
                    source = listOfNotNull(s.artists.firstOrNull(), s.title).joinToString(" – "),
                    result = found?.let { listOfNotNull(it.artists.firstOrNull(), it.title).joinToString(" – ") },
                    outcome = when {
                        found == null -> SyncState.Outcome.NOT_FOUND
                        score == null -> SyncState.Outcome.CACHED
                        else -> SyncState.Outcome.FOUND
                    },
                ),
            )
            if (found != null) {
                if (found.id !in matchedDstIds) toAdd[found.id] = found
                matchedDstIds += found.id
                newCache[Store.cacheKey(src.id, s.id, dst.id)] = found.id
                if (score != null && score < Matcher.REVIEW_THRESHOLD) uncertain += MatchReview(s, found, score)
                // Salvataggio progressivo: un errore a meta' strada non butta via le ricerche fatte.
                if (newCache.size % 100 == 0) store.putMatches(newCache)
            } else unmatched += s
        }
        // Il servizio ha smesso di rispondere (limiti, sessione): si applica quanto trovato finora e
        // la prossima esecuzione riparte dalla cache, senza rifare le ricerche.
        if (interrupted != null) notes += ctx.getString(R.string.note_search_interrupted, searched, toSearch.size, interrupted)

        // 3) rimozioni: solo cio' che nella destinazione non corrisponde a nulla dell'origine
        var toRemove: List<Track> = emptyList()
        if (job.mirrorRemovals) {
            val extra = dstTracks.filter { it.id !in matchedDstIds }
            when {
                // Ricerca non completata: un brano della destinazione puo' corrispondere a uno
                // dell'origine non ancora cercato. Nulla si toglie finche' non si sa.
                interrupted != null -> if (extra.isNotEmpty()) notes += ctx.getString(R.string.note_removals_deferred, extra.size)
                !dst.canRemoveTracks -> if (extra.isNotEmpty()) notes += ctx.getString(R.string.note_no_removals, dst.displayName, extra.size)
                // Origine vuota: quasi certamente un errore, non svuotiamo la destinazione.
                srcTracks.isEmpty() -> notes += ctx.getString(R.string.error_source_empty)
                else -> toRemove = extra
            }
        }

        // Gli abbinamenti sono fatti, non intenzioni: salvarli ora evita di ripetere le ricerche
        // (e la quota YouTube) se l'utente esegue dopo l'anteprima.
        store.putMatches(newCache)
        return SyncPlan(
            targetPlaylistId = targetId,
            sourceCount = srcTracks.size,
            alreadyPresent = srcTracks.size - toSearch.size,
            toAdd = toAdd.values.toList(),
            toRemove = toRemove,
            unmatched = unmatched,
            ignored = ignored,
            uncertain = uncertain,
            notes = notes,
            newMatches = newCache,
            targetCreated = targetCreated,
        )
    }

    /** Anteprima quando la destinazione non esiste ancora: si cerca tutto, non si crea niente. */
    private suspend fun previewWithoutTarget(
        job: SyncJob, src: MusicProvider, dst: MusicProvider, srcTracks: List<Track>,
        ignored: Int, notes: List<String>, onProgress: (Progress) -> Unit,
    ): SyncPlan {
        val toAdd = LinkedHashMap<String, Track>()
        val unmatched = ArrayList<Track>()
        val uncertain = ArrayList<MatchReview>()
        val newCache = HashMap<String, String>()
        searchMissing(src, dst, srcTracks, onProgress) { s, found, score ->
            if (found != null) {
                toAdd[found.id] = found
                newCache[Store.cacheKey(src.id, s.id, dst.id)] = found.id
                if (score != null && score < Matcher.REVIEW_THRESHOLD) uncertain += MatchReview(s, found, score)
            } else unmatched += s
        }
        store.putMatches(newCache)
        return SyncPlan(
            targetPlaylistId = "", sourceCount = srcTracks.size, alreadyPresent = 0,
            toAdd = toAdd.values.toList(), toRemove = emptyList(), unmatched = unmatched,
            ignored = ignored, uncertain = uncertain, notes = notes, newMatches = newCache, targetCreated = false,
        )
    }

    /**
     * Cerca sulla destinazione i brani mancanti, usando la cache quando c'e'. Le ricerche vanno in
     * parallelo dove il servizio lo tollera (vedi [MusicProvider.searchParallelism]); i risultati
     * sono consegnati nell'ordine di origine.
     *
     * @return null se ha finito, altrimenti il motivo per cui si e' fermata prima (errore sistemico:
     * limiti di richieste, sessione scaduta). I brani non cercati non vengono consegnati.
     */
    /** Un semaforo per servizio di destinazione, condiviso da tutte le istanze del motore nel processo. */
    private companion object {
        val searchSlots = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Semaphore>()
    }

    private suspend fun searchMissing(
        src: MusicProvider, dst: MusicProvider, toSearch: List<Track>,
        onProgress: (Progress) -> Unit, onResult: (Track, Track?, Double?) -> Unit,
    ): String? {
        val parallel = dst.searchParallelism.coerceIn(1, 8)
        var consecutiveErrors = 0
        var done = 0
        var waits = 0
        var blockWaits = 0
        var sinceWait = 0
        val chunks = toSearch.chunked(parallel)
        var ci = 0
        val startedAt = System.currentTimeMillis()
        var searched = 0
        // Ricerche finite senza esito in questa esecuzione: ricordate, cosi' una ripresa o il giro di
        // domani non le ripetono (le "non trovate" altrimenti si ricercavano ogni volta).
        val misses = ArrayList<String>()
        fun saveMisses() { store.putMisses(misses); misses.clear() }
        while (ci < chunks.size) {
            val chunk = chunks[ci]
            // Cosa si sta cercando adesso: la scheda e la notifica lo mostrano, cosi' non sembra fermo.
            onProgress(Progress(Progress.Step.MATCHING, done, toSearch.size, chunk.first().let { listOfNotNull(it.artists.firstOrNull(), it.title).joinToString(" – ") }))
            // Esito per brano: (trovato, punteggio); punteggio null = preso dalla cache, mai "incerto".
            val results: List<Result<Pair<Track?, Double?>>> = coroutineScope {
                chunk.map { s ->
                    async {
                        val cached = store.cachedMatch(src.id, s.id, dst.id)
                        if (cached != null) {
                            // Abbinato in passato (o confermato a mano): lo aggiungiamo senza ripetere la ricerca.
                            Result.success<Pair<Track?, Double?>>(dst.rehydrate(s.copy(id = cached, uri = null, itemId = null)) to null)
                        } else if (store.cachedMiss(src.id, s.id, dst.id)) {
                            // Cercato da poco senza esito: resta "non trovato" senza interrogare il servizio.
                            Result.success<Pair<Track?, Double?>>(null to MISS_SCORE)
                        } else {
                            // Slot condivisi fra le sync in corso verso lo stesso servizio: due sync insieme
                            // non raddoppiano la pressione, se la dividono.
                            val slots = searchSlots.getOrPut(dst.id) { kotlinx.coroutines.sync.Semaphore(parallel) }
                            slots.withPermit { runCatching { searchScored(dst, s) }.map { it?.track to it?.score } }
                        }
                    }
                }.awaitAll()
            }
            // Il servizio chiede una pausa (403/429 dopo molte ricerche): e' un limite suo, non un
            // guasto. Si aspetta in modo visibile, sempre piu' a lungo, e si riprova lo stesso gruppo.
            val throttled = results.firstNotNullOfOrNull { r -> (r.exceptionOrNull() as? ProviderException)?.takeIf { isThrottle(it.message) } }
            // Un blocco dichiarato dal servizio ("retry in N s") si rispetta alla lettera: e' l'unico
            // modo perche' scada. Le altre pause seguono la scala crescente.
            val asked = declaredWait(throttled?.message)
            if (throttled != null && asked != null && blockWaits < MAX_BLOCK_WAITS) {
                blockWaits++
                Diagnostics.log(ctx, "engine", "${dst.displayName} is blocked: ${throttled.message?.take(200)}; waiting ${asked}s")
                waitVisible(asked, onProgress)
                continue
            }
            if (throttled != null && asked == null && waits < WAIT_SECONDS.size) {
                val seconds = WAIT_SECONDS[waits++]
                Diagnostics.log(ctx, "engine", "${dst.displayName} is throttling: ${throttled.message?.take(200)}; waiting ${seconds}s")
                sinceWait = 0
                for (s in 1..seconds) {
                    onProgress(Progress(Progress.Step.WAITING, s, seconds))
                    kotlinx.coroutines.delay(1000)
                }
                continue
            }
            if (throttled == null && ++sinceWait >= RESET_AFTER_CHUNKS) { waits = 0; sinceWait = 0 }
            ci++
            chunk.forEachIndexed { i, s ->
                done++
                onProgress(Progress(Progress.Step.MATCHING, done, toSearch.size))
                val r = results[i]
                val e = r.exceptionOrNull()
                if (e != null) {
                    if (e !is ProviderException) throw e
                    consecutiveErrors++
                    val msg = e.message ?: ""
                    // Errori sistemici (quota, limiti, sessione scaduta): inutile insistere. Se non e'
                    // stato trovato ancora nulla l'errore e' dell'intera sync; altrimenti ci si ferma qui.
                    val systemic = consecutiveErrors >= 3 || msg.contains("quota", true) || msg.contains("reconnect", true) ||
                        msg.contains("sign in again", true) || isThrottle(msg)
                    if (systemic) {
                        saveMisses()
                        if (done - chunk.size + i <= 0) throw e
                        return msg
                    }
                    onResult(s, null, null)
                } else {
                    consecutiveErrors = 0
                    val (found, score) = r.getOrThrow()
                    if (score != MISS_SCORE && store.cachedMatch(src.id, s.id, dst.id) == null) searched++
                    if (found == null && score != MISS_SCORE) misses += Store.cacheKey(src.id, s.id, dst.id)
                    if (misses.size >= 50) saveMisses()
                    onResult(s, found, if (score == MISS_SCORE) null else score)
                }
            }
        }
        saveMisses()
        if (searched > 0) {
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            Diagnostics.log(ctx, "engine", "${dst.displayName}: $searched searches in ${secs}s (${if (searched > 0) secs * 1000 / searched else 0} ms each), $waits waits")
        }
        return null
    }

    /**
     * Cerca [s] sulla destinazione e ne tiene il miglior candidato sopra soglia. La prima query e' quella
     * del servizio (artisti e titolo cosi' come sono); se non basta si riprova con il titolo pulito e il
     * primo artista, poi con il solo titolo: alcuni cataloghi rispondono meglio a query corte, e i
     * candidati sono comunque valutati contro l'artista originale, quindi un omonimo non passa.
     */
    private suspend fun searchScored(dst: MusicProvider, s: Track): Matcher.Scored? {
        var runnerUp: Matcher.Scored? = null
        fun consider(found: List<Track>): Matcher.Scored? {
            val best = Matcher.bestScored(s, found, 0.0) ?: return null
            if (best.score >= Matcher.DEFAULT_THRESHOLD) return best
            if (best.score > (runnerUp?.score ?: 0.0)) runnerUp = best
            return null
        }
        try {
            consider(dst.search(ctx, s))?.let { return it }
            val tried = hashSetOf(query(s))
            val title = Matcher.searchTitle(s.title)
            val first = s.artists.firstOrNull()?.let { Matcher.searchArtist(it) }?.takeIf { it.isNotBlank() }
            val variants = listOf(
                s.copy(title = title, artists = listOfNotNull(first), isrc = null),
                s.copy(title = title, artists = emptyList(), isrc = null),
            )
            for (v in variants) {
                if (!tried.add(query(v))) continue
                consider(dst.search(ctx, v))?.let { return it }
            }
            // Last resort, where the service has one: a wider catalogue (YouTube videos for YouTube Music).
            consider(dst.searchWide(ctx, s))?.let { return it }
            return null
        } finally {
            // Not found, but something came close: the review offers it as a one-tap proposal.
            runnerUp?.takeIf { it.score >= HINT_THRESHOLD }?.let { hints[s.id] = it.track }
        }
    }

    private fun suggestionsFor(unmatched: List<Track>): Map<String, Track> =
        unmatched.mapNotNull { t -> hints[t.id]?.let { t.id to it } }.toMap()

    private fun query(t: Track): String = (t.artists.take(2) + t.title).joinToString(" ").lowercase().trim()

    /**
     * Esegue una chiamata al servizio rispettando i blocchi che dichiara ("Retry in N s"): aspetta il
     * tempo detto, visibilmente, e riprova, fino al limite; ogni altro errore passa oltre subito.
     */
    private suspend fun <T> patient(onProgress: (Progress) -> Unit, block: suspend () -> T): T {
        var blockWaits = 0
        var networkRetries = 0
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val asked = declaredWait(e.message)
                when {
                    asked != null && blockWaits < MAX_BLOCK_WAITS -> {
                        blockWaits++
                        Diagnostics.log(ctx, "engine", "blocked: ${e.message?.take(160)}; waiting ${asked}s")
                        waitVisible(asked, onProgress)
                    }
                    // Rete assente per un attimo (risveglio programmato, cambio Wi-Fi/dati): non e' il
                    // servizio, si riprova con attese crescenti.
                    isTransientNetwork(e.message) && networkRetries < NETWORK_RETRY_SECONDS.size -> {
                        val seconds = NETWORK_RETRY_SECONDS[networkRetries++]
                        Diagnostics.log(ctx, "engine", "network hiccup: ${e.message?.take(120)}; retrying in ${seconds}s")
                        waitVisible(seconds, onProgress)
                    }
                    else -> throw e
                }
            }
        }
    }

    /** "Artista – Titolo", come nella vista dal vivo della fase di ricerca. */
    private fun Track.live(): String = listOfNotNull(artists.firstOrNull(), title).joinToString(" – ")

    /** Secondi di attesa dichiarati dal servizio ("Retry in N s"), entro limiti ragionevoli; null se non li dichiara. */
    private fun declaredWait(msg: String?): Int? =
        Regex("retry in (\\d+) s", RegexOption.IGNORE_CASE).find(msg ?: "")?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(5, 1800)

    /** Attesa mostrata all'utente secondo per secondo; lo stop la interrompe, la pausa no. */
    private suspend fun waitVisible(seconds: Int, onProgress: (Progress) -> Unit) {
        for (s in 1..seconds) {
            onProgress(Progress(Progress.Step.WAITING, s, seconds))
            kotlinx.coroutines.delay(1000)
        }
    }

    /** Il servizio sta limitando le richieste (non un errore dell'app). */
    private fun isThrottle(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("403") || m.contains("429") || m.contains("too many", true) || m.contains("rate limit", true) ||
            m.contains("refused the request", true)
    }

    /** Applica un piano: aggiunte, rimozioni, cache e report. */
    suspend fun apply(job: SyncJob, plan: SyncPlan, started: Long, reportId: String, onProgress: (Progress) -> Unit = {}): SyncReport {
        val (_, dst) = providers(job)
        val targetId = plan.targetPlaylistId.ifEmpty { throw ProviderException("No target playlist") }
        val unmatched = ArrayList(plan.unmatched)
        val failed = ArrayList<String>()

        var added = 0
        if (plan.toAdd.isNotEmpty()) {
            onProgress(Progress(Progress.Step.ADDING, 0, plan.toAdd.size))
            // Verso una playlist un blocco di 50 e' una chiamata sola. Verso i "brani preferiti" ogni
            // brano e' una chiamata a parte: blocchi piccoli, cosi' l'avanzamento si muove ogni pochi
            // brani invece di restare fermo per minuti.
            val chunkSize = if (targetId == MusicProvider.LIKED_ID) 10 else 50
            var blockWaits = 0
            for (chunk in plan.toAdd.chunked(chunkSize)) {
                // Cosa si sta aggiungendo adesso, in scheda e nella vista dal vivo, come per la ricerca.
                onProgress(Progress(Progress.Step.ADDING, added, plan.toAdd.size, chunk.first().live()))
                while (true) {
                    try {
                        dst.addTracks(ctx, targetId, chunk)
                        added += chunk.size
                        chunk.forEach { SyncState.item(job.id, SyncState.LiveItem(it.live(), null, SyncState.Outcome.ADDED)) }
                        break
                    } catch (e: Exception) {
                        // Il servizio ha bloccato l'indirizzo e dice quanto aspettare: si aspetta, come
                        // nella ricerca, invece di dare per falliti brani che sono solo in attesa.
                        val asked = declaredWait(e.message)
                        if (asked != null && blockWaits < MAX_BLOCK_WAITS) {
                            blockWaits++
                            Diagnostics.log(ctx, "engine", "${dst.displayName} is blocked while adding: ${e.message?.take(160)}; waiting ${asked}s")
                            waitVisible(asked, onProgress)
                            continue
                        }
                        // Un blocco fallito (id non piu' valido, ecc.): riprova brano per brano.
                        for (t in chunk) {
                            try {
                                dst.addTracks(ctx, targetId, listOf(t)); added++
                                SyncState.item(job.id, SyncState.LiveItem(t.live(), null, SyncState.Outcome.ADDED))
                            } catch (e2: Exception) {
                                failed += "$t (${e2.message})"
                                SyncState.item(job.id, SyncState.LiveItem(t.live(), e2.message?.take(80), SyncState.Outcome.ADD_FAILED))
                            }
                        }
                        break
                    }
                }
                onProgress(Progress(Progress.Step.ADDING, added, plan.toAdd.size))
            }
        }

        var removed = 0
        if (plan.toRemove.isNotEmpty()) {
            onProgress(Progress(Progress.Step.REMOVING, 0, plan.toRemove.size, plan.toRemove.first().live()))
            var blockWaits = 0
            while (true) {
                try {
                    dst.removeTracks(ctx, targetId, plan.toRemove)
                    plan.toRemove.forEach { SyncState.item(job.id, SyncState.LiveItem(it.live(), null, SyncState.Outcome.REMOVED)) }
                    break
                }
                catch (e: Exception) {
                    val asked = declaredWait(e.message)
                    if (asked != null && blockWaits < MAX_BLOCK_WAITS) { blockWaits++; waitVisible(asked, onProgress); continue }
                    throw e
                }
            }
            removed = plan.toRemove.size
        }

        store.putMatches(plan.newMatches)
        // Quanto l'utente ha gia' sistemato dal report provvisorio non deve ricomparire.
        val provisional = store.report(reportId)?.takeIf { it.partial }
        if (provisional != null) {
            val stillOpen = provisional.unmatchedTracks.map { it.id }.toHashSet()
            unmatched.retainAll { it.id in stillOpen }
        }
        val reviews = provisional?.reviewTracks ?: plan.uncertain
        return SyncReport(
            id = reportId, jobId = job.id, jobName = job.name, startedEpoch = started,
            durationMs = System.currentTimeMillis() - started,
            sourceCount = plan.sourceCount, added = added, removed = removed,
            unmatched = unmatched.map { it.toString() } + failed,
            unmatchedTracks = unmatched,
            ignored = plan.ignored,
            reviewTracks = reviews,
            removedTracks = if (removed > 0) plan.toRemove else emptyList(),
            notes = plan.notes,
            suggestions = suggestionsFor(unmatched),
        )
    }

    // ------------------------------------------------------------------ revisione abbinamenti

    /** L'abbinamento incerto va bene: resta in cache, sparisce dalla lista da rivedere. */
    fun confirmMatch(reportId: String, review: MatchReview) {
        store.updateReport(reportId) { r -> r.copy(reviewTracks = r.reviewTracks.filter { it.source.id != review.source.id }) }
    }

    /**
     * L'abbinamento era sbagliato: toglie il brano errato dalla destinazione (dove l'API lo
     * permette), aggiunge quello scelto e corregge la cache per le sync future.
     */
    suspend fun replaceMatch(job: SyncJob, reportId: String?, review: MatchReview, chosen: Track) {
        val (src, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        if (dst.canRemoveTracks) {
            // Il brano errato letto dalla ricerca non ha l'itemId di playlist: lo si ripesca dalla destinazione.
            val present = dst.tracks(ctx, targetId).firstOrNull { it.id == review.chosen.id }
            if (present != null) dst.removeTracks(ctx, targetId, listOf(present))
        }
        dst.addTracks(ctx, targetId, listOf(chosen))
        store.putMatches(mapOf(Store.cacheKey(src.id, review.source.id, dst.id) to chosen.id))
        if (reportId != null) confirmMatch(reportId, review)
    }

    /** In anteprima non e' stato ancora scritto nulla: basta correggere la cache. */
    fun rematchInPlan(job: SyncJob, review: MatchReview, chosen: Track) {
        val (src, dst) = providers(job)
        store.putMatches(mapOf(Store.cacheKey(src.id, review.source.id, dst.id) to chosen.id))
    }

    /** Rimette nella destinazione i brani tolti da una sync a specchio. */
    suspend fun restoreRemoved(job: SyncJob, report: SyncReport) {
        val (_, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        if (report.removedTracks.isEmpty()) return
        dst.addTracks(ctx, targetId, report.removedTracks)
        store.updateReport(report.id) { r ->
            r.copy(removedTracks = emptyList(), notes = r.notes + ctx.getString(R.string.restored_note, report.removedTracks.size))
        }
    }

    // ------------------------------------------------------------------ risoluzione manuale

    /**
     * Ricerca manuale sulla destinazione per una query libera ("Artista - Titolo", solo titolo, o un link).
     * Con il brano d'origine [source] ogni risultato ha un punteggio di somiglianza (titolo, artista,
     * durata) e una provenienza: il catalogo dei brani, l'album del brano d'origine (una seconda
     * ricerca "artista album", quando l'album e' noto), o il catalogo largo (i video di YouTube), che
     * si interroga solo se i brani non hanno dato nulla di convincente. Le edizioni ripetute di uno
     * stesso brano compaiono una volta sola.
     */
    suspend fun searchOnTarget(job: SyncJob, query: String, source: Track? = null): TargetSearch {
        val (_, dst) = providers(job)
        fun hit(t: Track, kind: TargetSearch.Kind) = TargetSearch.Hit(t, if (source != null) Matcher.score(source, t) else 0.0, kind)
        // Il link del brano incollato: si aggiunge quello, letto dal servizio quando possibile.
        TrackLinks.parse(query)?.let { ref ->
            if (!TrackLinks.matches(ref.service, dst.serviceId)) throw ProviderException(ctx.getString(R.string.link_other_service, dst.label(ctx)))
            val t = runCatching { dst.track(ctx, ref.trackId) }.getOrNull()
            return TargetSearch(listOf(hit(t ?: dst.rehydrate(Track(id = ref.trackId, title = ctx.getString(R.string.link_track_unknown), album = ref.trackId)), TargetSearch.Kind.SONG)))
        }
        val (artists, title) = PlaylistFiles.splitArtistTitle(query)
        var songs = dst.search(ctx, Track(id = "", title = title, artists = artists))
        // "Artista - Titolo" senza un candidato convincente: il solo titolo a volte lo trova.
        fun convincing() = source == null || Matcher.bestScored(source, songs) != null
        if (artists.isNotEmpty() && (songs.isEmpty() || !convincing())) {
            songs = (songs + dst.search(ctx, Track(id = "", title = title))).distinctBy { it.id }
        }
        val hits = ArrayList<TargetSearch.Hit>()
        val seen = HashSet<String>()
        fun add(tracks: List<Track>, kind: TargetSearch.Kind) = dedupe(tracks).filter { seen.add(it.id) }.forEach { hits += hit(it, kind) }
        var album: String? = null
        var albumFound: Boolean? = null
        if (source != null) {
            source.album.trim().takeIf { it.isNotEmpty() }?.let { name ->
                val byAlbum = runCatching { dst.search(ctx, Track(id = "", title = name, artists = source.artists.take(1))) }.getOrDefault(emptyList())
                // Un servizio che non riporta l'album nei risultati non permette di dire se il disco c'e'.
                if ((byAlbum + songs).any { it.album.isNotBlank() }) {
                    val wanted = Matcher.normalizeTitle(name)
                    val ofAlbum = (byAlbum + songs).filter {
                        it.album.isNotBlank() && Matcher.similarity(Matcher.normalizeTitle(it.album), wanted) >= 0.8 &&
                            (source.artists.isEmpty() || Matcher.artistScore(source.artists, it.artists) >= 0.7)
                    }
                    album = name
                    albumFound = ofAlbum.isNotEmpty()
                    add(ofAlbum, TargetSearch.Kind.ALBUM)
                }
            }
        }
        add(songs, TargetSearch.Kind.SONG)
        if (source != null && !convincing()) {
            val wide = runCatching { dst.searchWide(ctx, source.copy(title = title, artists = artists.ifEmpty { source.artists })) }.getOrDefault(emptyList())
            add(wide, TargetSearch.Kind.VIDEO)
        }
        return TargetSearch(if (source != null) hits.sortedByDescending { it.score } else hits, album, albumFound)
    }

    /** Una riga per titolo e artisti: le edizioni ripetute di uno stesso brano non aiutano a scegliere. */
    private fun dedupe(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>()
        return tracks.filter { seen.add(Matcher.normalizeTitle(it.title) + "|" + it.artists.map { a -> Matcher.normalizeArtist(a) }.sorted().joinToString(",")) }
    }

    /**
     * L'utente ha scelto a mano il brano giusto: lo aggiunge alla destinazione, lo mette in cache
     * (cosi' le sync future lo riconoscono) e lo toglie dai non trovati del report.
     */
    suspend fun resolveManually(job: SyncJob, reportId: String, sourceTrack: Track, chosen: Track) {
        val (src, dst) = providers(job)
        val targetId = job.target.playlistId ?: throw ProviderException("No target playlist")
        dst.addTracks(ctx, targetId, listOf(chosen))
        store.putMatches(mapOf(Store.cacheKey(src.id, sourceTrack.id, dst.id) to chosen.id))
        dropUnmatched(reportId, sourceTrack)
    }

    /**
     * L'utente non vuole piu' vedere questo brano: e' assente dalla destinazione, e lo resta per ogni
     * sync verso quel servizio, non solo per questa (come gli abbinamenti scelti a mano).
     */
    fun ignore(job: SyncJob, reportId: String, sourceTrack: Track) {
        store.upsertJob(job.copy(ignoredSourceIds = (job.ignoredSourceIds + sourceTrack.id).distinct()))
        runCatching { val (src, dst) = providers(job); store.putIgnored(src.id, sourceTrack.id, dst.id) }
        dropUnmatched(reportId, sourceTrack)
    }

    private fun dropUnmatched(reportId: String, track: Track) {
        store.updateReport(reportId) { r ->
            r.copy(
                unmatchedTracks = r.unmatchedTracks.filter { it.id != track.id },
                unmatched = r.unmatched.filter { it != track.toString() },
                suggestions = r.suggestions - track.id,
            )
        }
    }
}
