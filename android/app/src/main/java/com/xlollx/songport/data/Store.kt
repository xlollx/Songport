package com.xlollx.songport.data

import android.content.Context
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.net.json
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class Settings(
    val notifyOnSync: Boolean = true,
    /** Client ID inseriti dall'utente, per servizio (vuoto = usa quello della build). */
    val clientIds: Map<String, String> = emptyMap(),
    /**
     * Client secret, solo per i flussi "installed app" che lo richiedono (Google).
     * Non e' un vero segreto: Google stesso dichiara che nelle app installate non e' confidenziale,
     * ed e' l'utente a incollare il proprio. Resta comunque sul dispositivo.
     */
    val clientSecrets: Map<String, String> = emptyMap(),
    val deezerRedirectUrl: String = "",
    /** Account aggiuntivi per servizio: "spotify" -> ["2", "3"]. */
    val extraAccounts: Map<String, List<String>> = emptyMap(),
    /** Connectors shown in Accounts, in order: provider ids ("spotify", "spotify@2", "csv"). */
    val connectors: List<String> = emptyList(),
    /** User-given connector names, by provider id. */
    val connectorNames: Map<String, String> = emptyMap(),
    /** True once existing connections have been turned into connectors (one-time migration). */
    val connectorsInit: Boolean = false,
    /** Richiedi impronta/PIN all'apertura dell'app. */
    val appLock: Boolean = false,
    /** Schermata di benvenuto gia' vista. */
    val onboardingDone: Boolean = false,
    /** Cartella (URI ad albero del selettore di sistema) dove copiare i backup; vuoto = nessuna. */
    val backupFolder: String = "",
    /** Ogni quanto copiare i backup nella cartella; MANUAL = solo a richiesta. */
    val backupSchedule: com.xlollx.songport.model.Schedule = com.xlollx.songport.model.Schedule.MANUAL,
    /** Sync che ha fatto scattare la richiesta di contributo, finche' l'utente non risponde. */
    val supportPromptReport: String? = null,
    /** Richiesta di contributo gia' mostrata e chiusa: non torna piu'. */
    val supportPromptDone: Boolean = false,
)

/** Unita' di quota consumate in un giorno (chiave = data nel fuso del servizio). */
@Serializable
data class QuotaDay(val day: String, val units: Long)

@Serializable
data class StoreData(
    val jobs: List<SyncJob> = emptyList(),
    /** Ultimi report, dal piu' recente. */
    val reports: List<SyncReport> = emptyList(),
    /** Cache abbinamenti: "srcProvider:trackId>dstProvider" -> id brano sulla destinazione. */
    val matchCache: Map<String, String> = emptyMap(),
    /** Ricerche senza esito, stessa chiave -> quando (epoch ms): non si ripetono per una settimana. */
    val missCache: Map<String, Long> = emptyMap(),
    /** Source tracks the user declared absent from a destination service (same keys as the caches). */
    val ignoredTracks: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    /** Quota API stimata per servizio (oggi YouTube). */
    val quota: Map<String, QuotaDay> = emptyMap(),
)

/**
 * Stato persistente dell'app: un unico file JSON (piccolo), esposto come StateFlow alla UI.
 * Le scritture sono serializzate su un thread dedicato e atomiche (tmp + rename).
 */
class Store private constructor(context: Context) {
    private val file = File(context.filesDir, "store.json")
    private val tmp = File(context.filesDir, "store.json.tmp")
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private var pendingWrite: java.util.concurrent.ScheduledFuture<*>? = null
    private val lock = Any()

    private val _state = MutableStateFlow(load().also { Providers.configure(it.settings.extraAccounts, it.settings.connectorNames, it.settings.connectors) })
    val state: StateFlow<StoreData> get() = _state
    val data: StoreData get() = _state.value

    init { migrateConnectors(context) }

    /** First run of the connector model: whatever is connected or used by a sync becomes a connector. */
    private fun migrateConnectors(ctx: Context) {
        val d = data
        if (d.settings.connectorsInit) return
        val ids = Providers.all().filter { p ->
            p.slot.isNotEmpty() ||
                d.jobs.any { it.source.provider == p.id || it.target.provider == p.id } ||
                (p.requiresAuth && runCatching { p.isConnected(ctx) }.getOrDefault(false))
        }.map { it.id }
        update { s -> s.copy(settings = s.settings.copy(connectors = ids, connectorsInit = true)) }
    }

    private fun load(): StoreData = try {
        if (file.exists()) json.decodeFromString<StoreData>(file.readText()) else StoreData()
    } catch (e: Exception) {
        StoreData()
    }

    fun update(fn: (StoreData) -> StoreData) {
        val next: StoreData
        synchronized(lock) {
            next = fn(_state.value)
            _state.value = next
        }
        Providers.configure(next.settings.extraAccounts, next.settings.connectorNames, next.settings.connectors)
        scheduleWrite()
    }

    /**
     * Le scritture si accumulano per mezzo secondo e poi si scrive l'ultimo stato: digitare nelle
     * impostazioni o aggiornare la cache brano per brano non riscrive il file a ogni tocco.
     */
    private fun scheduleWrite() {
        synchronized(lock) {
            pendingWrite?.cancel(false)
            pendingWrite = writer.schedule({ writeNow() }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun writeNow() {
        val snapshot = _state.value
        try {
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) { file.writeText(json.encodeToString(snapshot)); tmp.delete() }
        } catch (_: Exception) { }
    }

    /** Scrive subito (es. prima che il processo venga fermato). */
    fun flush() {
        synchronized(lock) { pendingWrite?.cancel(false); pendingWrite = null }
        writeNow()
    }

    // --- helper di comodo ---

    fun job(id: String): SyncJob? = data.jobs.firstOrNull { it.id == id }

    fun upsertJob(job: SyncJob) = update { d ->
        if (d.jobs.any { it.id == job.id }) d.copy(jobs = d.jobs.map { if (it.id == job.id) job else it })
        else d.copy(jobs = d.jobs + job)
    }

    fun deleteJob(id: String) = update { d -> d.copy(jobs = d.jobs.filter { it.id != id }) }

    /** Aggiunge il report o, se un provvisorio con lo stesso id esiste gia', lo sostituisce al suo posto. */
    fun addReport(report: SyncReport) = update { d ->
        val reports = if (d.reports.any { it.id == report.id }) d.reports.map { if (it.id == report.id) report else it }
            else (listOf(report) + d.reports).take(MAX_REPORTS)
        d.copy(
            reports = reports,
            jobs = d.jobs.map { if (it.id == report.jobId) it.copy(lastRunEpoch = report.startedEpoch, lastReportId = report.id) else it },
        )
    }

    fun report(id: String): SyncReport? = data.reports.firstOrNull { it.id == id }

    fun updateReport(id: String, fn: (SyncReport) -> SyncReport) = update { d ->
        d.copy(reports = d.reports.map { if (it.id == id) fn(it) else it })
    }

    fun updateSettings(fn: (Settings) -> Settings) = update { d -> d.copy(settings = fn(d.settings)) }

    fun cachedMatch(srcProvider: String, srcTrackId: String, dstProvider: String): String? =
        data.matchCache[cacheKey(srcProvider, srcTrackId, dstProvider)]

    /** "Ignore" in the review: this source track is not on that service, whichever sync meets it. */
    fun isIgnored(srcProvider: String, srcTrackId: String, dstProvider: String): Boolean =
        cacheKey(srcProvider, srcTrackId, dstProvider) in data.ignoredTracks

    fun putIgnored(srcProvider: String, srcTrackId: String, dstProvider: String) =
        update { d -> d.copy(ignoredTracks = d.ignoredTracks + cacheKey(srcProvider, srcTrackId, dstProvider)) }

    fun putMatches(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        update { d ->
            var cache = d.matchCache + entries
            if (cache.size > MAX_CACHE) cache = cache.entries.drop(cache.size - MAX_CACHE).associate { it.key to it.value }
            // Un abbinamento trovato (o scelto a mano) cancella il ricordo della ricerca fallita.
            d.copy(matchCache = cache, missCache = d.missCache - entries.keys)
        }
    }

    /** True se questa ricerca e' fallita da poco: inutile ripeterla a ogni esecuzione o ripresa. */
    fun cachedMiss(srcProvider: String, srcTrackId: String, dstProvider: String): Boolean {
        val at = data.missCache[cacheKey(srcProvider, srcTrackId, dstProvider)] ?: return false
        return System.currentTimeMillis() - at < MISS_TTL_MS
    }

    fun putMisses(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        update { d ->
            var cache = d.missCache.filterValues { now - it < MISS_TTL_MS } + keys.associateWith { now }
            if (cache.size > MAX_CACHE) cache = cache.entries.drop(cache.size - MAX_CACHE).associate { it.key to it.value }
            d.copy(missCache = cache)
        }
    }

    companion object {
        private const val MAX_REPORTS = 200
        private const val WRITE_DELAY_MS = 500L
        private const val MAX_CACHE = 20_000
        /** Una ricerca fallita si ripete dopo una settimana: i cataloghi cambiano, ma non ogni giorno. */
        private const val MISS_TTL_MS = 7L * 24 * 3_600_000

        fun cacheKey(srcProvider: String, srcTrackId: String, dstProvider: String) = "$srcProvider:$srcTrackId>$dstProvider"

        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }
}
