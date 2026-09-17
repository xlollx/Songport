package com.xlollx.songport.data

import android.content.Context
import android.os.Build
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.providers.Providers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro tecnico locale, senza server: le ultime righe di errori e passaggi importanti,
 * piu' un rapporto copiabile per chiedere aiuto. Niente token, niente password, niente
 * indirizzi dei server personali.
 */
object Diagnostics {
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "diagnostics.log")

    fun log(ctx: Context, tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] ${message.replace('\n', ' ').take(400)}"
        synchronized(lock) {
            try {
                val f = file(ctx)
                val lines = if (f.exists()) f.readLines() else emptyList()
                val next = (lines + line).takeLast(MAX_LINES)
                f.writeText(next.joinToString("\n"))
            } catch (_: Exception) { }
        }
    }

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }

    /** Rapporto leggibile: versione, dispositivo, servizi collegati, ultime sync, ultime righe di log. */
    fun report(ctx: Context): String {
        val data = Store.get(ctx).data
        val sb = StringBuilder()
        sb.appendLine("Songport ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Locale: ${Locale.getDefault()}")
        sb.appendLine()
        sb.appendLine("Connectors:")
        val connectors = Providers.connectors()
        if (connectors.isEmpty()) sb.appendLine("  (none)")
        connectors.filter { it.requiresAuth }.forEach { p ->
            sb.appendLine("  ${p.id}: ${if (p.isConnected(ctx)) "connected" else "no"}" +
                (if (p.usesOwnCredentials(ctx)) ", own credentials" else "") +
                (if (!p.isConfigured(ctx)) ", not configured" else ""))
        }
        // Credenziali rimaste da connettori tolti: utili per capire una sincronizzazione che si
        // riferisce a un servizio non piu' elencato.
        val leftovers = Providers.all().filter { p -> p.requiresAuth && connectors.none { it.id == p.id } && runCatching { p.isConnected(ctx) }.getOrDefault(false) }
        if (leftovers.isNotEmpty()) sb.appendLine("  leftover sessions: ${leftovers.joinToString { it.id }}")
        // Cosa sta facendo il plugin, se c'e': conteggi e ritmi delle sue chiamate, mai contenuti.
        if (com.xlollx.songport.providers.BridgePlugin.installed(ctx)) {
            val stats = runCatching { ctx.contentResolver.call(com.xlollx.songport.providers.BridgePlugin.authority(ctx), "stats", null, null)?.getString("stats") }.getOrNull()
            sb.appendLine("  plugin: ${stats ?: "no stats"}")
        }
        sb.appendLine()
        sb.appendLine("Syncs: ${data.jobs.size} · match cache: ${data.matchCache.size} · miss cache: ${data.missCache.size}")
        data.jobs.forEach { j ->
            sb.appendLine("  ${j.name}: ${j.source.provider} -> ${j.target.provider}, ${j.schedule}, mirror=${j.mirrorRemovals}, on=${j.enabled}")
        }
        sb.appendLine()
        sb.appendLine("Last runs:")
        data.reports.take(15).forEach { r ->
            sb.appendLine("  ${fmt.format(Date(r.startedEpoch))} ${r.jobName}: +${r.added} -${r.removed} nf=${r.unmatched.size}" +
                (r.error?.let { " ERROR: $it" } ?: ""))
        }
        sb.appendLine()
        sb.appendLine("Log:")
        val f = file(ctx)
        if (f.exists()) f.readLines().takeLast(120).forEach { sb.appendLine("  $it") }
        return sb.toString()
    }
}
