package com.xlollx.songport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Durations
import kotlinx.coroutines.launch

/** Riga compatta "titolo · artista · album · durata" di un brano. */
@Composable
fun TrackLine(t: Track, modifier: Modifier = Modifier, emphasis: Boolean = false) {
    Column(modifier) {
        Text(t.title, style = if (emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(t.artistLine.ifBlank { null }, t.album.ifBlank { null }, Durations.format(t.durationMs).ifBlank { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Campo di ricerca sulla destinazione con elenco dei candidati e pulsante "Aggiungi"/"Usa questo".
 * Riusato dalla revisione degli abbinamenti (prima e dopo la sync) e dai brani non trovati.
 */
@Composable
fun MatchSearch(
    initialQuery: String,
    targetName: String,
    pickLabel: String,
    search: suspend (String) -> List<Track>,
    onPick: suspend (Track) -> Unit,
    onError: (String) -> Unit,
    autoSearch: Boolean = false,
    extraActions: @Composable (busy: Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var candidates by remember { mutableStateOf<List<Track>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Aperto per sistemare un brano: la prima ricerca parte da sola, un tocco in meno per ogni brano.
    LaunchedEffect(Unit) {
        if (autoSearch && candidates == null && query.isNotBlank()) {
            busy = true
            candidates = try { search(query) } catch (e: Exception) { onError(e.message ?: ""); emptyList() }
            busy = false
        }
    }

    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true, enabled = !busy,
        label = { Text(stringResource(R.string.unmatched_search_hint, targetName)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        extraActions(busy)
        Button(enabled = !busy && query.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                candidates = try { search(query) } catch (e: Exception) { onError(e.message ?: ""); emptyList() }
                busy = false
            }
        }) { Text(stringResource(R.string.unmatched_search)) }
    }
    candidates?.let { list ->
        if (list.isEmpty()) Text(stringResource(R.string.unmatched_no_results), style = MaterialTheme.typography.bodySmall)
        list.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TrackLine(c, Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { onPick(c) } catch (e: Exception) { onError(e.message ?: "") }
                        busy = false
                    }
                }) { Text(pickLabel) }
            }
        }
    }
}

