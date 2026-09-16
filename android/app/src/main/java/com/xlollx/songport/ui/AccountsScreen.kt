package com.xlollx.songport.ui

import android.net.Uri
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.providers.YouTubeProvider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import com.xlollx.songport.data.Store
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.FlowRow
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xlollx.songport.R
import com.xlollx.songport.auth.AuthEvents
import com.xlollx.songport.auth.AuthFlow
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(snackbar: SnackbarHostState, onSetup: (MusicProvider) -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Ricalcola lo stato connesso/non connesso quando si torna dal browser (login) o dopo logout.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    // Il login loopback (Google) finisce fuori da ogni Activity: ci avvisa da qui.
    val authVersion by AuthEvents.version.collectAsState()
    LaunchedEffect(authVersion) { refresh++ }

    var connectFor by remember { mutableStateOf<MusicProvider?>(null) }
    connectFor?.let { p ->
        ConnectDialog(p, onDismiss = { connectFor = null }) {
            connectFor = null
            AuthFlow.start(ctx, p)
        }
    }

    // Reading the accounts map here subscribes this scope to the store: a newly added account shows
    // up at once instead of on the next tab switch.
    val providers = remember(data.settings.extraAccounts) { Providers.all() }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "intro") { TrustBanner() }
        items(providers.filter { it.requiresAuth }, key = { it.id }) { p ->
            key(refresh) {
                ProviderCard(
                    p = p,
                    quotaUsed = if (p.serviceId == YouTubeProvider.SERVICE) QuotaMeter.used(data, YouTubeProvider.SERVICE) else null,
                    onSetup = onSetup,
                    onConnect = { connectFor = p },
                    onAddAccount = {
                        val used = data.settings.extraAccounts[p.serviceId].orEmpty()
                        val next = ((used.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1) + 1).toString()
                        store.updateSettings { s -> s.copy(extraAccounts = s.extraAccounts + (p.serviceId to used + next)) }
                    },
                    onRemoveAccount = {
                        val inUse = data.jobs.any { it.source.provider == p.id || it.target.provider == p.id }
                        if (inUse) {
                            scope.launch { snackbar.showSnackbar(ctx.getString(R.string.account_remove_blocked)) }
                        } else {
                            p.disconnect(ctx)
                            store.updateSettings { s ->
                                s.copy(extraAccounts = s.extraAccounts + (p.serviceId to s.extraAccounts[p.serviceId].orEmpty().filter { it != p.slot }))
                            }
                        }
                    },
                    onChanged = { refresh++ },
                )
            }
        }
        item(key = "files") { FilesCard(snackbar) }
    }
}

@Composable
private fun TrustBanner() {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.accounts_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ProviderCard(
    p: MusicProvider,
    quotaUsed: Long?,
    onSetup: (MusicProvider) -> Unit,
    onConnect: () -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    // Letture dal token cifrato: una volta per scheda, non a ogni ridisegno (key(refresh) le rinnova).
    val configured = remember(p.id) { p.isConfigured(ctx) }
    val connected = remember(p.id) { p.isConnected(ctx) }
    val account = remember(p.id) { p.accountName(ctx) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(p, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        p.label(ctx) + (if (!p.canWrite) " · " + stringResource(R.string.read_only) else ""),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            connected -> stringResource(R.string.connected_as, account ?: p.displayName)
                            p.slot.isEmpty() -> stringResource(p.noteRes)
                            else -> stringResource(R.string.not_connected)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (connected) StatusPill(stringResource(R.string.connected), Tone.Ok, Icons.Filled.CheckCircle)
                else if (!configured) StatusPill(stringResource(R.string.not_configured), Tone.Neutral)
            }
            if (connected && quotaUsed != null) QuotaCard(used = quotaUsed, sharedCredentials = !p.usesOwnCredentials(ctx))
            if (p.usesOwnCredentials(ctx)) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.setup_using_own), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else if (!configured && !connected) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(p.notConfiguredRes ?: R.string.not_configured_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (connected) {
                    Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.account_encrypted), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    )
                } else Spacer(Modifier.weight(1f))
                val actions = listOfNotNull(
                    if (p.setupGuide != null && p.slot.isEmpty() && configured) MenuAction(stringResource(R.string.setup_cta), { onSetup(p) }) else null,
                    if (connected && p.revokeUrl != null) MenuAction(stringResource(R.string.account_revoke, p.displayName), {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.revokeUrl)))
                    }) else null,
                    if (connected && p.slot.isEmpty() && p.supportsMultipleAccounts) MenuAction(stringResource(R.string.account_add), onAddAccount) else null,
                    if (p.slot.isNotEmpty()) MenuAction(stringResource(R.string.account_remove), onRemoveAccount, destructive = true) else null,
                )
                OverflowMenu(actions)
                when {
                    connected -> OutlinedButton(onClick = { p.disconnect(ctx); onChanged() }) { Text(stringResource(R.string.disconnect)) }
                    // Servizio che passa da un'app compagna non ancora installata: il pulsante la scarica.
                    !configured && p.installUrl != null -> Button(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.installUrl)))
                    }) { Text(stringResource(R.string.ytm_install)) }
                    // Nessuna chiave per questo servizio: il pulsante principale porta alla procedura guidata.
                    !configured && p.setupGuide != null -> Button(onClick = { onSetup(p) }) { Text(stringResource(R.string.setup_now)) }
                    else -> Button(enabled = configured, onClick = onConnect) { Text(stringResource(R.string.connect)) }
                }
            }
        }
    }
}

@Composable
private fun FilesCard(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { lists = withContext(Dispatchers.IO) { LocalFilesProvider.playlists(ctx) } }

    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }
    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        pendingImport = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                LocalFilesProvider.importFile(ctx, displayName(ctx, uri), text)
            }
        }
        result.onSuccess { n -> snackbar.showSnackbar(ctx.getString(R.string.csv_imported, n)); version++ }
            .onFailure { snackbar.showSnackbar(it.message ?: ctx.getString(R.string.error_generic)) }
    }

    // Un launcher per formato: CreateDocument fissa il tipo MIME alla costruzione.
    var exporting by remember { mutableStateOf<String?>(null) }
    fun writeExport(uri: Uri?, format: LocalFilesProvider.Export) {
        val id = exporting
        exporting = null
        if (uri == null || id == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()
                    ?.use { it.write(LocalFilesProvider.exportText(ctx, id, format)) }
            }
            snackbar.showSnackbar(ctx.getString(R.string.csv_exported))
        }
    }
    var pasteOpen by remember { mutableStateOf(false) }
    if (pasteOpen) PasteListDialog(
        onDismiss = { pasteOpen = false },
        onImport = { name, text ->
            pasteOpen = false
            scope.launch {
                val n = withContext(Dispatchers.IO) { LocalFilesProvider.importFile(ctx, "$name.txt", text) }
                snackbar.showSnackbar(ctx.getString(R.string.csv_imported, n))
                version++
            }
        },
    )
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.CSV.mime)
    ) { uri -> writeExport(uri, LocalFilesProvider.Export.CSV) }
    val m3uLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.M3U.mime)
    ) { uri -> writeExport(uri, LocalFilesProvider.Export.M3U) }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderBadge(LocalFilesProvider, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(LocalFilesProvider.displayName, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(LocalFilesProvider.noteRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.csv_playlists_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            if (lists.isEmpty()) Text(stringResource(R.string.csv_empty), style = MaterialTheme.typography.bodyMedium)
            lists.forEach { pl ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(pl.name)
                        Text(stringResource(R.string.tracks_count, pl.trackCount), style = MaterialTheme.typography.bodySmall)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Share, stringResource(R.string.csv_export)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.csv_export_csv)) },
                                onClick = { menu = false; exporting = pl.id; csvLauncher.launch(pl.name + ".csv") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.csv_export_m3u)) },
                                onClick = { menu = false; exporting = pl.id; m3uLauncher.launch(pl.name + ".m3u8") },
                            )
                        }
                    }
                    IconButton(onClick = { LocalFilesProvider.delete(ctx, pl.id); version++ }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { pasteOpen = true }) { Text(stringResource(R.string.paste_list)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "text/*", "text/csv", "text/comma-separated-values", "text/plain",
                            "application/json", "application/xml", "text/xml",
                            "audio/x-mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream",
                        )
                    )
                }) {
                    Text(stringResource(R.string.csv_import))
                }
            }
        }
    }
}

private fun displayName(ctx: android.content.Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: "playlist"
    }
    return uri.lastPathSegment ?: "playlist"
}

/** Un blocco di testo "Artista - Titolo" per riga diventa una playlist da file. */
@Composable
private fun PasteListDialog(onDismiss: () -> Unit, onImport: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_list)) },
        text = {
            Column {
                Text(stringResource(R.string.paste_list_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.paste_list_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 5, maxLines = 10, label = { Text(stringResource(R.string.paste_list_text)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onImport(name.ifBlank { "playlist" }, text) }) { Text(stringResource(R.string.import_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
