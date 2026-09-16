package com.xlollx.songport.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.launch

@Composable
fun LogScreen(data: StoreData, snackbar: SnackbarHostState, onResolve: (String) -> Unit) {
    if (data.reports.isEmpty()) {
        EmptyState(Icons.Filled.History, stringResource(R.string.empty_log_title), stringResource(R.string.empty_log_body))
        return
    }
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "diag") {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                // Apre una nuova issue su GitHub gia' compilata con il rapporto: l'utente la legge,
                // toglie quel che vuole e la invia con il proprio account. Nulla parte da solo.
                TextButton(onClick = {
                    val body = Diagnostics.report(ctx).take(5000)
                    val url = "https://github.com/xlollx/Songport/issues/new?title=" + Uri.encode("[Sync error] ") +
                        "&body=" + Uri.encode("<!-- Describe what you were doing. Remove anything you prefer not to share. -->\n\n```\n$body\n```")
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text(stringResource(R.string.report_issue)) }
                TextButton(onClick = {
                    val text = Diagnostics.report(ctx)
                    val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Songport diagnostics") }
                    ctx.startActivity(Intent.createChooser(share, ctx.getString(R.string.diag_share)))
                }) { Text(stringResource(R.string.diag_share)) }
            }
        }
        items(data.reports, key = { it.id }) { r -> ReportRow(r, data.jobs.firstOrNull { it.id == r.jobId }, snackbar, onResolve) }
    }
}

@Composable
private fun ReportRow(r: SyncReport, job: SyncJob?, snackbar: SnackbarHostState, onResolve: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val hasDetails = r.unmatched.isNotEmpty()
    Card(
        Modifier.fillMaxWidth().clickable(enabled = hasDetails) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            val okBg = Tones.successContainer()
            val okFg = Tones.onSuccessContainer()
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (r.ok) okBg else MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (r.ok) Icons.Filled.Check else Icons.Filled.PriorityHigh, null,
                    tint = if (r.ok) okFg else MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.jobName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(formatDate(r.startedEpoch), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    Notifications.summary(ctx, r) + " · " + stringResource(R.string.log_duration, r.durationMs / 1000),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (r.notes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    r.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                }
                if (expanded && hasDetails) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.log_unmatched_title), style = MaterialTheme.typography.labelMedium)
                    r.unmatched.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                val canRestore = job != null && r.removedTracks.isNotEmpty()
                val canReview = job != null && (r.unmatchedTracks.isNotEmpty() || r.reviewTracks.isNotEmpty())
                if (hasDetails || canRestore || canReview) {
                    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (hasDetails) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.hide_details else R.string.show_details))
                            }
                        }
                        if (canRestore && job != null) {
                            TextButton(enabled = !restoring, onClick = {
                                restoring = true
                                scope.launch {
                                    try {
                                        SyncEngine(ctx).restoreRemoved(job, r)
                                        snackbar.showSnackbar(ctx.getString(R.string.restored_done, r.removedTracks.size))
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
                                    }
                                    restoring = false
                                }
                            }) { Text(stringResource(R.string.restore_removed, r.removedTracks.size)) }
                        }
                        if (canReview) {
                            TextButton(onClick = { onResolve(r.id) }) { Text(stringResource(R.string.review_open)) }
                        }
                    }
                }
            }
        }
    }
}
