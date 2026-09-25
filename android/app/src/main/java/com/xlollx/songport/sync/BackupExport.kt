package com.xlollx.songport.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backup fuori dal telefono: i file di backup (una versione datata per playlist, vedi
 * [LocalFilesProvider.writeBackup]) vengono copiati in una cartella scelta dall'utente con il
 * selettore di sistema: scheda SD, Nextcloud, Drive, qualunque app che offra una cartella. Si copia
 * solo cio' che nella cartella ancora manca, per nome, quindi ripetere l'operazione costa poco e la
 * cartella conserva tutte le versioni che il telefono ha avuto. Ripristinare = importare un file da li'.
 */
object BackupExport {

    data class Result(val services: Int, val copied: Int, val failed: List<String>, val folderMissing: Boolean = false)

    /** L'esito dell'ultima copia, per la schermata: quando e' andata bene, o cosa non e' andato. */
    data class State(val lastOk: Long, val lastError: String?)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("backup_state", Context.MODE_PRIVATE)
    fun state(ctx: Context): State = prefs(ctx).let { State(it.getLong("ok", 0), it.getString("error", null)) }
    private fun remember(ctx: Context, error: String?) {
        prefs(ctx).edit().apply { if (error == null) putLong("ok", System.currentTimeMillis()).remove("error") else putString("error", error) }.apply()
    }

    /**
     * La cartella e' ancora raggiungibile: esiste e il permesso persistente vale ancora. Una cartella
     * cancellata, una scheda SD tolta o un'app di cloud disinstallata rispondono qui, non a meta' copia.
     */
    fun folderReachable(ctx: Context, tree: Uri): Boolean = runCatching {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** Aggiorna i backup locali di ogni servizio collegato, poi copia nella cartella i file nuovi. */
    suspend fun run(ctx: Context, onProgress: (String) -> Unit = {}): Result {
        val folder = Store.get(ctx).data.settings.backupFolder.takeIf { it.isNotBlank() }
            ?: return Result(0, 0, emptyList())
        if (!folderReachable(ctx, Uri.parse(folder))) {
            val msg = ctx.getString(com.xlollx.songport.R.string.backup_folder_missing)
            Diagnostics.log(ctx, "backup", "folder not reachable: $folder")
            remember(ctx, msg)
            return Result(0, 0, listOf(msg), folderMissing = true)
        }
        val failed = ArrayList<String>()
        var services = 0
        for (p in Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) }) {
            onProgress(p.label(ctx))
            try {
                val r = Tools.backupAll(ctx, p)
                failed += r.failed.map { "${p.displayName}: $it" }
                services++
            } catch (e: Exception) {
                failed += "${p.displayName}: ${e.message}"
            }
        }
        var copyError: String? = null
        val copied = try { copyNew(ctx, Uri.parse(folder)) } catch (e: Exception) {
            Diagnostics.log(ctx, "backup", "folder copy failed: ${e.message}")
            copyError = e.message ?: e.javaClass.simpleName
            failed += copyError
            0
        }
        Diagnostics.log(ctx, "backup", "export: $services services, $copied new files" + (if (failed.isNotEmpty()) ", ${failed.size} failed" else ""))
        // A copy that reached the folder counts as good, even if one service failed to answer.
        remember(ctx, copyError ?: failed.firstOrNull()?.takeIf { services == 0 })
        return Result(services, copied, failed)
    }

    /** I file locali che nella cartella non ci sono ancora, per nome. @return quanti ne ha scritti. */
    private suspend fun copyNew(ctx: Context, tree: Uri): Int = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val dirId = DocumentsContract.getTreeDocumentId(tree)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val existing = HashSet<String>()
        resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { c -> while (c.moveToNext()) existing += c.getString(0) }
        var copied = 0
        for (f in LocalFilesProvider.dir(ctx).listFiles { it -> it.extension == "csv" }.orEmpty()) {
            if (f.name in existing) continue
            val doc = DocumentsContract.createDocument(resolver, dirUri, "text/csv", f.name) ?: continue
            resolver.openOutputStream(doc)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            copied++
        }
        copied
    }

    /** Nome leggibile della cartella scelta, dall'ultimo pezzo del suo id ("primary:Music/Songport" -> "Music/Songport"). */
    fun folderLabel(uriString: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(uriString)).substringAfter(':').ifBlank { uriString }
    }.getOrDefault(uriString)
}
