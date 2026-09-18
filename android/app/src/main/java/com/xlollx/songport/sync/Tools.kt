package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider

/**
 * Utilita' che i concorrenti tengono dietro un abbonamento o non hanno affatto:
 * backup completo di un servizio in file locali e pulizia dei brani doppi.
 */
object Tools {

    data class BackupResult(val playlists: Int, val tracks: Int, val failed: List<String>, val unchanged: Int = 0)

    /**
     * Salva tutte le playlist (e i preferiti, se il servizio li espone) come file CSV locali,
     * esportabili dalla scheda File. Non tocca nulla sul servizio.
     */
    suspend fun backupAll(ctx: Context, provider: MusicProvider, onProgress: (Progress) -> Unit = {}): BackupResult {
        val lists = provider.playlists(ctx).toMutableList()
        if (provider.supportsLikedSongs) {
            lists.add(0, com.xlollx.songport.model.Playlist(MusicProvider.LIKED_ID, ctx.getString(com.xlollx.songport.R.string.liked_songs)))
        }
        var tracks = 0
        var done = 0
        var unchanged = 0
        val failed = ArrayList<String>()
        val inUse = LocalFilesProvider.protectedIds(ctx)
        val now = System.currentTimeMillis()
        for (pl in lists) {
            onProgress(Progress(Progress.Step.BACKUP, done, lists.size))
            try {
                val items = provider.tracks(ctx, pl.id)
                // One dated version per run; a playlist that has not changed since the last backup
                // is not written again, so the Files screen holds versions, not copies.
                if (LocalFilesProvider.writeBackup(ctx, provider.displayName, pl.name, items, inUse, now) == LocalFilesProvider.BackupOutcome.UNCHANGED) unchanged++
                tracks += items.size
            } catch (e: Exception) {
                failed += "${pl.name} (${e.message})"
            }
            done++
        }
        return BackupResult(lists.size - failed.size, tracks, failed, unchanged)
    }

    /** Brani doppi in una playlist, raggruppati (il primo di ogni gruppo e' quello da tenere). */
    suspend fun findDuplicates(ctx: Context, provider: MusicProvider, playlistId: String): List<List<Track>> =
        Duplicates.groups(provider.tracks(ctx, playlistId))

    /**
     * Toglie i doppi lasciando la prima occorrenza. Su servizi che rimuovono per id (Spotify,
     * Deezer) togliere un id elimina TUTTE le sue copie: in quel caso si toglie e si riaggiunge
     * una volta. Dove la rimozione e' per elemento di playlist (YouTube, TIDAL) basta l'itemId.
     */
    suspend fun removeDuplicates(ctx: Context, provider: MusicProvider, playlistId: String, onProgress: (Progress) -> Unit = {}): Int {
        if (!provider.canRemoveTracks) throw ProviderException(ctx.getString(com.xlollx.songport.R.string.error_no_removals, provider.displayName))
        val groups = Duplicates.groups(provider.tracks(ctx, playlistId))
        var removed = 0
        groups.forEachIndexed { i, group ->
            onProgress(Progress(Progress.Step.DEDUPE, i, groups.size))
            val keep = group.first()
            val extras = group.drop(1)
            val byItem = extras.filter { it.itemId != null }
            val byId = extras.filter { it.itemId == null }
            if (byItem.isNotEmpty()) provider.removeTracks(ctx, playlistId, byItem)
            if (byId.isNotEmpty()) {
                // Rimozione per id: le copie con lo stesso id del "da tenere" spariscono tutte, poi se ne rimette una.
                provider.removeTracks(ctx, playlistId, byId)
                if (byId.any { it.id == keep.id }) provider.addTracks(ctx, playlistId, listOf(keep))
            }
            removed += extras.size
        }
        return removed
    }
}
