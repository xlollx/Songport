package com.xlollx.songport.sync

import com.xlollx.songport.model.Progress
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Stato in memoria delle sync in corso (per la UI), con pausa e interruzione richieste dall'utente. */
object SyncState {
    private val _running = MutableStateFlow<Map<String, Progress?>>(emptyMap())
    val running: StateFlow<Map<String, Progress?>> get() = _running
    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    val paused: StateFlow<Set<String>> get() = _paused
    private val _stopping = MutableStateFlow<Set<String>>(emptySet())
    /** Stop asked for, not yet reached: the engine stops at its next step, the card says so meanwhile. */
    val stopping: StateFlow<Set<String>> get() = _stopping

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
        _stopping.value = _stopping.value - jobId
    }

    private const val MAX_ITEMS = 1000

    fun pause(jobId: String) { _paused.value = _paused.value + jobId }
    fun resume(jobId: String) { _paused.value = _paused.value - jobId }
    // A paused sync stays shown as paused until it actually stops: checkpoint() lets a stop through a pause.
    fun stop(jobId: String) { _stopping.value = _stopping.value + jobId }
    fun isPaused(jobId: String) = jobId in _paused.value

    /**
     * Chiamato dal motore a ogni passo: attende finche' la sync e' in pausa e interrompe se l'utente
     * l'ha fermata. Bloccante di proposito: il motore lavora su un thread di WorkManager.
     */
    fun checkpoint(env: Env, jobId: String) {
        while (jobId in _paused.value && jobId !in _stopping.value) Thread.sleep(250)
        stopCheck(env, jobId)
    }

    /** Solo l'interruzione, senza attendere la pausa: per i passi che devono scorrere comunque. */
    fun stopCheck(env: Env, jobId: String) {
        if (jobId in _stopping.value) throw SyncStoppedException(env.text("sync_stopped"))
    }
}

class SyncStoppedException(message: String) : Exception(message)
