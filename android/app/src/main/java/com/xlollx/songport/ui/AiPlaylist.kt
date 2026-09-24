package com.xlollx.songport.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * "Generatore di playlist" (beta): l'utente descrive, la SUA AI propone, Songport cerca. La lista
 * proposta viene salvata come file (cosi' resta consultabile in File) e, se la destinazione e' un
 * servizio, diventa l'origine di una sync normale creata e avviata subito: ricerca, non trovati e
 * revisione sono quelli di sempre.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiPlaylistCard(snackbar: SnackbarHostState, onSyncStarted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AiClient.config(ctx)) }
    var setupOpen by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var seeds by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(25f) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val targets = remember { (Providers.connectors().filter { it.isConnected(ctx) } + LocalFilesProvider).distinctBy { it.id }.filter { it.canWrite && it.canCreatePlaylists } }
    var targetId by remember { mutableStateOf(targets.firstOrNull { it.requiresAuth }?.id ?: LocalFilesProvider.id) }

    if (setupOpen) AiSetupDialog(config, onDismiss = { setupOpen = false }) { config = it; setupOpen = false }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                StatusPill(stringResource(R.string.beta), Tone.Neutral)
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ai_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            val c = config
            if (c == null || !c.complete) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { setupOpen = true }) { Text(stringResource(R.string.ai_setup)) }
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${c.vendor.label} · ${c.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = { setupOpen = true }) { Text(stringResource(R.string.ai_setup_change)) }
            }
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it }, minLines = 2, maxLines = 5,
                label = { Text(stringResource(R.string.ai_prompt)) }, placeholder = { Text(stringResource(R.string.ai_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = seeds, onValueChange = { seeds = it }, minLines = 1, maxLines = 5,
                label = { Text(stringResource(R.string.ai_seeds)) }, placeholder = { Text(stringResource(R.string.ai_seeds_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ai_count, count.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(value = count, onValueChange = { count = it }, valueRange = 5f..100f, steps = 18)
            Text(stringResource(R.string.ai_target), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                targets.forEach { p ->
                    FilterChip(selected = p.id == targetId, onClick = { targetId = p.id }, label = { Text(p.label(ctx)) }, leadingIcon = { ProviderDot(p) })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.editor_new_playlist_name)) }, placeholder = { Text(stringResource(R.string.ai_default_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_generating), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                }
                Button(enabled = !busy && prompt.isNotBlank(), onClick = {
                    busy = true
                    val target = targets.firstOrNull { it.id == targetId } ?: LocalFilesProvider
                    val finalName = name.trim().ifBlank { ctx.getString(R.string.ai_default_name) }
                    val seedLines = seeds.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    scope.launch {
                        try {
                            val language = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
                            val tracks = AiClient.generate(c, AiClient.Prompt(prompt, count.toInt(), seedLines, language))
                            if (tracks.isEmpty()) throw com.xlollx.songport.model.ProviderException(ctx.getString(R.string.ai_empty))
                            val fileId = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(ctx, finalName, tracks) }
                            if (target.id == LocalFilesProvider.id) {
                                snackbar.showSnackbar(ctx.getString(R.string.ai_done_file, tracks.size, fileId))
                            } else {
                                val job = SyncJob(
                                    id = UUID.randomUUID().toString(),
                                    name = finalName,
                                    source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = fileId, playlistName = fileId),
                                    target = PlaylistRef(provider = target.id, playlistId = null, playlistName = finalName),
                                )
                                Store.get(ctx).upsertJob(job)
                                Scheduler.runNow(ctx, job.id)
                                snackbar.showSnackbar(ctx.getString(R.string.ai_done_sync, tracks.size, target.label(ctx)))
                                onSyncStarted()
                            }
                            prompt = ""; seeds = ""; name = ""
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.ai_generate)) }
            }
        }
    }
}

/** Fornitore, URL (solo per i server compatibili), chiave e modello, con la lista dei modelli letta dall'API. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AiSetupDialog(initial: AiClient.Config?, onDismiss: () -> Unit, onSaved: (AiClient.Config?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var vendor by remember { mutableStateOf(initial?.vendor ?: AiClient.Vendor.OPENAI) }
    var key by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var model by remember { mutableStateOf(initial?.model.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun current() = AiClient.Config(vendor, key.trim(), model.trim(), baseUrl.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.ai_vendor), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiClient.Vendor.entries.forEach { v ->
                        FilterChip(selected = vendor == v, onClick = { vendor = v; models = emptyList() }, label = { Text(v.label) })
                    }
                }
                if (vendor == AiClient.Vendor.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true,
                        label = { Text(stringResource(R.string.ai_base_url)) }, placeholder = { Text(stringResource(R.string.ai_base_url_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ai_key)) }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ai_key_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (vendor.keyUrl.isNotEmpty()) TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vendor.keyUrl))) } }) { Text(stringResource(R.string.ai_key_get)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ai_model)) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) { CircularProgressIndicator(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(enabled = !loading && (key.isNotBlank() || vendor == AiClient.Vendor.CUSTOM), onClick = {
                            loading = true; error = null
                            scope.launch {
                                try {
                                    models = AiClient.models(current())
                                    if (models.isEmpty()) error = ctx.getString(R.string.ai_models_none) else modelsOpen = true
                                } catch (e: Exception) { error = e.message ?: ctx.getString(R.string.error_generic) }
                                loading = false
                            }
                        }) { Text(stringResource(R.string.ai_models_load)) }
                        DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                            models.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelsOpen = false }) }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = current().complete, onClick = { AiClient.save(ctx, current()); onSaved(current()) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { AiClient.clear(ctx); onSaved(null) }) { Text(stringResource(R.string.ai_forget), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
