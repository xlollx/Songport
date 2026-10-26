package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.Compatibility
import com.xlollx.songport.sync.LinkResolver
import com.xlollx.songport.sync.PlaylistLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A playlist chosen for a card: the service it lives on and what is known about it. */
class CardPick(val provider: MusicProvider, val playlist: Playlist, val pastedLink: String? = null)

/**
 * Where a card's playlist comes from: a pasted public link, or one of the user's own playlists on
 * a connected service. [initialLink] pre-fills and resolves a link handed in from the share sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PlaylistSource(label: String, initialLink: String?, snackbar: SnackbarHostState, onPick: (CardPick?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = remember { Providers.connectors().filter { it.requiresAuth && it.isConnected(ctx) && it.serviceId != "lastfm" && it.serviceId != "listenbrainz" } }
    var mode by remember { mutableStateOf<String?>(null) } // null = link, else provider id
    var link by remember { mutableStateOf(initialLink.orEmpty()) }
    var picked by remember { mutableStateOf<CardPick?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    var expanded by remember { mutableStateOf(false) }

    fun choose(p: CardPick?) { picked = p; onPick(p) }

    suspend fun resolve(text: String) {
        busy = true
        val ref = LinkResolver.resolve(ctx, text)
        val named = ref?.let { Providers.byId(it.providerId) }
        val provider = ref?.let { r ->
            named?.takeIf { it.canRead(ctx, r.playlistId) }
                ?: Providers.connectors().firstOrNull { it.familyName == named?.familyName && it.canRead(ctx, r.playlistId) }
        }
        when {
            ref == null -> snackbar.showSnackbar(ctx.getString(R.string.link_invalid))
            provider == null -> snackbar.showSnackbar(ctx.getString(R.string.link_not_connected, named?.displayName ?: ref.providerId))
            else -> {
                val info = runCatching { provider.playlistInfo(ctx, ref.playlistId) }.getOrElse { Playlist(ref.playlistId, ctx.getString(R.string.card_unnamed_playlist), ownedByMe = false) }
                choose(CardPick(provider, info, text.trim()))
            }
        }
        busy = false
    }

    LaunchedEffect(initialLink) { if (!initialLink.isNullOrBlank()) resolve(initialLink) }
    LaunchedEffect(mode) {
        val id = mode ?: return@LaunchedEffect
        val p = connected.firstOrNull { it.id == id } ?: return@LaunchedEffect
        lists = null
        lists = try { p.libraryEntries(ctx, asTarget = false) + p.playlists(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic)); emptyList()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = mode == null, onClick = { mode = null; choose(null) }, label = { Text(stringResource(R.string.card_source_link)) })
                connected.forEach { p ->
                    FilterChip(selected = mode == p.id, onClick = { mode = p.id; choose(null) }, label = { Text(p.label(ctx)) }, leadingIcon = { ProviderDot(p) })
                }
            }
            if (mode == null) {
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.card_source_link_hint)) },
                    trailingIcon = {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                        else TextButton(enabled = link.isNotBlank(), onClick = { scope.launch { resolve(link) } }) { Text(stringResource(R.string.link_use)) }
                    },
                )
            } else {
                val items = lists
                if (items == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.loading)) }
                else ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = picked?.playlist?.name ?: "", onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(R.string.editor_playlist)) }, placeholder = { Text(stringResource(R.string.editor_choose_playlist)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        items.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = {
                                expanded = false
                                connected.firstOrNull { it.id == mode }?.let { choose(CardPick(it, p)) }
                            })
                        }
                    }
                }
            }
            picked?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderDot(p.provider); Spacer(Modifier.width(6.dp))
                    Text(p.playlist.name.ifBlank { stringResource(R.string.card_unnamed_playlist) } + (if (p.playlist.trackCount >= 0) "  ·  ${p.playlist.trackCount}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Share and save buttons for a rendered card, with the story/square switch. */
@Composable
private fun CardActions(bitmaps: Map<Cards.Format, Bitmap>?, name: String, busy: Boolean, onRender: () -> Unit, renderLabel: String) {
    val ctx = LocalContext.current
    var format by remember { mutableStateOf(Cards.Format.STORY) }
    val bmp = bitmaps?.get(format)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null && bmp != null) Cards.save(ctx, bmp, uri)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
        Button(enabled = !busy, onClick = onRender) { Text(renderLabel) }
    }
    if (bmp != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = format == Cards.Format.STORY, onClick = { format = Cards.Format.STORY }, label = { Text(stringResource(R.string.card_format_story)) })
            FilterChip(selected = format == Cards.Format.SQUARE, onClick = { format = Cards.Format.SQUARE }, label = { Text(stringResource(R.string.card_format_square)) })
        }
        Image(
            bmp.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(format.width.toFloat() / format.height).clip(RoundedCornerShape(16.dp)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Cards.share(ctx, bmp, name) }) { Text(stringResource(R.string.card_share)) }
            OutlinedButton(onClick = { save.launch("$name.png") }) { Text(stringResource(R.string.card_save)) }
        }
    }
}

/** The playlist's web address for a QR: the service's page, or the user's own server. */
private fun qrUrl(ctx: android.content.Context, pick: CardPick): String? {
    pick.pastedLink?.let { l -> Regex("""https?://\S+""").find(l)?.value?.let { return it } }
    val tokens = ctx.env.tokens.get(pick.provider.id)
    val base = tokens?.extra?.get("url")
    val region = when (pick.provider.serviceId) {
        "apple" -> tokens?.extra?.get("storefront")
        "amazon" -> pick.provider.webSearchUrl(ctx, "")?.let { Regex("""music\.amazon\.([a-z.]+)/""").find(it)?.groupValues?.get(1) }
        else -> null
    }
    return PlaylistLinks.webUrl(pick.provider.id, pick.playlist.id, base, region)
}

/** "How compatible are we?": two playlists in, a score and a card out. */
@Composable
fun CompatibilityCard(snackbar: SnackbarHostState, initialLinks: Pair<String, String>? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf<CardPick?>(null) }
    var b by remember { mutableStateOf<CardPick?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Compatibility.Result?>(null) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }

    fun run() {
        val pa = a ?: return; val pb = b ?: return
        busy = true; result = null; bitmaps = null
        scope.launch {
            try {
                val (ta, tb) = coroutineScope {
                    val da = async { pa.provider.tracks(ctx, pa.playlist.id) }
                    val db = async { pb.provider.tracks(ctx, pb.playlist.id) }
                    da.await() to db.await()
                }
                val r = withContext(Dispatchers.Default) { Compatibility.compare(ta, tb) }
                result = r
                val covers = withContext(Dispatchers.IO) {
                    coroutineScope { r.common.take(5).map { c -> async { c.a.id to Cards.trackCover(c.a, pa.provider.serviceId) } }.map { it.await() } }.toMap()
                }
                val card = Cards.CompatibilityCard(pa.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }, pb.playlist.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) }, r, covers)
                bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.compatibility(ctx, card, it) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            }
            busy = false
        }
    }

    // Two links shared together: compared as soon as both resolve.
    var autoRan by remember { mutableStateOf(false) }
    LaunchedEffect(a, b) { if (initialLinks != null && !autoRan && a != null && b != null) { autoRan = true; run() } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tools_compat_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlaylistSource(stringResource(R.string.card_compat_first), initialLinks?.first, snackbar) { a = it }
        PlaylistSource(stringResource(R.string.card_compat_second), initialLinks?.second, snackbar) { b = it }
        val r = result
        if (r != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.score}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.card_compat_subtitle, r.common.size, r.sharedArtists.size), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.card_compat_common_pct, r.commonPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.common.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.card_compat_in_common), style = MaterialTheme.typography.titleSmall)
                        r.common.take(8).forEach { Text("• ${it.a}", style = MaterialTheme.typography.bodySmall) }
                        if (r.common.size > 8) Text(stringResource(R.string.preview_more, r.common.size - 8), style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.sharedArtists.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.card_compat_artists), style = MaterialTheme.typography.titleSmall)
                        Text(r.sharedArtists.take(12).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    Suggestions(stringResource(R.string.card_compat_for, a?.playlist?.name.orEmpty()), r.forA)
                    Suggestions(stringResource(R.string.card_compat_for, b?.playlist?.name.orEmpty()), r.forB)
                }
            }
        }
        CardActions(bitmaps, "songport-compatibility", busy || a == null || b == null, onRender = { run() }, renderLabel = stringResource(if (result == null) R.string.card_compat_run else R.string.card_again))
    }
}

@Composable
private fun Suggestions(title: String, tracks: List<Track>) {
    if (tracks.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    tracks.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
}

/** The playlist poster: covers, title, numbers and a QR to open it. */
@Composable
fun PosterCard(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf<CardPick?>(null) }
    var busy by remember { mutableStateOf(false) }
    var bitmaps by remember { mutableStateOf<Map<Cards.Format, Bitmap>?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }

    fun run() {
        val p = pick ?: return
        busy = true; bitmaps = null
        scope.launch {
            try {
                val tracks = p.provider.tracks(ctx, p.playlist.id)
                val info = if (p.playlist.trackCount >= 0 && p.playlist.name.isNotBlank()) p.playlist else runCatching { p.provider.playlistInfo(ctx, p.playlist.id) }.getOrDefault(p.playlist)
                val covers: List<Bitmap?> = withContext(Dispatchers.IO) {
                    info.imageUrl?.let { url -> Cards.bitmap(url, 900)?.let { return@withContext listOf(it) } }
                    coroutineScope { tracks.take(4).map { t -> async { Cards.trackCover(t, p.provider.serviceId) } }.map { it.await() } }
                }
                qr = qrUrl(ctx, p)
                val card = Cards.PosterCard(
                    title = info.name.ifBlank { ctx.getString(R.string.card_unnamed_playlist) },
                    owner = if (info.ownedByMe) p.provider.accountName(ctx).orEmpty() else "",
                    service = p.provider.displayName,
                    trackCount = tracks.size, durationMs = tracks.sumOf { it.durationMs },
                    covers = covers, qr = qr,
                )
                bitmaps = withContext(Dispatchers.Default) { Cards.Format.entries.associateWith { Cards.poster(ctx, card, it) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: ctx.getString(R.string.error_generic))
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tools_poster_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlaylistSource(stringResource(R.string.editor_playlist), null, snackbar) { pick = it; bitmaps = null }
        if (bitmaps != null && qr == null) Text(stringResource(R.string.card_poster_no_qr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CardActions(bitmaps, "songport-poster", busy || pick == null, onRender = { run() }, renderLabel = stringResource(if (bitmaps == null) R.string.card_poster_run else R.string.card_again))
    }
}
