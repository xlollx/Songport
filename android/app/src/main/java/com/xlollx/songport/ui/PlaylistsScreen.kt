package com.xlollx.songport.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Le playlist dell'utente su un servizio, con le azioni che il servizio permette: rinominare,
 * eliminare, esportare. Solo le proprie (quelle seguite di altri non si toccano). Eliminare una
 * playlist usata da una sync fa fallire la sync finche' non la si modifica: lo si dice prima.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(provider: MusicProvider, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { Store.get(ctx) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var expanding by remember { mutableStateOf<Playlist?>(null) }
    val aiReady = remember { com.xlollx.songport.ai.AiClient.config(ctx)?.complete == true }
    expanding?.let { pl ->
        AiExpandPlaylistDialog(provider, pl, onClose = { expanding = null }) { added, missing ->
            scope.launch {
                snackbar.showSnackbar(ctx.getString(R.string.ai_expand_done, added, pl.name) + (if (missing > 0) " · " + ctx.getString(R.string.ai_expand_missing, missing, provider.label(ctx)) else ""))
            }
            version++
        }
    }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    var busy by remember { mutableStateOf(false) }
    BackHandler { onClose() }

    LaunchedEffect(version) {
        lists = null; error = null
        lists = try { provider.playlists(ctx).filter { it.ownedByMe } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.error_generic); emptyList() }
    }
    fun fail(e: Exception) { scope.launch { snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)) } }

    // Export: the tracks are read when the file destination is chosen.
    var exporting by remember { mutableStateOf<Playlist?>(null) }
    val export = rememberPlaylistExporter { uri: Uri?, fmt ->
        val pl = exporting
        exporting = null
        if (uri == null || pl == null) return@rememberPlaylistExporter
        scope.launch {
            try {
                val tracks = provider.tracks(ctx, pl.id)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(fmt.encode(pl.name, tracks)) } }
                snackbar.showSnackbar(ctx.getString(R.string.tools_export_done, tracks.size))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
        }
    }

    renaming?.let { pl ->
        var name by remember(pl.id) { mutableStateOf(pl.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.manage_rename)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.manage_rename_title)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() != pl.name, onClick = {
                    renaming = null; busy = true
                    scope.launch {
                        try {
                            provider.renamePlaylist(ctx, pl.id, name.trim())
                            // Syncs show the playlist by the name they saved: keep it current.
                            store.data.jobs.filter { j -> (j.source.provider == provider.id && j.source.playlistId == pl.id) || (j.target.provider == provider.id && j.target.playlistId == pl.id) }
                                .forEach { j ->
                                    val srcHit = j.source.provider == provider.id && j.source.playlistId == pl.id
                                    val dstHit = j.target.provider == provider.id && j.target.playlistId == pl.id
                                    // The file provider renames its id too and updates the jobs itself.
                                    if (provider.id != LocalFilesProvider.id) store.upsertJob(j.copy(
                                        source = if (srcHit) j.source.copy(playlistName = name.trim()) else j.source,
                                        target = if (dstHit) j.target.copy(playlistName = name.trim()) else j.target,
                                    ))
                                }
                            snackbar.showSnackbar(ctx.getString(R.string.manage_renamed))
                            version++
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { pl ->
        val used = store.data.jobs.count { j -> (j.source.provider == provider.id && j.source.playlistId == pl.id) || (j.target.provider == provider.id && j.target.playlistId == pl.id) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Column {
                    Text(stringResource(R.string.manage_delete_confirm, pl.name, provider.label(ctx)))
                    if (used > 0) {
                        Spacer(Modifier.padding(4.dp))
                        Text(pluralStringResource(R.plurals.manage_delete_used, used, used), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null; busy = true
                    scope.launch {
                        try { provider.deletePlaylist(ctx, pl.id); snackbar.showSnackbar(ctx.getString(R.string.manage_deleted)); version++ } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { fail(e) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.yes), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.no)) } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderBadge(provider, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.manage_title, provider.label(ctx)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                actions = { if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).width(20.dp)) },
            )
        },
    ) { padding ->
        val all = lists
        when {
            all == null -> Row(Modifier.padding(padding).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading))
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(padding).padding(24.dp))
            all.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.manage_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(all, key = { it.id }) { pl ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (pl.trackCount >= 0) Text(stringResource(R.string.tracks_count, pl.trackCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (provider.canRenamePlaylists) DropdownMenuItem(text = { Text(stringResource(R.string.manage_rename)) }, onClick = { menu = false; renaming = pl })
                                    if (aiReady && provider.canWrite) DropdownMenuItem(text = { Text(stringResource(R.string.ai_expand)) }, onClick = { menu = false; expanding = pl })
                                    ExportMenuItems { fmt -> menu = false; exporting = pl; export(fmt, pl.name) }
                                    if (provider.canDeletePlaylists) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; deleting = pl },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
