package com.xlollx.songport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.StoreData
import androidx.compose.material.icons.filled.Favorite
import com.xlollx.songport.sync.SupportPrompt
import com.xlollx.songport.data.Store
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.PlaylistLinks
import com.xlollx.songport.sync.SyncState
import java.util.UUID

@Composable
fun SyncsScreen(
    data: StoreData,
    onEdit: (SyncJob) -> Unit,
    onPreview: (SyncJob) -> Unit,
    onRun: (SyncJob) -> Unit,
    onRunAll: () -> Unit,
    onDelete: (SyncJob) -> Unit,
    onGoToAccounts: () -> Unit,
    onReview: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val running by SyncState.running.collectAsState()
    if (data.jobs.isEmpty()) {
        val anyConnected = remember { Providers.connectors().any { it.requiresAuth && it.isConnected(ctx) } }
        if (anyConnected) {
            EmptyState(Icons.Filled.Sync, stringResource(R.string.empty_syncs_title), stringResource(R.string.empty_syncs_body_connected))
        } else {
            EmptyState(
                Icons.Filled.Sync, stringResource(R.string.empty_syncs_title), stringResource(R.string.empty_syncs_body),
                stringResource(R.string.empty_syncs_cta_accounts), onGoToAccounts,
            )
        }
        return
    }
    val lastRun = data.jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") {
            val n = data.jobs.size
            val last = if (lastRun > 0) " · " + stringResource(R.string.hero_last, formatDate(lastRun)) else ""
            HeroHeader(stringResource(R.string.hero_title), ctx.resources.getQuantityString(R.plurals.hero_jobs, n, n) + last) {
                Button(
                    onClick = onRunAll,
                    enabled = running.isEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White, contentColor = Brand.Indigo,
                        disabledContainerColor = Color.White.copy(alpha = 0.5f), disabledContentColor = Brand.Indigo.copy(alpha = 0.6f),
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sync_all))
                }
            }
        }
        // Una volta sola, dopo la prima sync grande: il momento in cui si vede quanto tempo ha fatto risparmiare.
        val support = SupportPrompt.pending(Store.get(ctx))
        if (support != null) item(key = "support") { SupportCard(support) }
        items(data.jobs, key = { it.id }) { job ->
            JobCard(job, data, running[job.id], running.containsKey(job.id), onEdit, onPreview, onRun, onDelete, onReview)
        }
    }
}

@Composable
private fun JobCard(
    job: SyncJob, data: StoreData, progress: Progress?, isRunning: Boolean,
    onEdit: (SyncJob) -> Unit, onPreview: (SyncJob) -> Unit, onRun: (SyncJob) -> Unit, onDelete: (SyncJob) -> Unit,
    onReview: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var liveOpen by remember { mutableStateOf(false) }
    if (liveOpen && isRunning) LiveSheet(job) { liveOpen = false }
    var extendOpen by remember { mutableStateOf(false) }
    if (extendOpen) AiExtendDialog(job) { extendOpen = false }
    val report = data.reports.firstOrNull { it.id == job.lastReportId }
    val src = Providers.byId(job.source.provider)
    val dst = Providers.byId(job.target.provider)
    val srcName = playlistDisplayName(job.source.playlistId, job.source.playlistName)
    val dstName = job.target.playlistName.ifBlank { stringResource(R.string.editor_new_playlist) }
    Card(
        // While running, a tap opens the live list of tracks processed so far.
        Modifier.fillMaxWidth().clickable(enabled = isRunning) { liveOpen = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(56.dp).height(36.dp)) {
                    ProviderBadge(src, 34.dp)
                    Box(Modifier.align(Alignment.CenterEnd)) { ProviderBadge(dst, 34.dp) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Written by the user's AI: a small spark before the name, and "add tracks" in the menu.
                        if (job.aiPrompt != null) {
                            Icon(Icons.Filled.AutoAwesome, stringResource(R.string.ai_badge_desc), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(job.name.ifBlank { job.source.playlistName }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        (src?.label(ctx) ?: job.source.provider) + " · " + srcName +
                            (if (job.linkedJobId != null) "  ↔  " else "  →  ") +
                            (dst?.label(ctx) ?: job.target.provider) + " · " + dstName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    isRunning -> StatusPill(stringResource(R.string.pill_running), Tone.Accent, Icons.Filled.Sync)
                    !job.enabled -> StatusPill(stringResource(R.string.disabled), Tone.Neutral)
                    report == null -> StatusPill(stringResource(R.string.pill_never), Tone.Neutral)
                    report.ok -> StatusPill(stringResource(R.string.pill_ok), Tone.Ok, Icons.Filled.CheckCircle)
                    else -> StatusPill(stringResource(R.string.pill_error), Tone.Error, Icons.Filled.Warning)
                }
            }
            if (isRunning) {
                val pausedSet by SyncState.paused.collectAsState()
                val paused = job.id in pausedSet
                Spacer(Modifier.height(12.dp))
                val pct = progress?.percent
                if (pct != null) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (if (paused) stringResource(R.string.sync_paused) + " · " else "") + progressText(progress) + (pct?.let { " · $it%" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (paused) SyncState.resume(job.id) else SyncState.pause(job.id) }) {
                        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (paused) R.string.sync_resume else R.string.sync_pause))
                    }
                    IconButton(onClick = { SyncState.stop(job.id) }) { Icon(Icons.Filled.Stop, stringResource(R.string.sync_stop)) }
                }
            } else if (report != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    Notifications.summary(ctx, report), style = MaterialTheme.typography.bodySmall,
                    color = if (report.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            // Brani da sistemare a mano: raggiungibili appena la ricerca e' finita, anche mentre la
            // sync sta ancora aggiungendo, e dopo, finche' ne restano.
            val toFix = report?.takeIf { it.jobId == job.id }?.let { it.unmatchedTracks.size + it.reviewTracks.size } ?: 0
            if (toFix > 0 && report != null) {
                Spacer(Modifier.height(6.dp))
                FilledTonalButton(onClick = { onReview(report.id) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Build, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.review_now, toFix))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                val last = if (job.lastRunEpoch > 0) formatDate(job.lastRunEpoch) else stringResource(R.string.last_run_never)
                Text(
                    scheduleLabel(job.schedule) + (if (job.mirrorRemovals) " · " + stringResource(R.string.editor_mirror) else "") + " · " + last,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = { onRun(job) }, enabled = !isRunning) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.run_now))
                }
                OverflowMenu(
                    listOfNotNull(
                        if (job.aiPrompt != null) MenuAction(stringResource(R.string.ai_extend), { extendOpen = true }) else null,
                        MenuAction(stringResource(R.string.preview), { onPreview(job) }),
                        MenuAction(stringResource(R.string.edit), { onEdit(job) }),
                        MenuAction(stringResource(R.string.delete), { confirmDelete = true }, destructive = true),
                    ),
                    enabled = !isRunning,
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(job) }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.no)) } },
        )
    }
}

/** Live view of a running sync: what was searched, found, taken from the cache or not found. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveSheet(job: SyncJob, onDismiss: () -> Unit) {
    val all by SyncState.items.collectAsState()
    val running by SyncState.running.collectAsState()
    val items = all[job.id].orEmpty()
    val progress = running[job.id]
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.live_title), style = MaterialTheme.typography.titleLarge)
            Text(progressText(progress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) Text(stringResource(R.string.live_empty), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items.size) { i ->
                    val it = items[i]
                    Row(verticalAlignment = Alignment.Top) {
                        val (icon, tint) = when (it.outcome) {
                            SyncState.Outcome.FOUND -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
                            SyncState.Outcome.CACHED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
                            SyncState.Outcome.NOT_FOUND -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
                            SyncState.Outcome.ADDED -> Icons.Filled.CheckCircle to Tones.onSuccessContainer()
                            SyncState.Outcome.ADD_FAILED -> Icons.Filled.Warning to MaterialTheme.colorScheme.error
                            SyncState.Outcome.REMOVED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(it.source, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (it.outcome) {
                                    SyncState.Outcome.FOUND -> stringResource(R.string.live_found, it.result ?: "")
                                    SyncState.Outcome.CACHED -> stringResource(R.string.live_cached, it.result ?: "")
                                    SyncState.Outcome.NOT_FOUND -> stringResource(R.string.live_not_found)
                                    SyncState.Outcome.ADDED -> stringResource(R.string.live_added)
                                    SyncState.Outcome.ADD_FAILED -> stringResource(R.string.live_add_failed, it.result ?: "")
                                    SyncState.Outcome.REMOVED -> stringResource(R.string.live_removed)
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun progressText(p: Progress?): String = when (p?.step) {
    null -> stringResource(R.string.running)
    Progress.Step.FETCH_SOURCE -> stringResource(R.string.progress_fetch_source)
    Progress.Step.CREATE_TARGET -> stringResource(R.string.progress_create_target)
    Progress.Step.FETCH_TARGET -> stringResource(R.string.progress_fetch_target)
    Progress.Step.MATCHING -> stringResource(R.string.progress_matching, p?.done ?: 0, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.WAITING -> stringResource(R.string.progress_waiting, ((p?.total ?: 0) - (p?.done ?: 0)).coerceAtLeast(0))
    Progress.Step.ADDING -> stringResource(R.string.progress_adding, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.REMOVING -> stringResource(R.string.progress_removing, p?.total ?: 0) + (p?.label?.let { " · $it" } ?: "")
    Progress.Step.BACKUP -> stringResource(R.string.tools_progress_backup, p?.done ?: 0, p?.total ?: 0)
    Progress.Step.DEDUPE -> stringResource(R.string.tools_progress_dedupe, p?.done ?: 0, p?.total ?: 0)
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

private sealed class Loaded {
    object Loading : Loaded()
    data class Ok(val items: List<Playlist>) : Loaded()
    data class Failed(val message: String) : Loaded()
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SyncEditorScreen(job: SyncJob, onCancel: () -> Unit, onSave: (SyncJob, SyncJob?) -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onCancel() }
    val isNew = job.name.isEmpty()
    // Solo i connettori aggiunti nella scheda Account: uno tolto di li' non deve piu' essere
    // scegliibile, anche se il token o la sessione nell'app plugin sono ancora validi. I servizi gia'
    // usati dal job che si sta modificando restano nell'elenco per non cambiarlo di nascosto.
    val available = remember {
        (Providers.connectors().filter { it.isConnected(ctx) } +
            listOfNotNull(Providers.byId(job.source.provider), Providers.byId(job.target.provider))).distinctBy { it.id }
    }

    var name by remember { mutableStateOf(job.name) }
    var srcProvider by remember { mutableStateOf(job.source.provider.ifEmpty { available.firstOrNull()?.id ?: "" }) }
    var srcPlaylist by remember { mutableStateOf<Playlist?>(job.source.playlistId?.let { Playlist(it, job.source.playlistName) }) }
    val writable = available.filter { it.canWrite }
    var dstProvider by remember { mutableStateOf(job.target.provider.ifEmpty { writable.getOrNull(1)?.id ?: writable.firstOrNull()?.id ?: "" }) }
    var createNew by remember { mutableStateOf(job.target.playlistId == null) }
    var dstPlaylist by remember { mutableStateOf<Playlist?>(job.target.playlistId?.let { Playlist(it, job.target.playlistName) }) }
    var newName by remember { mutableStateOf(if (job.target.playlistId == null) job.target.playlistName else "") }
    var schedule by remember { mutableStateOf(job.schedule) }
    var mirror by remember { mutableStateOf(job.mirrorRemovals) }
    var wifiOnly by remember { mutableStateOf(job.wifiOnly) }
    var enabled by remember { mutableStateOf(job.enabled) }
    var bidirectional by remember { mutableStateOf(job.linkedJobId != null) }
    var error by remember { mutableStateOf<String?>(null) }

    var srcLists by remember { mutableStateOf<Loaded>(Loaded.Loading) }
    var dstLists by remember { mutableStateOf<Loaded>(Loaded.Loading) }

    LaunchedEffect(srcProvider) {
        srcLists = Loaded.Loading
        srcLists = loadPlaylists(ctx, srcProvider, forTarget = false)
    }
    LaunchedEffect(dstProvider) {
        dstLists = Loaded.Loading
        dstLists = loadPlaylists(ctx, dstProvider, forTarget = true)
    }
    // Liked songs to a service that can like: propose "liked songs" as the target instead of a new playlist.
    LaunchedEffect(srcPlaylist?.id, dstProvider) {
        val dstObj = Providers.byId(dstProvider)
        val sid = srcPlaylist?.id
        if (MusicProvider.isLibrary(sid) && dstObj?.supportsLibrary(sid, asTarget = true) == true && dstPlaylist == null) {
            createNew = false
            dstPlaylist = Playlist(sid!!, srcPlaylist?.name ?: "")
        }
    }
    // Job creato da un link condiviso: l'id c'e', il nome ancora no.
    LaunchedEffect(Unit) {
        val sp = srcPlaylist
        if (sp != null && sp.name.isBlank() && !MusicProvider.isLibrary(sp.id)) {
            val p = Providers.byId(srcProvider)
            if (p != null) srcPlaylist = runCatching { p.playlistInfo(ctx, sp.id) }.getOrElse { Playlist(sp.id, p.displayName) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isNew) R.string.editor_title_new else R.string.editor_title_edit)) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (available.isEmpty()) {
                Text(stringResource(R.string.no_connected_providers), color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(srcPlaylist?.let { playlistDisplayName(it.id, it.name) } ?: "") })

            Text(stringResource(R.string.editor_source), style = MaterialTheme.typography.titleMedium)
            // Il servizio di un link incollato puo' non essere fra quelli collegati (playlist pubblica).
            val srcProviders = (available + listOfNotNull(Providers.byId(srcProvider))).distinctBy { it.id }
            ProviderPicker(srcProviders, srcProvider) { srcProvider = it; srcPlaylist = null }
            PlaylistPicker(srcLists, srcPlaylist, label = stringResource(R.string.editor_playlist)) { srcPlaylist = it }
            LinkImportField { providerId, playlist ->
                srcProvider = providerId
                srcPlaylist = playlist
            }

            Text(stringResource(R.string.editor_target), style = MaterialTheme.typography.titleMedium)
            ProviderPicker(writable, dstProvider) { dstProvider = it; dstPlaylist = null }
            val dstObj = Providers.byId(dstProvider)
            val canCreate = dstObj?.canCreatePlaylists != false
            if (!canCreate && createNew) createNew = false
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = createNew, enabled = canCreate, onCheckedChange = { createNew = it })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.editor_new_playlist))
            }
            if (!canCreate && dstObj != null) {
                Text(stringResource(R.string.error_create_unsupported, dstObj.displayName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (createNew) {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.editor_new_playlist_name)) },
                    placeholder = { Text(srcPlaylist?.let { playlistDisplayName(it.id, it.name) } ?: "") })
            } else {
                PlaylistPicker(dstLists, dstPlaylist, label = stringResource(R.string.editor_playlist), onlyOwned = true) { dstPlaylist = it }
            }

            Text(stringResource(R.string.editor_schedule), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                // due righe di chip per stare anche su schermi stretti
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Schedule.entries.take(3).forEach { s -> FilterChip(selected = schedule == s, onClick = { schedule = s }, label = { Text(scheduleLabel(s)) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Schedule.entries.drop(3).forEach { s -> FilterChip(selected = schedule == s, onClick = { schedule = s }, label = { Text(scheduleLabel(s)) }) }
                    }
                }
            }
            SwitchRow(stringResource(R.string.editor_mirror), stringResource(R.string.editor_mirror_desc), mirror) { mirror = it }
            val dstProviderObj = Providers.byId(dstProvider)
            if (mirror && dstProviderObj != null && !dstProviderObj.canRemoveTracks) {
                Text(
                    stringResource(R.string.editor_mirror_unsupported, dstProviderObj.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Bidirezionale: crea la sync gemella al contrario, senza rimozioni (niente cancellazioni a ping-pong).
            val srcObj = Providers.byId(srcProvider)
            val biAllowed = !createNew && srcObj?.canWrite == true &&
                srcObj.supportsLibrary(srcPlaylist?.id, asTarget = true) &&
                dstObj?.supportsLibrary(dstPlaylist?.id, asTarget = false) == true
            if (!biAllowed && bidirectional) bidirectional = false
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.editor_bidirectional))
                    Text(
                        stringResource(if (biAllowed) R.string.editor_bidirectional_desc else R.string.editor_bidirectional_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = bidirectional, enabled = biAllowed, onCheckedChange = { bidirectional = it })
            }
            SwitchRow(stringResource(R.string.editor_wifi_only), null, wifiOnly) { wifiOnly = it }
            SwitchRow(stringResource(R.string.editor_enabled), null, enabled) { enabled = it }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val sp = srcPlaylist
                        val dp = dstPlaylist
                        if (srcProvider.isEmpty() || dstProvider.isEmpty() || sp == null || (!createNew && dp == null)) {
                            error = ctx.getString(R.string.editor_error_incomplete)
                            return@Button
                        }
                        val srcName = sp.name
                        val target = if (createNew) PlaylistRef(dstProvider, null, newName.ifBlank { srcName })
                        else PlaylistRef(dstProvider, dp!!.id, dp.name)
                        val main = job.copy(
                            name = name.ifBlank { srcName },
                            source = PlaylistRef(srcProvider, sp.id, srcName),
                            target = target,
                            schedule = schedule, mirrorRemovals = mirror, wifiOnly = wifiOnly, enabled = enabled,
                        )
                        val reverse = if (bidirectional && !createNew && dp != null) SyncJob(
                            id = job.linkedJobId ?: UUID.randomUUID().toString(),
                            name = "${main.name} ↔",
                            source = target,
                            target = PlaylistRef(srcProvider, sp.id, srcName),
                            schedule = schedule, mirrorRemovals = false, wifiOnly = wifiOnly, enabled = enabled,
                            linkedJobId = job.id,
                        ) else null
                        onSave(main.copy(linkedJobId = reverse?.id), reverse)
                    },
                ) { Text(stringResource(R.string.save)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun loadPlaylists(ctx: android.content.Context, providerId: String, forTarget: Boolean): Loaded {
    val p = Providers.byId(providerId) ?: return Loaded.Ok(emptyList())
    // Servizio non collegato (es. playlist pubblica da link): niente elenco, nessun errore.
    if (!p.isConnected(ctx)) return Loaded.Ok(emptyList())
    return try {
        val lists = p.playlists(ctx)
        // "Brani preferiti": come origine dove il servizio li espone, come destinazione dove si possono scrivere.
        Loaded.Ok(p.libraryEntries(ctx, asTarget = forTarget) + lists)
    } catch (e: Exception) {
        Loaded.Failed(e.message ?: ctx.getString(R.string.error_generic))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderPicker(available: List<MusicProvider>, selected: String, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    // Le chip vanno a capo: con gli account multipli e i server personali possono essere molte.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        available.forEach { p ->
            FilterChip(
                selected = p.id == selected,
                onClick = { onSelect(p.id) },
                label = { Text(p.label(ctx)) },
                leadingIcon = { ProviderDot(p) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPicker(loaded: Loaded, selected: Playlist?, label: String, onlyOwned: Boolean = false, onSelect: (Playlist) -> Unit) {
    when (loaded) {
        Loaded.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading))
        }
        is Loaded.Failed -> Text(loaded.message, color = MaterialTheme.colorScheme.error)
        is Loaded.Ok -> {
            var expanded by remember { mutableStateOf(false) }
            val items = if (onlyOwned) loaded.items.filter { it.ownedByMe } else loaded.items
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.let { playlistDisplayName(it.id, it.name) } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(label) },
                    placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    items.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(playlistDisplayName(p.id, p.name))
                                    if (p.trackCount >= 0) Text(stringResource(R.string.tracks_count, p.trackCount), style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            onClick = { onSelect(p); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

/** Campo per incollare il link di una playlist pubblica e usarla come origine. */
@Composable
private fun LinkImportField(onResolved: (String, Playlist) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text(stringResource(R.string.link_paste)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    val ref = PlaylistLinks.parse(text)
                    val provider = ref?.let { Providers.byId(it.providerId) }
                    if (ref == null || provider == null) {
                        error = ctx.getString(R.string.link_invalid)
                    } else if (!provider.canRead(ctx, ref.playlistId)) {
                        error = ctx.getString(R.string.link_not_connected, provider.displayName)
                    } else {
                        error = null
                        busy = true
                        scope.launch {
                            val info = runCatching { provider.playlistInfo(ctx, ref.playlistId) }
                                .getOrElse { Playlist(ref.playlistId, provider.displayName) }
                            busy = false
                            text = ""
                            onResolved(provider.id, info)
                        }
                    }
                },
            ) { Text(stringResource(R.string.link_use)) }
        }
        if (busy) Text(stringResource(R.string.link_resolving), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** La richiesta di contributo: cosa ha appena fatto l'app, perche' e' gratis, un tocco per offrire un caffe'. */
@Composable
private fun SupportCard(report: com.xlollx.songport.model.SyncReport) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val count = remember(report.added) { java.text.NumberFormat.getIntegerInstance().format(report.added) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.support_prompt_title, count), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(stringResource(R.string.support_prompt_body, report.jobName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { SupportPrompt.close(store) }) {
                    Text(stringResource(R.string.support_prompt_later), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = {
                    if (BuildConfig.KOFI_URL.isNotBlank()) openSupport(ctx) else openSponsors(ctx)
                    SupportPrompt.close(store)
                }) {
                    Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.support_button))
                }
            }
        }
    }
}
