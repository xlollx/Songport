package com.xlollx.songport.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xlollx.songport.R
import com.xlollx.songport.providers.LocalFilesProvider

/** Nome del formato per l'utente: le sigle restano sigle, il testo semplice ha una parola tradotta. */
@Composable
fun formatLabel(format: LocalFilesProvider.Export): String = when (format) {
    LocalFilesProvider.Export.TXT -> stringResource(R.string.format_text)
    else -> format.name
}

/**
 * Un "salva con nome" per ogni formato di esportazione. CreateDocument fissa il tipo MIME alla
 * creazione, quindi serve un launcher per formato: qui stanno tutti, e chi chiama passa solo
 * formato e nome base del file.
 */
@Composable
fun rememberPlaylistExporter(onPicked: (Uri?, LocalFilesProvider.Export) -> Unit): (LocalFilesProvider.Export, String) -> Unit {
    val launchers = LocalFilesProvider.Export.entries.associateWith { fmt ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(fmt.mime)) { onPicked(it, fmt) }
    }
    return { fmt, base -> launchers.getValue(fmt).launch(base.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "playlist" } + "." + fmt.extension) }
}

/** Le voci "Esporta come …" di un menu, una per formato. */
@Composable
fun ExportMenuItems(onExport: (LocalFilesProvider.Export) -> Unit) {
    LocalFilesProvider.Export.entries.forEach { fmt ->
        DropdownMenuItem(text = { Text(stringResource(R.string.export_format, formatLabel(fmt))) }, onClick = { onExport(fmt) })
    }
}
