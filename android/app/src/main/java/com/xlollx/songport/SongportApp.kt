package com.xlollx.songport

import android.app.Application
import com.xlollx.songport.sync.Scheduler

class SongportApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The connections log (hosts only) starts before anything talks to the network.
        com.xlollx.songport.net.HostLog.init(this)
        com.xlollx.songport.providers.BuiltIn.onAppStart(com.xlollx.songport.net.HostLog.interceptor)
        Notifications.ensureChannel(this)
        // Riallinea i lavori periodici alla configurazione salvata (idempotente).
        Scheduler.applyAll(this)
    }
}
