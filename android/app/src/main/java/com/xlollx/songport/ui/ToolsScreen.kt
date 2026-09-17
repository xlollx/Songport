package com.xlollx.songport.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Tools
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.PlaylistFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Strumenti che altrove stanno dietro un abbonamento: backup completo e pulizia dei duplicati. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ToolsScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val connected = Providers.all().filter { it.requiresAuth && it.isConnected(ctx) }
    var selectedId by remember { mutableStateOf(connected.firstOrNull()?.id) }
    val provider = connected.firstOrNull { it.id == selectedId } ?: connected.firstOrNull()

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Playlist files (imported, or produced by a sync into "File") live here, not under Accounts.
        FilesCard(snackbar)
        if (provider == null) {
            EmptyState(Icons.Filled.Build, stringResource(R.string.empty_tools_title), stringResource(R.string.empty_tools_body))
            return@Column
        }
        Text(stringResource(R.string.tools_pick_service), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            connected.forEach { p ->
                FilterChip(selected = p.id == provider.id, onClick = { selectedId = p.id }, label = { Text(p.label(ctx)) }, leadingIcon = { ProviderDot(p) })
            }
        }
        ExportCard(provider, snackbar)
        BackupCard(provider, snackbar)
        DedupeCard(provider, snackbar)
    }
}

/** Una playlist qualsiasi del servizio diventa un file CSV o M3U scelto dal selettore di sistema. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember(provider.id) { mutableStateOf<List<Playlist>?>(null) }
    var selected by remember(provider.id) { mutableStateOf<Playlist?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf<LocalFilesProvider.Export?>(null) }

    LaunchedEffect(provider.id) {
        lists = try {
            val base = provider.playlists(ctx)
            if (provider.supportsLikedSongs) listOf(Playlist(MusicProvider.LIKED_ID, ctx.getString(R.string.liked_songs))) + base else base
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            emptyList()
        }
    }

    fun write(uri: Uri?) {
        val pl = selected
        val fmt = pendingFormat
        pendingFormat = null
        if (uri == null || pl == null || fmt == null) return
        busy = true
        scope.launch {
            try {
                val tracks = provider.tracks(ctx, pl.id)
                val text = when (fmt) {
                    LocalFilesProvider.Export.CSV -> CsvCodec.encode(tracks)
                    LocalFilesProvider.Export.M3U -> PlaylistFiles.toM3u(tracks)
                }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                }
                snackbar.showSnackbar(ctx.getString(R.string.tools_export_done, tracks.size))
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            }
            busy = false
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.CSV.mime)) { write(it) }
    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.M3U.mime)) { write(it) }
    fun fileName(pl: Playlist, ext: String) = pl.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "playlist" } + "." + ext

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tools_export_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_export_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val items = lists
            if (items == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading)) }
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.editor_playlist)) },
                        placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { selected = p; expanded = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                val pl = selected
                OutlinedButton(enabled = !busy && pl != null, onClick = {
                    pendingFormat = LocalFilesProvider.Export.CSV
                    csvLauncher.launch(fileName(pl!!, "csv"))
                }) { Text(stringResource(R.string.tools_export_csv)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && pl != null, onClick = {
                    pendingFormat = LocalFilesProvider.Export.M3U
                    m3uLauncher.launch(fileName(pl!!, "m3u8"))
                }) { Text(stringResource(R.string.tools_export_m3u)) }
            }
        }
    }
}

@Composable
private fun BackupCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tools_backup_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_backup_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    progress?.let { Text(stringResource(R.string.tools_progress_backup, it.done, it.total), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp)) }
                }
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val r = Tools.backupAll(ctx, provider) { progress = it }
                            snackbar.showSnackbar(ctx.getString(R.string.tools_backup_done, r.playlists, r.tracks) +
                                (if (r.failed.isNotEmpty()) " · " + r.failed.joinToString(", ") else ""))
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.tools_backup_run)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupeCard(provider: MusicProvider, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember(provider.id) { mutableStateOf<List<Playlist>?>(null) }
    var selected by remember(provider.id) { mutableStateOf<Playlist?>(null) }
    var groups by remember(provider.id) { mutableStateOf<List<List<Track>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(provider.id) {
        lists = try { provider.playlists(ctx).filter { it.ownedByMe } } catch (e: Exception) { emptyList() }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.tools_dedupe_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tools_dedupe_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!provider.canRemoveTracks) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.error_no_removals, provider.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Spacer(Modifier.height(10.dp))
            val items = lists
            if (items == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading)) }
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.editor_playlist)) },
                        placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { selected = p; groups = null; expanded = false })
                        }
                    }
                }
            }
            val g = groups
            if (g != null) {
                Spacer(Modifier.height(8.dp))
                if (g.isEmpty()) Text(stringResource(R.string.tools_dedupe_none))
                else {
                    val extras = g.sumOf { it.size - 1 }
                    Text(stringResource(R.string.tools_dedupe_found, g.size, extras), style = MaterialTheme.typography.bodyMedium)
                    g.take(10).forEach { grp -> Text("• ${grp.first()} ×${grp.size}", style = MaterialTheme.typography.bodySmall) }
                    if (g.size > 10) Text(stringResource(R.string.preview_more, g.size - 10), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    progress?.let { Text(stringResource(R.string.tools_progress_dedupe, it.done, it.total), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp)) }
                }
                val pl = selected
                OutlinedButton(enabled = !busy && pl != null, onClick = {
                    busy = true
                    scope.launch {
                        groups = try { Tools.findDuplicates(ctx, provider, pl!!.id) } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)); null
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.tools_dedupe_scan)) }
                val extras = g?.sumOf { it.size - 1 } ?: 0
                if (extras > 0) {
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy && pl != null, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val n = Tools.removeDuplicates(ctx, provider, pl!!.id) { progress = it }
                                snackbar.showSnackbar(ctx.getString(R.string.tools_dedupe_done, n))
                                groups = null
                            } catch (e: Exception) {
                                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
                            }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.tools_dedupe_remove, extras)) }
                }
            }
        }
    }
}
