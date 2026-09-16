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
        sb.appendLine("Lingua: ${Locale.getDefault()}")
        sb.appendLine()
        sb.appendLine("Servizi:")
        Providers.all().filter { it.requiresAuth }.forEach { p ->
            sb.appendLine("  ${p.id}: ${if (p.isConnected(ctx)) "collegato" else "no"}" +
                (if (p.usesOwnCredentials(ctx)) ", credenziali proprie" else "") +
                (if (!p.isConfigured(ctx)) ", non configurato" else ""))
        }
        sb.appendLine()
        sb.appendLine("Sync configurate: ${data.jobs.size} · cache abbinamenti: ${data.matchCache.size}")
        data.jobs.forEach { j ->
            sb.appendLine("  ${j.name}: ${j.source.provider} -> ${j.target.provider}, ${j.schedule}, mirror=${j.mirrorRemovals}, on=${j.enabled}")
        }
        sb.appendLine()
        sb.appendLine("Ultime esecuzioni:")
        data.reports.take(15).forEach { r ->
            sb.appendLine("  ${fmt.format(Date(r.startedEpoch))} ${r.jobName}: +${r.added} -${r.removed} nf=${r.unmatched.size}" +
                (r.error?.let { " ERRORE: $it" } ?: ""))
        }
        sb.appendLine()
        sb.appendLine("Log:")
        val f = file(ctx)
        if (f.exists()) f.readLines().takeLast(120).forEach { sb.appendLine("  $it") }
        return sb.toString()
    }
}
