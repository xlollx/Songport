package com.xlollx.songport.server

import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.isTransientNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs syncs, by hand from the page or by their schedule. What WorkManager does in the app: a loop
 * that wakes every minute and starts the syncs whose interval has passed since their last run, a
 * few at a time.
 */
class Runner(private val env: ServerEnv) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(MAX_PARALLEL)
    /** A scheduled sync that found no network is tried again sooner than its next interval. */
    private val retryAt = ConcurrentHashMap<String, Long>()

    fun isRunning(jobId: String) = jobId in SyncState.running.value

    /** Starts [job] now unless it is already running. */
    fun start(job: SyncJob, scheduled: Boolean = false) {
        if (isRunning(job.id)) return
        SyncState.start(job.id)
        scope.launch {
            slots.withPermit { run(job, scheduled) }
        }
    }

    private suspend fun run(job: SyncJob, scheduled: Boolean) {
        try {
            env.log("sync", "start ${job.name} (${if (scheduled) "scheduled" else "manual"})")
            val report = SyncEngine(env).run(job, unattended = scheduled) { p ->
                SyncState.progress(job.id, p)
                if (p.step == Progress.Step.WAITING) SyncState.stopCheck(env, job.id) else SyncState.checkpoint(env, job.id)
            }
            env.log(
                "sync",
                "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size} src=${report.sourceCount} dst=${report.targetCount}" +
                    (report.error?.let { " ERR $it" } ?: ""),
            )
            if (scheduled && !report.ok && isTransientNetwork(report.error)) {
                retryAt[job.id] = System.currentTimeMillis() + RETRY_MS
                env.log("sync", "no network for ${job.name}: will retry")
            } else retryAt.remove(job.id)
        } catch (e: Exception) {
            env.log("sync", "${job.name}: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            SyncState.finish(job.id)
            env.store.flush()
        }
    }

    /** True when [job] is due by its schedule at [now]. */
    fun isDue(job: SyncJob, now: Long): Boolean {
        if (!job.enabled || job.schedule == Schedule.MANUAL) return false
        retryAt[job.id]?.let { if (now >= it) return true }
        return now - job.lastRunEpoch >= job.schedule.minutes * 60_000
    }

    /** The scheduler: once a minute, the syncs that are due. */
    fun startScheduler() {
        scope.launch {
            delay(STARTUP_DELAY_MS)
            while (isActive) {
                val now = System.currentTimeMillis()
                env.store.data.jobs.filter { isDue(it, now) && !isRunning(it.id) }.forEach { start(it, scheduled = true) }
                delay(TICK_MS)
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL = 2
        private const val TICK_MS = 60_000L
        private const val STARTUP_DELAY_MS = 15_000L
        private const val RETRY_MS = 15 * 60_000L

        /** When [job] runs next by its schedule, or null for a manual one. */
        fun nextRun(job: SyncJob): Long? =
            if (!job.enabled || job.schedule == Schedule.MANUAL) null
            else maxOf(System.currentTimeMillis(), job.lastRunEpoch + job.schedule.minutes * 60_000)
    }
}
