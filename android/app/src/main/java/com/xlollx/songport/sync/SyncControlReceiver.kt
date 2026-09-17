package com.xlollx.songport.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Pausa, ripresa e interruzione di una sync dai pulsanti della notifica di avanzamento. */
class SyncControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB) ?: return
        when (intent.action) {
            ACTION_PAUSE -> SyncState.pause(jobId)
            ACTION_RESUME -> SyncState.resume(jobId)
            ACTION_STOP -> SyncState.stop(jobId)
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.xlollx.songport.sync.PAUSE"
        const val ACTION_RESUME = "com.xlollx.songport.sync.RESUME"
        const val ACTION_STOP = "com.xlollx.songport.sync.STOP"
        const val EXTRA_JOB = "job"
    }
}
