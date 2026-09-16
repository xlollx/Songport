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
    }

    fun exportText(ctx: Context, playlistId: String, format: Export): String {
        val tracks = file(ctx, playlistId).takeIf { it.exists() }?.readText()?.let { CsvCodec.parse(it) } ?: emptyList()
        return when (format) {
            Export.CSV -> CsvCodec.encode(tracks)
            Export.M3U -> PlaylistFiles.toM3u(tracks)
        }
    }

    fun delete(ctx: Context, playlistId: String) { file(ctx, playlistId).delete() }
}
