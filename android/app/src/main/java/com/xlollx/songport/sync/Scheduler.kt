package com.xlollx.songport.sync

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.widget.SyncWidget
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/** Stato in memoria delle sync in corso (per la UI), con pausa e interruzione richieste dall'utente. */
object SyncState {
    private val _running = MutableStateFlow<Map<String, Progress?>>(emptyMap())
    val running: StateFlow<Map<String, Progress?>> get() = _running
    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    val paused: StateFlow<Set<String>> get() = _paused
    @Volatile private var stopRequested: Set<String> = emptySet()

    /** What happened to one source track during the run, for the live view. */
    enum class Outcome { FOUND, CACHED, NOT_FOUND, ADDED, ADD_FAILED, REMOVED }
    class LiveItem(val source: String, val result: String?, val outcome: Outcome)

    private val _items = MutableStateFlow<Map<String, List<LiveItem>>>(emptyMap())
    /** Tracks processed so far per running job, newest first (capped). */
    val items: StateFlow<Map<String, List<LiveItem>>> get() = _items

    fun start(jobId: String) { _running.value = _running.value + (jobId to null); _items.value = _items.value + (jobId to emptyList()) }
    fun progress(jobId: String, p: Progress) { _running.value = _running.value + (jobId to p) }
    fun item(jobId: String, item: LiveItem) {
        val cur = _items.value[jobId].orEmpty()
        _items.value = _items.value + (jobId to (listOf(item) + cur).take(MAX_ITEMS))
    }
    fun finish(jobId: String) {
        _running.value = _running.value - jobId
        _paused.value = _paused.value - jobId
        _items.value = _items.value - jobId
        stopRequested = stopRequested - jobId
    }

    private const val MAX_ITEMS = 1000

    fun pause(jobId: String) { _paused.value = _paused.value + jobId }
    fun resume(jobId: String) { _paused.value = _paused.value - jobId }
    fun stop(jobId: String) { stopRequested = stopRequested + jobId; resume(jobId) }
    fun isPaused(jobId: String) = jobId in _paused.value

    /**
     * Chiamato dal motore a ogni passo: attende finche' la sync e' in pausa e interrompe se l'utente
     * l'ha fermata. Bloccante di proposito: il motore lavora su un thread di WorkManager.
     */
    fun checkpoint(ctx: Context, jobId: String) {
        while (jobId in _paused.value && jobId !in stopRequested) Thread.sleep(250)
        stopCheck(ctx, jobId)
    }

    /** Solo l'interruzione, senza attendere la pausa: per i passi che devono scorrere comunque. */
    fun stopCheck(ctx: Context, jobId: String) {
        if (jobId in stopRequested) throw SyncStoppedException(ctx.getString(R.string.sync_stopped))
    }
}

class SyncStoppedException(message: String) : Exception(message)

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        val ctx = applicationContext
        val store = Store.get(ctx)
        val batch = inputData.getStringArray(KEY_JOBS)
        val jobs = when {
            batch != null -> batch.mapNotNull { store.job(it) }
            jobId == ALL -> store.data.jobs.filter { it.enabled }
            else -> listOfNotNull(store.job(jobId))
        }
        val engine = SyncEngine(ctx)
        // Rete ancora assente al risveglio: non e' un esito della sync, la si rimette in coda.
        var retryLater = false
        // Le sync manuali possono durare piu' dei 10 minuti concessi a un lavoro in background
        // (migliaia di brani verso YouTube): in primo piano, con una notifica di avanzamento.
        if (!scheduled) runCatching { setForeground(foregroundInfo(ctx.getString(R.string.running))) }
        for (job in jobs) {
            if (job.id in SyncState.running.value) continue
            SyncState.start(job.id)
            Diagnostics.log(ctx, "sync", "start ${job.name} (${if (scheduled) "scheduled" else "manual"})")
            try {
                val report = try {
                    engine.run(job, unattended = scheduled) { p ->
                        SyncState.progress(job.id, p)
                        if (!scheduled) updateProgress(job, p)
                        // Pausa o stop chiesti dall'utente: qui, fra un passo e l'altro. L'attesa imposta
                        // dal servizio e' tempo che deve passare comunque: in pausa il conto alla rovescia
                        // continua, e' l'abbinamento che si ferma appena l'attesa finisce.
                        if (SyncState.isPaused(job.id) && !scheduled) updateProgress(job, p, paused = true)
                        if (p.step == Progress.Step.WAITING) SyncState.stopCheck(ctx, job.id) else SyncState.checkpoint(ctx, job.id)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Fermato dal sistema, non dall'utente: WorkManager riprende il lavoro da solo e la
                    // cache degli abbinamenti evita di rifare le ricerche. Lo si annota, non e' un errore.
                    val reason = if (Build.VERSION.SDK_INT >= 31) " (stop reason $stopReason)" else ""
                    Diagnostics.log(ctx, "sync", "paused by the system$reason ${job.name}: will resume")
                    throw e
                }
                Diagnostics.log(ctx, "sync", "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size}" + (report.error?.let { " ERR $it" } ?: ""))
                // Una sync programmata morta per mancanza di rete non ha "fallito": WorkManager la
                // riprova col suo backoff, quindi niente notifica d'errore per una giornata persa.
                val networkGone = scheduled && !report.ok && isTransientNetwork(report.error)
                if (networkGone) {
                    retryLater = true
                    Diagnostics.log(ctx, "sync", "no network for ${job.name}: will retry")
                }
                SupportPrompt.offer(store, report)
                val notable = report.added > 0 || report.removed > 0 || !report.ok
                if (!networkGone && store.data.settings.notifyOnSync && (scheduled || !report.ok) && notable) {
                    Notifications.syncResult(ctx, report)
                }
            } finally {
                SyncState.finish(job.id)
            }
        }
        SyncWidget.refresh(ctx)
        store.flush()
        return if (retryLater) Result.retry() else Result.success()
    }

    /** One notification per worker: syncs started separately run side by side, each with its own bar. */
    private val notificationId: Int get() = Notifications.progressId(inputData.getString(KEY_JOB))

    private fun foregroundInfo(title: String, text: String = ""): ForegroundInfo {
        val n = Notifications.progress(applicationContext, title, text)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notificationId, n)
    }

    /** Aggiorna la notifica di avanzamento (stesso id del servizio in primo piano): barra, percentuale, pausa e stop. */
    private fun updateProgress(job: SyncJob, p: Progress, paused: Boolean = false) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val text = (if (paused) ctx.getString(R.string.sync_paused) + " · " else "") + progressText(ctx, p) +
            (p.percent?.let { " · $it%" } ?: "")
        val others = (SyncState.running.value.keys - job.id).size
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                ?.notify(notificationId, Notifications.progress(ctx, job.name, text, p.done, p.total, job.id, paused, others))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext.getString(R.string.running))

    private fun progressText(ctx: Context, p: Progress): String = when (p.step) {
        Progress.Step.FETCH_SOURCE -> ctx.getString(R.string.progress_fetch_source)
        Progress.Step.CREATE_TARGET -> ctx.getString(R.string.progress_create_target)
        Progress.Step.FETCH_TARGET -> ctx.getString(R.string.progress_fetch_target)
        Progress.Step.MATCHING -> ctx.getString(R.string.progress_matching, p.done, p.total) + (p.label?.let { " · $it" } ?: "")
        Progress.Step.WAITING -> ctx.getString(R.string.progress_waiting, (p.total - p.done).coerceAtLeast(0))
        Progress.Step.ADDING -> ctx.getString(R.string.progress_adding, p.total) + (p.label?.let { " · $it" } ?: "")
        Progress.Step.REMOVING -> ctx.getString(R.string.progress_removing, p.total) + (p.label?.let { " · $it" } ?: "")
        else -> ""
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_SCHEDULED = "scheduled"
        const val ALL = "*"
        /** Several syncs run one after another in a single worker (a batch transfer). */
        const val KEY_JOBS = "jobs"
    }
}

/** Copia periodica dei backup nella cartella scelta dall'utente (vedi [BackupExport]). */
private const val BACKUP_NOTICE_ID = 4242

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val r = BackupExport.run(ctx)
        if (r.folderMissing) {
            Notifications.notice(ctx, BACKUP_NOTICE_ID, ctx.getString(R.string.backup_folder_title), ctx.getString(R.string.backup_folder_missing))
            return Result.success()
        }
        // Network gone on every service: not an outcome, WorkManager tries again with its backoff.
        return if (r.services == 0 && r.failed.isNotEmpty() && r.failed.all { isTransientNetwork(it) }) Result.retry() else Result.success()
    }
}

object Scheduler {
    private fun name(jobId: String) = "sync-$jobId"
    private const val BACKUP_WORK = "backup-export"

    /** Allinea il lavoro periodico di copia dei backup alle impostazioni. */
    fun applyBackup(ctx: Context) {
        val s = Store.get(ctx).data.settings
        val wm = WorkManager.getInstance(ctx)
        if (s.backupFolder.isBlank() || s.backupSchedule == Schedule.MANUAL) {
            wm.cancelUniqueWork(BACKUP_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(s.backupSchedule.minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(BACKUP_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Allinea il lavoro periodico di WorkManager alla configurazione della sync. */
    fun apply(ctx: Context, job: SyncJob) {
        val wm = WorkManager.getInstance(ctx)
        if (!job.enabled || job.schedule == Schedule.MANUAL) {
            wm.cancelUniqueWork(name(job.id))
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (job.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(job.schedule.minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_JOB to job.id, SyncWorker.KEY_SCHEDULED to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag("sync")
            .build()
        wm.enqueueUniquePeriodicWork(name(job.id), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context, jobId: String) = WorkManager.getInstance(ctx).cancelUniqueWork(name(jobId))

    fun applyAll(ctx: Context) {
        val store = Store.get(ctx)
        store.data.jobs.forEach { apply(ctx, it) }
        applyBackup(ctx)
    }

    /**
     * Esecuzione manuale immediata (di una sync o di tutte con SyncWorker.ALL). Senza vincolo di
     * rete: WorkManager ferma il lavoro ogni volta che il vincolo viene meno anche per un istante
     * (passaggio Wi-Fi/dati), e una sync lunga finiva "cancellata" a ogni cambio di rete. Se la
     * rete manca davvero, sono le chiamate ai servizi a fallire, con i loro tentativi.
     */
    fun runNow(ctx: Context, jobId: String) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_JOB to jobId, SyncWorker.KEY_SCHEDULED to false))
            .addTag("sync")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("run-$jobId", ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Several syncs in a row, in one worker: a batch transfer of many playlists would otherwise
     * start them all at once and hit the services' rate limits together.
     */
    fun runInSequence(ctx: Context, jobIds: List<String>) {
        if (jobIds.isEmpty()) return
        val batchId = "batch-" + java.util.UUID.randomUUID()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_JOB to batchId, SyncWorker.KEY_JOBS to jobIds.toTypedArray(), SyncWorker.KEY_SCHEDULED to false))
            .addTag("sync")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("run-$batchId", ExistingWorkPolicy.KEEP, req)
    }
}
