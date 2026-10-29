package com.xlollx.songport.flows

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Several syncs started at once share one ongoing notification: "3 syncs running", a line per
 * sync with its step, Pause all and Stop all. No second entry next to it (the bare "Syncing…" bar
 * WorkManager used to leave), and nothing ongoing once they are done.
 */
@RunWith(AndroidJUnit4::class)
class SyncNotificationFlowTest : FlowTest() {

    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    /** The app's ongoing entries in the shade: progress, foreground service. */
    private fun ongoing(): List<StatusBarNotification> =
        nm.activeNotifications.filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 || it.notification.channelId == Notifications.PROGRESS_CHANNEL_ID }

    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun StatusBarNotification.actionTitles() = notification.actions.orEmpty().map { it.title.toString() }
    private fun StatusBarNotification.action(title: String) = notification.actions.orEmpty().first { it.title.toString() == title }

    @Test
    fun threeSyncsAtOnceShowOneOngoingNotification() {
        val jobs = (1..3).map { i ->
            SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", null, "Road Trip $i"))
        }
        store.update { it.copy(jobs = jobs) }
        store.flush()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")

        // Each sync waits at its first read until the test lets it go: all three run together.
        val hold = CompletableDeferred<Unit>()
        FakeWorld.hold = hold
        try {
            jobs.forEach { Scheduler.runNow(app, it.id) }
            waitFor("three syncs running", 30_000) { SyncState.running.value.keys == jobs.map { it.id }.toSet() }

            val title = plural(R.plurals.syncs_running, 3, 3)
            waitFor("one notification for the three syncs", 20_000) { ongoing().singleOrNull()?.title() == title }
            // It stays one: no late placeholder from another worker turns up beside it.
            val end = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < end) {
                val now = ongoing()
                assertEquals("ongoing notifications: ${now.map { "${it.id} ${it.title()}" }}", 1, now.size)
                Thread.sleep(200)
            }
            val n = ongoing().single()
            assertEquals(Notifications.PROGRESS_ID, n.id)
            assertEquals(Notifications.PROGRESS_CHANNEL_ID, n.notification.channelId)
            val lines = n.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
            assertEquals(lines.toString(), 3, lines.size)
            jobs.forEach { j -> assertTrue("${j.name} in $lines", lines.any { it.startsWith(j.name) && it.contains(s(R.string.progress_fetch_source)) }) }
            assertEquals(listOf(s(R.string.sync_pause_all), s(R.string.sync_stop_all)), n.actionTitles())

            // Pause all, from the notification: every sync paused, and the button turns into Resume all.
            n.action(s(R.string.sync_pause_all)).actionIntent.send()
            waitFor("all three paused") { SyncState.paused.value == jobs.map { it.id }.toSet() }
            waitFor("Resume all offered") { ongoing().singleOrNull()?.actionTitles()?.contains(s(R.string.sync_resume_all)) == true }
            ongoing().single().action(s(R.string.sync_resume_all)).actionIntent.send()
            waitFor("all three resumed") { SyncState.paused.value.isEmpty() }
            assertEquals(1, ongoing().size)

            hold.complete(Unit)
            waitFor("the three syncs done", 60_000) { SyncState.running.value.isEmpty() && store.data.reports.count { !it.partial } >= 3 }
            waitFor("no ongoing notification left", 20_000) { ongoing().isEmpty() }
        } finally {
            hold.complete(Unit)
            FakeWorld.hold = null
            // Only what still runs: a stop asked for a sync that is not running would wait for its next run.
            SyncState.running.value.keys.forEach { SyncState.stop(it) }
            runCatching { waitFor("syncs finished", 60_000) { SyncState.running.value.isEmpty() } }
        }
        jobs.forEach { j -> assertTrue("${j.name} ran", store.data.reports.any { it.jobId == j.id && it.ok }) }
    }
}
