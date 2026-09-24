package com.xlollx.songport.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import com.xlollx.songport.model.Track
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Stato della scheda del generatore, fuori dalla schermata: testi, destinazione e la richiesta in
 * corso sopravvivono al cambio di scheda (la Column di Strumenti viene ricomposta da zero) e i
 * testi anche alla chiusura dell'app. La chiamata all'AI gira in uno scope proprio: se l'utente
 * va a guardare le sync mentre aspetta, la risposta lo trova al ritorno.
 */
object AiDraft {
    var prompt by mutableStateOf("")
    var seeds by mutableStateOf("")
    var name by mutableStateOf("")
    var count by mutableStateOf(25f)
    var targetId by mutableStateOf("")
    var busy by mutableStateOf(false)
    /** The AI's proposal, shown for a look (and a prune) before anything is created. */
    var proposed by mutableStateOf<List<Track>?>(null)
    /** Error of the last request, shown once by whoever is on screen. */
    var error by mutableStateOf<String?>(null)
    /** Models offered by the configured provider, read once per key/vendor. */
    var models by mutableStateOf<List<String>>(emptyList())

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ai_draft", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(ctx)
        prompt = p.getString("prompt", "") ?: ""
        seeds = p.getString("seeds", "") ?: ""
        name = p.getString("name", "") ?: ""
        count = p.getFloat("count", 25f)
        targetId = p.getString("target", "") ?: ""
    }

    fun persist(ctx: Context) {
        prefs(ctx).edit().putString("prompt", prompt).putString("seeds", seeds).putString("name", name)
            .putFloat("count", count).putString("target", targetId).apply()
    }

    fun clearText(ctx: Context) { prompt = ""; seeds = ""; name = ""; persist(ctx) }
}

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
    val d = AiDraft
    remember { d.load(ctx); true }
    var config by remember { mutableStateOf(AiClient.config(ctx)) }
    var setupOpen by remember { mutableStateOf(false) }
    val targets = remember { (Providers.connectors().filter { it.isConnected(ctx) } + LocalFilesProvider).distinctBy { it.id }.filter { it.canWrite && it.canCreatePlaylists } }
    val target = targets.firstOrNull { it.id == d.targetId } ?: targets.firstOrNull { it.requiresAuth } ?: LocalFilesProvider

    // Errors of a request that may have finished while another tab was open.
    LaunchedEffect(d.error) { d.error?.let { snackbar.showSnackbar(it); d.error = null } }

    if (setupOpen) AiSetupDialog(config, onDismiss = { setupOpen = false }) { config = it; d.models = emptyList(); setupOpen = false }
    d.proposed?.let { list ->
        AiReviewDialog(list, target.label(ctx), onDismiss = { d.proposed = null }) { kept ->
            d.proposed = null
            val finalName = d.name.trim().ifBlank { ctx.getString(R.string.ai_default_name) }
            val app = ctx.applicationContext
            d.scope.launch {
                try {
                    val fileId = withContext(Dispatchers.IO) { LocalFilesProvider.importTracks(app, finalName, kept) }
                    if (target.id == LocalFilesProvider.id) {
                        d.clearText(app)
                        snackbar.showSnackbar(app.getString(R.string.ai_done_file, kept.size, fileId))
                    } else {
                        val job = SyncJob(
                            id = UUID.randomUUID().toString(),
                            name = finalName,
                            source = PlaylistRef(provider = LocalFilesProvider.id, playlistId = fileId, playlistName = fileId),
                            target = PlaylistRef(provider = target.id, playlistId = null, playlistName = finalName),
                        )
                        Store.get(app).upsertJob(job)
                        Scheduler.runNow(app, job.id)
                        d.clearText(app)
                        onSyncStarted()
                        snackbar.showSnackbar(app.getString(R.string.ai_done_sync, kept.size, target.label(app)))
                    }
                } catch (e: Exception) {
                    d.error = e.message ?: app.getString(R.string.error_generic)
                }
            }
        }
    }

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
            // Provider and model on one line; the model is a menu right here, the rest is in the dialog.
            ModelRow(c, onChange = { setupOpen = true }) { m -> AiClient.save(ctx, c.copy(model = m)); config = c.copy(model = m) }
            OutlinedTextField(
                value = d.prompt, onValueChange = { d.prompt = it; d.persist(ctx) }, minLines = 2, maxLines = 5,
                label = { Text(stringResource(R.string.ai_prompt)) }, placeholder = { Text(stringResource(R.string.ai_prompt_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.seeds, onValueChange = { d.seeds = it; d.persist(ctx) }, minLines = 1, maxLines = 5,
                label = { Text(stringResource(R.string.ai_seeds)) }, placeholder = { Text(stringResource(R.string.ai_seeds_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.ai_count, d.count.toInt()), style = MaterialTheme.typography.bodyMedium)
            Slider(value = d.count, onValueChange = { d.count = it }, onValueChangeFinished = { d.persist(ctx) }, valueRange = 5f..100f, steps = 18)
            Text(stringResource(R.string.ai_target), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                targets.forEach { p ->
                    FilterChip(selected = p.id == target.id, onClick = { d.targetId = p.id; d.persist(ctx) }, label = { Text(p.label(ctx)) }, leadingIcon = { ProviderDot(p) })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.name, onValueChange = { d.name = it; d.persist(ctx) }, singleLine = true,
                label = { Text(stringResource(R.string.editor_new_playlist_name)) }, placeholder = { Text(stringResource(R.string.ai_default_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (d.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ai_generating), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(8.dp))
                }
                Button(enabled = !d.busy && d.prompt.isNotBlank(), onClick = {
                    d.busy = true
                    val app = ctx.applicationContext
                    val seedLines = d.seeds.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val request = AiClient.Prompt(d.prompt, d.count.toInt(), seedLines, java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH))
                    d.scope.launch {
                        val outcome = runCatching { AiClient.generate(c, request) }
                        d.busy = false
                        outcome.onFailure { e -> d.error = e.message ?: app.getString(R.string.error_generic) }
                        val tracks = outcome.getOrNull() ?: return@launch
                        if (tracks.isEmpty()) d.error = app.getString(R.string.ai_empty) else d.proposed = tracks
                    }
                }) { Text(stringResource(R.string.ai_generate)) }
            }
        }
    }
}

/** "Fornitore · modello  Cambia": il modello si sceglie da un menu letto dall'API, il resto nel dialogo. */
@Composable
private fun ModelRow(c: AiClient.Config, onChange: () -> Unit, onModel: (String) -> Unit) {
    val ctx = LocalContext.current
    val d = AiDraft
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(c.vendor.label + " · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            TextButton(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), onClick = {
                if (d.models.isNotEmpty()) { open = true; return@TextButton }
                loading = true
                d.scope.launch {
                    val r = runCatching { AiClient.models(c) }
                    loading = false
                    r.onSuccess { d.models = it; if (it.isEmpty()) d.error = ctx.getString(R.string.ai_models_none) else open = true }
                        .onFailure { e -> d.error = e.message ?: ctx.getString(R.string.error_generic) }
                }
            }) {
                if (loading) { CircularProgressIndicator(Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                Text(c.model, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.ai_model))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                d.models.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { open = false; onModel(m) }) }
            }
        }
        TextButton(onClick = onChange) { Text(stringResource(R.string.ai_setup_change)) }
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
            TextButton(enabled = current().complete, onClick = { AiClient.save(ctx, current()); AiDraft.models = emptyList(); onSaved(current()) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { AiClient.clear(ctx); AiDraft.models = emptyList(); onSaved(null) }) { Text(stringResource(R.string.ai_forget), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** La lista proposta dall'AI: si toglie quello che non si vuole, poi si conferma. */
@Composable
private fun AiReviewDialog(tracks: List<Track>, targetLabel: String, onDismiss: () -> Unit, onConfirm: (List<Track>) -> Unit) {
    var kept by remember { mutableStateOf(tracks) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_review_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ai_review_hint, targetLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(kept, key = { it.id }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(t.artistLine, t.album).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { kept = kept - t }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_review_remove)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = kept.isNotEmpty(), onClick = { onConfirm(kept) }) { Text(pluralStringResource(R.plurals.ai_review_confirm, kept.size, kept.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
