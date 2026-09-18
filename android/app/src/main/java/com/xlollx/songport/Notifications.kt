package com.xlollx.songport

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xlollx.songport.model.SyncReport

object Notifications {
    private const val CHANNEL_ID = "sync_results"
    const val PROGRESS_CHANNEL_ID = "sync_progress"
    const val PROGRESS_ID = 1001
    private const val PROGRESS_GROUP = "sync_progress"

    /**
     * Each running sync has its own progress notification: two workers posting under one id would
     * overwrite each other and only the last to speak would be visible. "Sync all" keeps the base id.
     */
    fun progressId(jobId: String?): Int =
        if (jobId == null || jobId == "*") PROGRESS_ID else PROGRESS_ID + 1 + (jobId.hashCode() and 0xffff)

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notif_channel_desc) }
        )
        mgr.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, context.getString(R.string.notif_progress_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notif_progress_desc); setShowBadge(false) }
        )
    }

    /**
     * Notifica silenziosa e persistente mentre una sync manuale gira in primo piano. Con un totale
     * noto la barra e' determinata; con un [jobId] offre Pausa/Riprendi e Interrompi. Con [others]
     * altre sync in corso lo dice sotto il testo, cosi' chi vede solo questa sa che non e' l'unica.
     */
    fun progress(
        context: Context, title: String, text: String,
        done: Int = 0, total: Int = 0, jobId: String? = null, paused: Boolean = false, others: Int = 0,
    ): android.app.Notification {
        val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(context, PROGRESS_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(if (total > 0) total else 0, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(PROGRESS_GROUP)
            .setContentIntent(pi)
        if (others > 0) b.setSubText(context.resources.getQuantityString(R.plurals.notif_more_running, others, others))
        if (jobId != null) {
            // Request codes differ per job: PendingIntents with equal codes and intents that differ
            // only in their extras replace each other, and Pause on one sync would pause another.
            fun action(act: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
                context, (jobId.hashCode() shl 2) or code,
                Intent(context, com.xlollx.songport.sync.SyncControlReceiver::class.java).setAction(act).putExtra(com.xlollx.songport.sync.SyncControlReceiver.EXTRA_JOB, jobId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (paused) b.addAction(0, context.getString(R.string.sync_resume), action(com.xlollx.songport.sync.SyncControlReceiver.ACTION_RESUME, 1))
            else b.addAction(0, context.getString(R.string.sync_pause), action(com.xlollx.songport.sync.SyncControlReceiver.ACTION_PAUSE, 2))
            b.addAction(0, context.getString(R.string.sync_stop), action(com.xlollx.songport.sync.SyncControlReceiver.ACTION_STOP, 3))
        }
        return b.build()
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Testo riassuntivo di un report, condiviso tra notifiche e UI. */
    fun summary(context: Context, r: SyncReport): String {
        if (!r.ok) return context.getString(R.string.result_error, r.error)
        val parts = ArrayList<String>()
        if (r.added > 0 || r.removed > 0) parts += context.getString(R.string.result_added_removed, r.added, r.removed)
        if (r.unmatched.isNotEmpty()) parts += context.getString(R.string.result_unmatched, r.unmatched.size)
        if (r.ignored > 0) parts += context.getString(R.string.result_ignored, r.ignored)
        if (parts.isEmpty()) parts += context.getString(R.string.result_ok)
        return parts.joinToString(", ")
    }

    fun syncResult(context: Context, report: SyncReport) {
        if (!canPost(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_LOG)
        }
        val pi = PendingIntent.getActivity(context, report.jobId.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = summary(context, report)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_sync_title, report.jobName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(report.jobId.hashCode(), n)
    }
}
