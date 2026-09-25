package com.xlollx.songport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncPlan
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.launch

/**
 * Cosa farebbe la sync, prima di farla: gia' presenti, da aggiungere, da rimuovere, non trovati,
 * e gli abbinamenti incerti da confermare o cambiare sul posto. Per YouTube mostra anche quanta
 * quota giornaliera costerebbe. Si esegue solo dopo aver visto tutto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(job: SyncJob, onClose: () -> Unit, onRun: (SyncJob) -> Unit) {
    val ctx = LocalContext.current
    val engine = remember { SyncEngine(ctx) }
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<SyncPlan?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { onClose() }

    LaunchedEffect(job.id) {
        try { plan = engine.plan(job, { progress = it }, createTarget = false) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.error_generic) }
    }

    val dst = Providers.byId(job.target.provider)
    val dstName = dst?.displayName ?: job.target.provider
    val p = plan
    val hasWork = p != null && (p.toAdd.isNotEmpty() || p.toRemove.isNotEmpty() || job.target.playlistId == null)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title, job.name)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                p == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(progress?.let { progressLabel(it) } ?: stringResource(R.string.preview_computing))
                }
                else -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (job.target.playlistId == null) Text(stringResource(R.string.preview_target_new), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.preview_present, p.alreadyPresent))
                            Text(stringResource(R.string.preview_add, p.toAdd.size))
                            if (job.mirrorRemovals) Text(stringResource(R.string.preview_remove, p.toRemove.size))
                            Text(stringResource(R.string.preview_unmatched, p.unmatched.size))
                            if (p.uncertain.isNotEmpty()) Text(stringResource(R.string.preview_uncertain, p.uncertain.size), color = MaterialTheme.colorScheme.tertiary)
                            if (p.ignored > 0) Text(stringResource(R.string.preview_ignored, p.ignored))
                            p.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                            if (!hasWork) { Spacer(Modifier.height(6.dp)); Text(stringResource(R.string.preview_nothing)) }
                            if (dst?.serviceId == YouTubeProvider.SERVICE) {
                                QuotaCard(
                                    used = QuotaMeter.used(data, YouTubeProvider.SERVICE),
                                    sharedCredentials = !dst.usesOwnCredentials(ctx),
                                    estimate = QuotaMeter.estimateAdd(p.toAdd.size),
                                )
                            }
                        }
                    }
                    if (hasWork) {
                        Button(onClick = { onRun(job) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.run_now)) }
                    }
                    if (p.uncertain.isNotEmpty()) {
                        Text(stringResource(R.string.review_section_uncertain, p.uncertain.size), style = MaterialTheme.typography.titleMedium)
                        var openReview by remember { mutableStateOf<String?>(null) }
                        p.uncertain.forEach { review ->
                            ReviewRow(
                                review = review, dstName = dstName,
                                expanded = openReview == review.source.id,
                                onToggle = { openReview = if (openReview == review.source.id) null else review.source.id },
                                onKeep = { plan = p.copy(uncertain = p.uncertain - review) },
                                search = { q -> engine.searchOnTarget(job, q, review.source) },
                                webSearchUrl = dst?.let { d -> { q: String -> d.webSearchUrl(ctx, q) } },
                                onReplace = { chosen ->
                                    engine.rematchInPlan(job, review, chosen)
                                    plan = p.copy(
                                        uncertain = p.uncertain - review,
                                        toAdd = p.toAdd.filter { it.id != review.chosen.id } + chosen,
                                    )
                                },
                                onError = { m -> scope.launch { snackbar.showSnackbar(m.ifBlank { ctx.getString(R.string.error_generic) }) } },
                            )
                        }
                    }
                    TrackList(stringResource(R.string.preview_add, p.toAdd.size), p.toAdd)
                    TrackList(stringResource(R.string.preview_remove, p.toRemove.size), p.toRemove)
                    TrackList(stringResource(R.string.preview_unmatched, p.unmatched.size), p.unmatched)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun progressLabel(p: Progress): String = when (p.step) {
    Progress.Step.MATCHING -> stringResource(R.string.progress_matching, p.done, p.total)
    Progress.Step.FETCH_TARGET -> stringResource(R.string.progress_fetch_target)
    else -> stringResource(R.string.progress_fetch_source)
}

@Composable
private fun TrackList(title: String, tracks: List<Track>, max: Int = 30) {
    if (tracks.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    tracks.take(max).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    if (tracks.size > max) Text(stringResource(R.string.preview_more, tracks.size - max), style = MaterialTheme.typography.bodySmall)
}
