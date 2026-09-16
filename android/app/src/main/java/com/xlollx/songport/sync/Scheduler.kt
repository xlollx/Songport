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

/** Stato in memoria delle sync in corso (per la UI). */
object SyncState {
    private val _running = MutableStateFlow<Map<String, Progress?>>(emptyMap())
    val running: StateFlow<Map<String, Progress?>> get() = _running

    fun start(jobId: String) { _running.value = _running.value + (jobId to null) }
    fun progress(jobId: String, p: Progress) { _running.value = _running.value + (jobId to p) }
    fun finish(jobId: String) { _running.value = _running.value - jobId }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        val ctx = applicationContext
        val store = Store.get(ctx)
        val jobs = if (jobId == ALL) store.data.jobs.filter { it.enabled } else listOfNotNull(store.job(jobId))
        val engine = SyncEngine(ctx)
        // Le sync manuali possono durare piu' dei 10 minuti concessi a un lavoro in background
        // (migliaia di brani verso YouTube): in primo piano, con una notifica di avanzamento.
        if (!scheduled) runCatching { setForeground(foregroundInfo(ctx.getString(R.string.running))) }
        for (job in jobs) {
            if (job.id in SyncState.running.value) continue
            SyncState.start(job.id)
            Diagnostics.log(ctx, "sync", "start ${job.name} (${if (scheduled) "scheduled" else "manual"})")
            try {
                val report = engine.run(job) { p ->
                    SyncState.progress(job.id, p)
                    if (!scheduled) updateProgress(job.name, progressText(ctx, p))
                }
                Diagnostics.log(ctx, "sync", "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size}" + (report.error?.let { " ERR $it" } ?: ""))
                val notable = report.added > 0 || report.removed > 0 || !report.ok
                if (store.data.settings.notifyOnSync && (scheduled || !report.ok) && notable) {
                    Notifications.syncResult(ctx, report)
                }
            } finally {
                SyncState.finish(job.id)
            }
        }
        SyncWidget.refresh(ctx)
        store.flush()
        return Result.success()
    }

    private fun foregroundInfo(title: String, text: String = ""): ForegroundInfo {
        val n = Notifications.progress(applicationContext, title, text)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifications.PROGRESS_ID, n)
    }

    /** Aggiorna la notifica di avanzamento (stesso id del servizio in primo piano). */
    private fun updateProgress(title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                ?.notify(Notifications.PROGRESS_ID, Notifications.progress(ctx, title, text))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext.getString(R.string.running))

    private fun progressText(ctx: Context, p: Progress): String = when (p.step) {
        Progress.Step.FETCH_SOURCE -> ctx.getString(R.string.progress_fetch_source)
        Progress.Step.CREATE_TARGET -> ctx.getString(R.string.progress_create_target)
        Progress.Step.FETCH_TARGET -> ctx.getString(R.string.progress_fetch_target)
        Progress.Step.MATCHING -> ctx.getString(R.string.progress_matching, p.done, p.total)
        Progress.Step.ADDING -> ctx.getString(R.string.progress_adding, p.total)
        Progress.Step.REMOVING -> ctx.getString(R.string.progress_removing, p.total)
        else -> ""
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_SCHEDULED = "scheduled"
        const val ALL = "*"
    }
}

object Scheduler {
    private fun name(jobId: String) = "sync-$jobId"

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
    }

    /** Esecuzione manuale immediata (di una sync o di tutte con SyncWorker.ALL). */
    fun runNow(ctx: Context, jobId: String) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_JOB to jobId, SyncWorker.KEY_SCHEDULED to false))
            .addTag("sync")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("run-$jobId", ExistingWorkPolicy.KEEP, req)
    }
}
