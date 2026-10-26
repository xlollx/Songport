package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
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

/**
 * The small service mark in a chip: colour and initial, never the colour alone, and it tells
 * TalkBack which service it is. The chip's label carries the account name next to it.
 */
@Composable
fun ProviderDot(p: MusicProvider?, sizeDp: Int = 18) = ProviderBadge(p, sizeDp.dp)

/** Cerchio nel colore del servizio con la sua iniziale: si riconosce a colpo d'occhio e si legge con TalkBack. */
@Composable
fun ProviderBadge(p: MusicProvider?, size: Dp = 36.dp) {
    val bg = if (p != null) Color(p.brandColor) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (bg.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White
    val letter = p?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val name = p?.displayName ?: ""
    Box(
        Modifier.size(size).clip(CircleShape).background(bg)
            .semantics { if (name.isNotEmpty()) contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            color = fg,
            fontWeight = FontWeight.Bold,
            style = when {
                size >= 32.dp -> MaterialTheme.typography.titleMedium
                size >= 24.dp -> MaterialTheme.typography.labelLarge
                else -> MaterialTheme.typography.labelSmall
            },
            // A letter on an 18dp mark: tighter than the scale, or it will not fit.
            fontSize = if (size < 24.dp) 10.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
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

/**
 * Scheda con titolo opzionale: raggruppa le impostazioni in blocchi leggibili. The title is text,
 * not an action, so it is in the text colour: the accent is kept for what can be tapped.
 */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/**
 * A settings row that is one big switch: title, note and the control, with the whole row as the
 * touch target (56dp at least) and read out by TalkBack as a single switch.
 */
@Composable
fun SwitchRow(
    title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { v ->
                haptics.performHapticFeedback(if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(v)
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        // The row owns the toggle; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A settings row with a control on the right (button, value...). When [onClick] is given the whole
 * row is the target, as in Android's own settings; otherwise the control is.
 */
@Composable
fun SettingRow(title: String, desc: String, onClick: (() -> Unit)? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

/** A light tap you can feel, for the actions that start or save something. */
@Composable
fun rememberConfirmHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
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

/**
 * Intestazione con la sfumatura del brand: titolo, sottotitolo e una riga di azioni. The gradient
 * is the brand's whatever the colour scheme (Material You too): it is the one place the identity
 * lives. White text at full alpha, and the gradient ends on a deep magenta, so it reads at AA.
 */
@Composable
fun HeroHeader(title: String, subtitle: String, content: @Composable RowScope.() -> Unit = {}) {
    Box(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Brand.Indigo, Brand.Violet, Brand.MagentaDeep)))
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30.dp, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
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
fun playlistDisplayName(playlistId: String?, name: String): String = when (playlistId) {
    // The heart marks the service's own default list, the one every app has.
    MusicProvider.LIKED_ID -> "\u2665 " + stringResource(R.string.liked_songs)
    MusicProvider.ALBUMS_ID -> stringResource(R.string.saved_albums)
    MusicProvider.ARTISTS_ID -> stringResource(R.string.followed_artists)
    MusicProvider.PODCASTS_ID -> stringResource(R.string.followed_podcasts)
    MusicProvider.RECENT_ID -> stringResource(R.string.recently_played)
    else -> name
}
