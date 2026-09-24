package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.PlaylistFiles
import java.io.File

/**
 * "Servizio" locale: playlist come file nella cartella privata dell'app.
 * E' il ponte per i servizi senza API aperta (Amazon Music, Qobuz, SoundCloud…): si importa
 * qui quello che loro sanno esportare (CSV, M3U, XML di Apple Music/iTunes, JSON, elenchi di
 * testo) e lo si sincronizza verso Spotify, Apple Music, YouTube Music, TIDAL o Deezer — e
 * viceversa, esportando qualsiasi playlist in CSV o M3U.
 *
 * L'id resta "csv" per compatibilita' con le sync gia' salvate.
 */
object LocalFilesProvider : MusicProvider {
    override val serviceId = "csv"
    override val supportsMultipleAccounts = false
    override val displayName = "File"
    override val brandColor = 0xFF607D8B
    override val noteRes = R.string.provider_note_files
    override val requiresAuth = false

    fun dir(ctx: Context): File = File(ctx.filesDir, "csv").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.csv")

    override fun isConfigured(ctx: Context) = true
    override fun isConnected(ctx: Context) = true
    override fun accountName(ctx: Context): String? = null
    override fun startAuth(ctx: Context) = Unit
    override suspend fun completeAuth(ctx: Context, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Context) = Unit

    override suspend fun playlists(ctx: Context): List<Playlist> =
        dir(ctx).listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name.lowercase() }.map { f ->
            Playlist(id = f.nameWithoutExtension, name = f.nameWithoutExtension, trackCount = CsvCodec.parse(f.readText()).size)
        }

    override suspend fun tracks(ctx: Context, playlistId: String): List<Track> {
        val f = file(ctx, playlistId)
        return if (f.exists()) CsvCodec.parse(f.readText()) else emptyList()
    }

    /** Un file puo' "contenere" qualunque brano: il candidato e' il brano stesso con id stabile. */
    override suspend fun search(ctx: Context, track: Track): List<Track> = listOf(CsvCodec.withStableId(track))

    override suspend fun createPlaylist(ctx: Context, name: String, description: String): Playlist {
        val base = CsvCodec.safeName(name)
        var candidate = base
        var n = 2
        while (file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        file(ctx, candidate).writeText(CsvCodec.encode(emptyList()))
        return Playlist(candidate, candidate, 0)
    }

    override suspend fun addTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        val f = file(ctx, playlistId)
        val current = if (f.exists()) CsvCodec.parse(f.readText()) else emptyList()
        val ids = current.map { it.id }.toHashSet()
        val merged = current + tracks.map { CsvCodec.withStableId(it) }.filter { ids.add(it.id) }
        f.writeText(CsvCodec.encode(merged))
    }

    override suspend fun removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) {
        val f = file(ctx, playlistId)
        if (!f.exists()) return
        val remove = tracks.map { it.id }.toHashSet()
        f.writeText(CsvCodec.encode(CsvCodec.parse(f.readText()).filter { it.id !in remove }))
    }

    // --- import/export per la UI (Storage Access Framework) ---

    /** Importa un file in qualsiasi formato riconosciuto. @return numero di brani importati. */
    fun importFile(ctx: Context, fileName: String, text: String): Int {
        val tracks = PlaylistFiles.parse(fileName, text)
        val base = CsvCodec.safeName(fileName.substringBeforeLast('.').ifBlank { "playlist" })
        var candidate = base
        var n = 2
        while (file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        file(ctx, candidate).writeText(CsvCodec.encode(tracks))
        return tracks.size
    }

    /** Salva una lista di brani come nuovo file (nome reso unico). @return id della playlist creata. */
    fun importTracks(ctx: Context, name: String, tracks: List<Track>): String {
        val base = CsvCodec.safeName(name.ifBlank { "playlist" })
        var candidate = base
        var n = 2
        while (file(ctx, candidate).exists()) { candidate = "$base ($n)"; n++ }
        file(ctx, candidate).writeText(CsvCodec.encode(tracks))
        return candidate
    }

    enum class Export(val mime: String, val extension: String) {
        CSV("text/csv", "csv"),
        M3U("audio/x-mpegurl", "m3u8"),
        JSPF("application/json", "jspf"),
        XSPF("application/xspf+xml", "xspf"),
        TXT("text/plain", "txt"),
        ;

        /** Il testo del file in questo formato; [name] serve ai formati che hanno un titolo. */
        fun encode(name: String, tracks: List<Track>): String = when (this) {
            CSV -> CsvCodec.encode(tracks)
            M3U -> PlaylistFiles.toM3u(tracks)
            JSPF -> PlaylistFiles.toJspf(name, tracks)
            XSPF -> PlaylistFiles.toXspf(name, tracks)
            TXT -> PlaylistFiles.toText(tracks)
        }
    }

    fun exportText(ctx: Context, playlistId: String, format: Export): String {
        val tracks = file(ctx, playlistId).takeIf { it.exists() }?.readText()?.let { CsvCodec.parse(it) } ?: emptyList()
        return format.encode(playlistId, tracks)
    }

    fun delete(ctx: Context, playlistId: String) { file(ctx, playlistId).delete() }

    // --- backups: one version per run, dated, with retention ---

    /** How many versions of one playlist's backup are kept; older ones are removed at the next backup. */
    const val KEEP_VERSIONS = 5

    /**
     * A file playlist as the Files screen shows it. Backups are "Service - Playlist (yyyy-MM-dd HH.mm)";
     * [service] and [base] come from the name, [writtenAt] from the stamp, or from the file when the
     * name has none (imports, and backups made before versions existed).
     */
    data class Entry(
        val id: String, val name: String, val trackCount: Int,
        val service: String?, val base: String, val stamped: Boolean, val writtenAt: Long,
    ) {
        /** The playlist's own name, without the service prefix and the stamp. */
        val title: String get() = if (service == null) base else base.substringAfter(" - ").trim()
    }

    /** A fresh formatter per use: SimpleDateFormat is not thread-safe, and backups and the Files screen may overlap. */
    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT)
    private val STAMP_TAIL = Regex(""" \((\d{4}-\d{2}-\d{2} \d{2}\.\d{2})\)$""")
    private val COPY_TAIL = Regex(""" \(\d+\)$""")

    fun entries(ctx: Context): List<Entry> =
        dir(ctx).listFiles { f -> f.extension == "csv" }.orEmpty().map { f ->
            val name = f.nameWithoutExtension
            val tail = STAMP_TAIL.find(name)
            val stampedAt = tail?.let { runCatching { stamp().parse(it.groupValues[1])?.time }.getOrNull() }
            val base = COPY_TAIL.replace(STAMP_TAIL.replace(name, ""), "")
            Entry(
                id = name, name = name, trackCount = CsvCodec.parse(f.readText()).size,
                service = base.substringBefore(" - ", "").trim().takeIf { it.isNotEmpty() },
                base = base, stamped = stampedAt != null, writtenAt = stampedAt ?: f.lastModified(),
            )
        }

    enum class BackupOutcome { WRITTEN, UNCHANGED }

    /**
     * Writes one dated version of a service playlist's backup, unless the newest version already has
     * exactly these tracks, then trims that playlist's versions to [KEEP_VERSIONS]. Files a sync reads
     * or writes ([protectedIds]) are never removed, whatever their age.
     */
    fun writeBackup(ctx: Context, service: String, playlistName: String, tracks: List<Track>, protectedIds: Set<String>, now: Long = System.currentTimeMillis()): BackupOutcome {
        val base = CsvCodec.safeName("$service - $playlistName")
        val versions = entries(ctx).filter { it.base == base && it.service != null }.sortedByDescending { it.writtenAt }
        val text = CsvCodec.encode(tracks)
        if (versions.firstOrNull()?.let { file(ctx, it.id).readText() } == text) return BackupOutcome.UNCHANGED
        val when_ = stamp().format(java.util.Date(now))
        var id = "$base ($when_)"
        var n = 2
        while (file(ctx, id).exists()) { id = "$base ($when_) ($n)"; n++ }
        file(ctx, id).writeText(text)
        versions.drop(KEEP_VERSIONS - 1).filter { it.id !in protectedIds }.forEach { file(ctx, it.id).delete() }
        return BackupOutcome.WRITTEN
    }

    /** Ids of the file playlists some sync reads or writes: retention leaves them alone. */
    fun protectedIds(ctx: Context): Set<String> =
        com.xlollx.songport.data.Store.get(ctx).data.jobs.flatMap { j ->
            listOfNotNull(j.source.takeIf { it.provider == serviceId }?.playlistId, j.target.takeIf { it.provider == serviceId }?.playlistId)
        }.toSet()
}
