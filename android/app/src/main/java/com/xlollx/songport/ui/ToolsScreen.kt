package com.xlollx.songport.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which tool is open in its own page; null = the list. */
private enum class Tool(val icon: androidx.compose.ui.graphics.vector.ImageVector, val titleRes: Int, val descRes: Int, val needsService: Boolean) {
    FILES(Icons.Filled.Folder, R.string.files_title, R.string.files_row_desc, false),
    AI(Icons.Filled.AutoAwesome, R.string.ai_title, R.string.ai_row_desc, false),
    MANAGE(Icons.Filled.Edit, R.string.tools_manage_title, R.string.tools_manage_desc, true),
    EXPORT(Icons.Filled.Download, R.string.tools_export_title, R.string.tools_export_desc, true),
    MAKE(Icons.Filled.PlaylistAdd, R.string.tools_make_title, R.string.tools_make_desc, true),
    TRANSFER(Icons.Filled.SwapHoriz, R.string.tools_transfer_title, R.string.tools_transfer_desc, true),
    BACKUP(Icons.Filled.Backup, R.string.tools_backup_title, R.string.tools_backup_row_desc, true),
    DEDUPE(Icons.Filled.CleaningServices, R.string.tools_dedupe_title, R.string.tools_dedupe_desc, true),
    SCAN(Icons.Filled.ContentCopy, R.string.tools_scan_title, R.string.tools_scan_desc, true),
}

/**
 * Strumenti: un elenco di voci, ognuna con la sua pagina. Con dieci strumenti le schede una sotto
 * l'altra erano diventate una parete di testo; qui si legge il titolo e si apre solo cio' che serve.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(snackbar: SnackbarHostState, onManageFiles: () -> Unit = {}, onTransfer: () -> Unit = {}, onSyncStarted: () -> Unit = {}, onManage: (MusicProvider) -> Unit = {}, onScan: (MusicProvider) -> Unit = {}) {
    val ctx = LocalContext.current
    val connected = Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) }
    var selectedId by remember { mutableStateOf(connected.firstOrNull()?.id) }
    val provider = connected.firstOrNull { it.id == selectedId } ?: connected.firstOrNull()
    var open by remember { mutableStateOf<Tool?>(null) }

    val current = open
    if (current != null) {
        // The tool's own page: its card, full width, with a snackbar of its own (the tab's is behind us).
        val local = remember { SnackbarHostState() }
        BackHandler { open = null }
        Scaffold(
            snackbarHost = { SnackbarHost(local) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(current.titleRes)) },
                    navigationIcon = { IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current.needsService && provider != null && connected.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        connected.forEach { p ->
                            FilterChip(selected = p.id == provider.id, onClick = { selectedId = p.id }, label = { Text(p.label(ctx)) }, leadingIcon = { ProviderDot(p) })
                        }
                    }
                }
                when (current) {
                    Tool.FILES -> FilesCard(local, onManage = onManageFiles)
                    Tool.AI -> AiPlaylistCard(local, onSyncStarted)
                    Tool.EXPORT -> provider?.let { ExportCard(it, local) }
                    Tool.MAKE -> provider?.let { MakePlaylistCard(it, local) }
                    Tool.BACKUP -> provider?.let { BackupCard(it, local) }
                    Tool.DEDUPE -> provider?.let { DedupeCard(it, local) }
                    else -> {}
                }
            }
        }
        return
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolRow(Tool.FILES) { open = Tool.FILES }
        ToolRow(Tool.AI, beta = true) { open = Tool.AI }
        Spacer(Modifier.height(8.dp))
        if (provider == null) {
            Text(stringResource(R.string.empty_tools_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.empty_tools_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(stringResource(R.string.tools_pick_service), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            connected.forEach { p ->
                FilterChip(selected = p.id == provider.id, onClick = { selectedId = p.id }, label = { Text(p.label(ctx)) }, leadingIcon = { ProviderDot(p) })
            }
        }
        ToolRow(Tool.MANAGE) { onManage(provider) }
        ToolRow(Tool.EXPORT) { open = Tool.EXPORT }
        ToolRow(Tool.MAKE) { open = Tool.MAKE }
        ToolRow(Tool.TRANSFER) { onTransfer() }
        ToolRow(Tool.BACKUP) { open = Tool.BACKUP }
        ToolRow(Tool.DEDUPE) { open = Tool.DEDUPE }
        ToolRow(Tool.SCAN) { onScan(provider) }
    }
}

/** One tool in the list: icon, name, a line or two of what it does, a chevron. */
@Composable
private fun ToolRow(tool: Tool, beta: Boolean = false, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(tool.titleRes), style = MaterialTheme.typography.titleSmall)
                    if (beta) { Spacer(Modifier.width(8.dp)); StatusPill(stringResource(R.string.beta), Tone.Neutral) }
                }
                Text(stringResource(tool.descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
            provider.libraryEntries(ctx, asTarget = false) + base
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
                val text = fmt.encode(pl.name, tracks)
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
    val export = rememberPlaylistExporter { uri, _ -> write(uri) }

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
                var formats by remember { mutableStateOf(false) }
                Box {
                    Button(enabled = !busy && pl != null, onClick = { formats = true }) { Text(stringResource(R.string.tools_export_save)) }
                    DropdownMenu(expanded = formats, onDismissRequest = { formats = false }) {
                        ExportMenuItems { fmt -> formats = false; pendingFormat = fmt; export(fmt, pl!!.name) }
                    }
                }
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
            Text(stringResource(R.string.tools_backup_desc, LocalFilesProvider.KEEP_VERSIONS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                (if (r.unchanged > 0) " · " + ctx.getString(R.string.tools_backup_unchanged, r.unchanged) else "") +
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
