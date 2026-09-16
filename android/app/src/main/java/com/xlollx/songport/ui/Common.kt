package com.xlollx.songport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------------
// Servizi
// ---------------------------------------------------------------------------------------------

@Composable
fun ProviderLabel(providerId: String, detail: String? = null, modifier: Modifier = Modifier) {
    val p = Providers.byId(providerId)
    val ctx = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ProviderBadge(p, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = buildString {
                append(p?.label(ctx) ?: providerId)
                if (!detail.isNullOrBlank()) append(" · ").append(detail)
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ProviderDot(p: MusicProvider?, sizeDp: Int = 10) {
    Spacer(
        Modifier.size(sizeDp.dp).clip(CircleShape)
            .background(if (p != null) Color(p.brandColor) else MaterialTheme.colorScheme.outline)
    )
}

/** Cerchio nel colore del servizio con la sua iniziale: si riconosce a colpo d'occhio. */
@Composable
fun ProviderBadge(p: MusicProvider?, size: Dp = 36.dp) {
    val bg = if (p != null) Color(p.brandColor) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (bg.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White
    val letter = p?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            letter,
            color = fg,
            fontWeight = FontWeight.Bold,
            style = if (size >= 32.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Blocchi di interfaccia condivisi
// ---------------------------------------------------------------------------------------------

enum class Tone { Ok, Error, Neutral, Accent }

/** Etichetta di stato: colore + icona + testo, mai solo il colore. */
@Composable
fun StatusPill(text: String, tone: Tone, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val bg = when (tone) {
        Tone.Ok -> Tones.successContainer()
        Tone.Error -> MaterialTheme.colorScheme.errorContainer
        Tone.Accent -> MaterialTheme.colorScheme.primaryContainer
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when (tone) {
        Tone.Ok -> Tones.onSuccessContainer()
        Tone.Error -> MaterialTheme.colorScheme.onErrorContainer
        Tone.Accent -> MaterialTheme.colorScheme.onPrimaryContainer
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Schermata vuota: un'icona, una frase, al massimo un pulsante. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (ctaLabel != null && onCta != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCta) { Text(ctaLabel) }
        }
    }
}

/** Scheda con titolo opzionale: raggruppa le impostazioni in blocchi leggibili. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

class MenuAction(val label: String, val onClick: () -> Unit, val destructive: Boolean = false, val enabled: Boolean = true)

/** Le azioni secondarie stanno dietro un solo pulsante "Altro": la scheda resta pulita. */
@Composable
fun OverflowMenu(actions: List<MenuAction>, enabled: Boolean = true) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a.destructive) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    enabled = a.enabled,
                    onClick = { open = false; a.onClick() },
                )
            }
        }
    }
}

/** Il marchio Songport (nota nell'anello), tinto con il colore che serve. */
@Composable
fun BrandMark(size: Dp = 28.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Icon(painterResource(R.drawable.ic_brand_mark), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, showBrand: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBrand) {
                    BrandMark(26.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** Intestazione con la sfumatura del brand: titolo, sottotitolo e una riga di azioni. */
@Composable
fun HeroHeader(title: String, subtitle: String, content: @Composable RowScope.() -> Unit = {}) {
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Brand.Indigo, Brand.Violet, Brand.Magenta)))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30.dp, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Testi
// ---------------------------------------------------------------------------------------------

@Composable
fun scheduleLabel(s: Schedule): String = stringResource(
    when (s) {
        Schedule.MANUAL -> R.string.schedule_manual
        Schedule.HOURLY -> R.string.schedule_hourly
        Schedule.EVERY_6H -> R.string.schedule_6h
        Schedule.DAILY -> R.string.schedule_daily
        Schedule.WEEKLY -> R.string.schedule_weekly
    }
)

fun formatDate(epoch: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))

@Composable
fun playlistDisplayName(playlistId: String?, name: String): String =
    if (playlistId == MusicProvider.LIKED_ID) stringResource(R.string.liked_songs) else name
