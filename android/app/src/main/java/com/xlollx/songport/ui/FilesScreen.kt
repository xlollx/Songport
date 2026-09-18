package com.xlollx.songport.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The file playlists (imports and backups), on their own screen: a search field, one section per
 * origin ("Spotify - Rap" sits under "Spotify" as "Rap"), one compact row per playlist and a menu
 * with export and delete. Dozens of backups stay browsable instead of stretching a card for pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var lists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(version) { lists = withContext(Dispatchers.IO) { LocalFilesProvider.playlists(ctx) } }
    BackHandler { onClose() }

    // One launcher per format: CreateDocument fixes the MIME type when it is built.
    var exporting by remember { mutableStateOf<String?>(null) }
    fun writeExport(uri: Uri?, format: LocalFilesProvider.Export) {
        val id = exporting
        exporting = null
        if (uri == null || id == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(LocalFilesProvider.exportText(ctx, id, format)) }
            }
            snackbar.showSnackbar(ctx.getString(R.string.csv_exported))
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.CSV.mime)) { writeExport(it, LocalFilesProvider.Export.CSV) }
    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalFilesProvider.Export.M3U.mime)) { writeExport(it, LocalFilesProvider.Export.M3U) }

    val filtered = lists.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    // "Service - Name" backups group under the service; anything else under a plain heading.
    val groups = filtered.groupBy { it.name.substringBefore(" - ", "").trim() }
        .toSortedMap(compareBy<String> { it.isEmpty() }.thenBy { it.lowercase() })
    val total = lists.sumOf { it.trackCount.coerceAtLeast(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.files_title))
                        Text(stringResource(R.string.files_summary, lists.size, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
            )
        },
    ) { padding ->
        if (lists.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.csv_empty), textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (lists.size > 6) item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(stringResource(R.string.files_search)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            groups.forEach { (group, items) ->
                item(key = "g-$group") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        (group.ifEmpty { stringResource(R.string.files_group_other) }) + " · " + items.size,
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(items, key = { it.id }) { pl ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (group.isEmpty()) pl.name else pl.name.substringAfter(" - ").trim(),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.tracks_count, pl.trackCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.csv_export)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.csv_export_csv)) }, onClick = { menu = false; exporting = pl.id; csvLauncher.launch(pl.name + ".csv") })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.csv_export_m3u)) }, onClick = { menu = false; exporting = pl.id; m3uLauncher.launch(pl.name + ".m3u8") })
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; LocalFilesProvider.delete(ctx, pl.id); version++ },
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
