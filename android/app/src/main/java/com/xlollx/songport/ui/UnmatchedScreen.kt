package com.xlollx.songport.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.xlollx.songport.R
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.launch

/**
 * Da rivedere dopo una sync: gli abbinamenti incerti (confermare o cambiare) e i brani non trovati
 * (cercare a mano o ignorare). Ogni scelta finisce in cache, cosi' le sync future la rispettano.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnmatchedScreen(reportId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { Store.get(ctx) }
    val data by store.state.collectAsState()
    val report = data.reports.firstOrNull { it.id == reportId }
    val job = report?.let { r -> data.jobs.firstOrNull { it.id == r.jobId } }
    val engine = remember { SyncEngine(ctx) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title, report?.jobName ?: "")) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
            )
        },
    ) { padding ->
        val reviews = report?.reviewTracks.orEmpty()
        val tracks = report?.unmatchedTracks.orEmpty()
        if (report == null || job == null || (tracks.isEmpty() && reviews.isEmpty())) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.unmatched_none), textAlign = TextAlign.Center)
            }
            return@Scaffold
        }
        val dstName = Providers.byId(job.target.provider)?.displayName ?: job.target.provider
        fun fail(msg: String) { scope.launch { snackbar.showSnackbar(msg.ifBlank { ctx.getString(R.string.error_generic) }) } }

        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (reviews.isNotEmpty()) {
                item(key = "h-review") { Text(stringResource(R.string.review_section_uncertain, reviews.size), style = MaterialTheme.typography.titleMedium) }
                items(reviews, key = { "r-" + it.source.id }) { review ->
                    ReviewRow(
                        review = review, dstName = dstName,
                        onKeep = { engine.confirmMatch(reportId, review) },
                        search = { q -> engine.searchOnTarget(job, q) },
                        onReplace = { chosen -> engine.replaceMatch(job, reportId, review, chosen) },
                        onError = ::fail,
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                item(key = "h-unmatched") {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.review_section_unmatched, tracks.size), style = MaterialTheme.typography.titleMedium)
                }
                items(tracks, key = { "u-" + it.id }) { track -> UnmatchedRow(job, reportId, track, engine, dstName, ::fail) }
            }
        }
    }
}

/** Origine -> scelto, con il punteggio; "Va bene" o ricerca di un sostituto. */
@Composable
fun ReviewRow(
    review: MatchReview,
    dstName: String,
    onKeep: () -> Unit,
    search: suspend (String) -> List<Track>,
    onReplace: suspend (Track) -> Unit,
    onError: (String) -> Unit,
) {
    var changing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            TrackLine(review.source, emphasis = true)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                TrackLine(review.chosen, Modifier.weight(1f))
                Text(stringResource(R.string.review_score, (review.score * 100).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            if (!changing) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { changing = true }) { Text(stringResource(R.string.review_change)) }
                    TextButton(onClick = onKeep) { Text(stringResource(R.string.review_keep)) }
                }
            } else {
                val initial = if (review.source.artists.isEmpty()) review.source.title else "${review.source.artistLine} - ${review.source.title}"
                MatchSearch(
                    initialQuery = initial, targetName = dstName, pickLabel = stringResource(R.string.review_use_this),
                    search = search, onPick = onReplace, onError = onError,
                    extraActions = { TextButton(onClick = { changing = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}

@Composable
private fun UnmatchedRow(job: SyncJob, reportId: String, track: Track, engine: SyncEngine, dstName: String, onError: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            TrackLine(track, emphasis = true)
            Spacer(Modifier.height(8.dp))
            MatchSearch(
                initialQuery = if (track.artists.isEmpty()) track.title else "${track.artistLine} - ${track.title}",
                targetName = dstName,
                pickLabel = stringResource(R.string.unmatched_add),
                search = { q -> engine.searchOnTarget(job, q) },
                onPick = { c -> engine.resolveManually(job, reportId, track, c) },
                onError = onError,
                extraActions = { busy ->
                    TextButton(enabled = !busy, onClick = { engine.ignore(job, reportId, track) }) { Text(stringResource(R.string.unmatched_ignore)) }
                },
            )
        }
    }
}
