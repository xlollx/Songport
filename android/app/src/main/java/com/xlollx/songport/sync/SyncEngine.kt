package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncPlan
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
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
class SyncEngine(private val ctx: Context) {
    private val store = Store.get(ctx)

    suspend fun run(job: SyncJob, onProgress: (Progress) -> Unit = {}): SyncReport {
        val started = System.currentTimeMillis()
        val reportId = UUID.randomUUID().toString()
        val report = try {
            val plan = plan(job, onProgress)
            apply(job, plan, started, reportId, onProgress)
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
        val allSource = src.tracks(ctx, srcPlaylistId)
        val ignoredIds = job.ignoredSourceIds.toHashSet()
        val srcTracks = allSource.filter { it.id !in ignoredIds }
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
            val created = dst.createPlaylist(ctx, name, MusicProvider.DESCRIPTION)
            targetId = created.id
            targetCreated = true
            store.upsertJob(job.copy(target = job.target.copy(playlistId = created.id, playlistName = created.name)))
        }

        if (dst.serviceId == LocalFilesProvider.serviceId) notes += ctx.getString(R.string.note_file_target)

        onProgress(Progress(Progress.Step.FETCH_TARGET))
        // A playlist created a moment ago is empty: skip the fetch (saves quota, and YouTube's Data API
        // may not even know the playlist yet).
        val dstTracks = if (targetCreated) emptyList() else dst.tracks(ctx, targetId)
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
        searchMissing(src, dst, toSearch, onProgress) { s, found, score ->
            if (found != null) {
                if (found.id !in matchedDstIds) toAdd[found.id] = found
                matchedDstIds += found.id
                newCache[Store.cacheKey(src.id, s.id, dst.id)] = found.id
                if (score != null && score < Matcher.REVIEW_THRESHOLD) uncertain += MatchReview(s, found, score)
            } else unmatched += s
        }

        // 3) rimozioni: solo cio' che nella destinazione non corrisponde a nulla dell'origine
        var toRemove: List<Track> = emptyList()
        if (job.mirrorRemovals) {
            val extra = dstTracks.filter { it.id !in matchedDstIds }
            when {
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

    /** Cerca sulla destinazione i brani mancanti, usando la cache quando c'e'. */
    private suspend fun searchMissing(
        src: MusicProvider, dst: MusicProvider, toSearch: List<Track>,
        onProgress: (Progress) -> Unit, onResult: (Track, Track?, Double?) -> Unit,
    ) {
        var consecutiveErrors = 0
        toSearch.forEachIndexed { i, s ->
            onProgress(Progress(Progress.Step.MATCHING, i + 1, toSearch.size))
            var score: Double? = null
            var found: Track? = store.cachedMatch(src.id, s.id, dst.id)?.let { cachedId ->
                // Abbinato in passato (o confermato a mano): lo aggiungiamo senza ripetere la ricerca.
                dst.rehydrate(s.copy(id = cachedId, uri = null, itemId = null))
            }
            if (found == null) {
                val candidates = try {
                    dst.search(ctx, s).also { consecutiveErrors = 0 }
                } catch (e: ProviderException) {
                    consecutiveErrors++
                    val msg = e.message ?: ""
                    // Errori sistemici (quota, sessione scaduta): inutile insistere.
                    if (consecutiveErrors >= 3 || msg.contains("quota", true) || msg.contains("reconnect", true)) throw e
                    onResult(s, null, null)
                    return@forEachIndexed
                }
                val best = Matcher.bestScored(s, candidates)
                found = best?.track
                score = best?.score
            }
            onResult(s, found, score)
        }
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
            for (chunk in plan.toAdd.chunked(50)) {
                try {
                    dst.addTracks(ctx, targetId, chunk)
                    added += chunk.size
                } catch (e: Exception) {
                    // Un blocco fallito (id non piu' valido, ecc.): riprova brano per brano.
                    for (t in chunk) {
                        try { dst.addTracks(ctx, targetId, listOf(t)); added++ }
                        catch (e2: Exception) { failed += "$t (${e2.message})" }
                    }
                }
                onProgress(Progress(Progress.Step.ADDING, added, plan.toAdd.size))
            }
        }

        var removed = 0
        if (plan.toRemove.isNotEmpty()) {
            onProgress(Progress(Progress.Step.REMOVING, 0, plan.toRemove.size))
            dst.removeTracks(ctx, targetId, plan.toRemove)
            removed = plan.toRemove.size
        }

        store.putMatches(plan.newMatches)
        return SyncReport(
            id = reportId, jobId = job.id, jobName = job.name, startedEpoch = started,
            durationMs = System.currentTimeMillis() - started,
            sourceCount = plan.sourceCount, added = added, removed = removed,
            unmatched = unmatched.map { it.toString() } + failed,
            unmatchedTracks = unmatched,
            ignored = plan.ignored,
            reviewTracks = plan.uncertain,
            removedTracks = if (removed > 0) plan.toRemove else emptyList(),
            notes = plan.notes,
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

    /** Candidati sulla destinazione per una query libera ("Artista - Titolo" o solo titolo). */
    suspend fun searchOnTarget(job: SyncJob, query: String): List<Track> {
        val (_, dst) = providers(job)
        val (artists, title) = PlaylistFiles.splitArtistTitle(query)
        return dst.search(ctx, Track(id = "", title = title, artists = artists))
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

    /** L'utente non vuole piu' vedere questo brano: finisce fra gli ignorati del job. */
    fun ignore(job: SyncJob, reportId: String, sourceTrack: Track) {
        store.upsertJob(job.copy(ignoredSourceIds = (job.ignoredSourceIds + sourceTrack.id).distinct()))
        dropUnmatched(reportId, sourceTrack)
    }

    private fun dropUnmatched(reportId: String, track: Track) {
        store.updateReport(reportId) { r ->
            r.copy(
                unmatchedTracks = r.unmatchedTracks.filter { it.id != track.id },
                unmatched = r.unmatched.filter { it != track.toString() },
            )
        }
    }
}
