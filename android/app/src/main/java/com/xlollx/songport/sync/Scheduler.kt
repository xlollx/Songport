package com.xlollx.songport.sync

import com.xlollx.songport.providers.*
import android.content.Context
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
import java.util.concurrent.TimeUnit

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
        // Every worker shares one notification (see SyncNotice): its content is all the syncs running.
        val foreground = !scheduled && runCatching { setForeground(SyncNotice.foregroundInfo(ctx)) }.isSuccess
        if (foreground) SyncNotice.enter(ctx)
        try {
            for (job in jobs) {
                if (job.id in SyncState.running.value) continue
                SyncState.start(job.id)
                Diagnostics.log(ctx, "sync", "start ${job.name} (${if (scheduled) "scheduled" else "manual"})")
                try {
                    val report = try {
                        engine.run(job, unattended = scheduled) { p ->
                            // The shared notification follows SyncState: this update reaches it too.
                            SyncState.progress(job.id, p)
                            // Pausa o stop chiesti dall'utente: qui, fra un passo e l'altro. L'attesa imposta
                            // dal servizio e' tempo che deve passare comunque: in pausa il conto alla rovescia
                            // continua, e' l'abbinamento che si ferma appena l'attesa finisce.
                            if (p.step == Progress.Step.WAITING) SyncState.stopCheck(ctx, job.id) else SyncState.checkpoint(ctx, job.id)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Fermato dal sistema, non dall'utente: WorkManager riprende il lavoro da solo e la
                        // cache degli abbinamenti evita di rifare le ricerche. Lo si annota, non e' un errore.
                        val reason = if (Build.VERSION.SDK_INT >= 31) " (stop reason $stopReason)" else ""
                        Diagnostics.log(ctx, "sync", "paused by the system$reason ${job.name}: will resume")
                        throw e
                    }
                    Diagnostics.log(ctx, "sync", "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size} src=${report.sourceCount} dst=${report.targetCount}" + (report.error?.let { " ERR $it" } ?: ""))
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
        } finally {
            if (foreground) SyncNotice.leave(ctx)
        }
        SyncWidget.refresh(ctx)
        store.flush()
        return if (retryLater) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncNotice.foregroundInfo(applicationContext)

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

/** The weekly look for entries the services no longer play (see the Unplayable tracks tool): a notice, never a change. */
private const val UNPLAYABLE_NOTICE_ID = 4243

class UnplayableWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        var count = 0
        var services = 0
        val failed = ArrayList<String>()
        for (p in Providers.connectors().filter { it.detectsUnplayable && it.isConnected(ctx) }) {
            try {
                val scan = Tools.scanUnplayable(ctx, p, Tools.unplayableTargets(ctx, p))
                count += scan.count
                services++
                failed += scan.failed.map { it.reason }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                failed += e.message ?: e.javaClass.simpleName
            }
        }
        Diagnostics.log(ctx, "unplayable", "weekly check: $count unplayable in $services services" + (if (failed.isEmpty()) "" else "; ${failed.size} playlists not read"))
        if (count > 0) Notifications.unplayable(ctx, UNPLAYABLE_NOTICE_ID, count)
        return if (services == 0 && failed.isNotEmpty() && failed.all { isTransientNetwork(it) }) Result.retry() else Result.success()
    }
}

object Scheduler {
    /** The unique periodic work of a sync (the UI tests read its state). */
    internal fun name(jobId: String) = "sync-$jobId"
    private const val BACKUP_WORK = "backup-export"
    private const val UNPLAYABLE_WORK = "unplayable-check"

    /** Allinea il controllo settimanale dei brani non riproducibili alle impostazioni. */
    fun applyUnplayable(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Store.get(ctx).data.settings.unplayableCheck) {
            wm.cancelUniqueWork(UNPLAYABLE_WORK)
            return
        }
        com.xlollx.songport.NotificationAsk.onUse()
        val req = PeriodicWorkRequestBuilder<UnplayableWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNPLAYABLE_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

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
        com.xlollx.songport.NotificationAsk.onUse()
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
        applyUnplayable(ctx)
    }

    /**
     * Esecuzione manuale immediata (di una sync o di tutte con SyncWorker.ALL). Senza vincolo di
     * rete: WorkManager ferma il lavoro ogni volta che il vincolo viene meno anche per un istante
     * (passaggio Wi-Fi/dati), e una sync lunga finiva "cancellata" a ogni cambio di rete. Se la
     * rete manca davvero, sono le chiamate ai servizi a fallire, con i loro tentativi.
     */
    fun runNow(ctx: Context, jobId: String) {
        com.xlollx.songport.NotificationAsk.onUse()
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
