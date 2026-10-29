package com.xlollx.songport.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xlollx.songport.R

/**
 * The colours a sync card can wear, so someone with many syncs tells them apart at a glance.
 * Stored by key (SyncJob.color), never by value: the shade follows light and dark mode.
 */
enum class JobColor(val key: String, @StringRes val label: Int, val light: Color, val dark: Color) {
    RED("red", R.string.color_red, Color(0xFFE5484D), Color(0xFFFF7A7A)),
    ORANGE("orange", R.string.color_orange, Color(0xFFF76B15), Color(0xFFFF9A5C)),
    YELLOW("yellow", R.string.color_yellow, Color(0xFFD69E00), Color(0xFFFFCB47)),
    GREEN("green", R.string.color_green, Color(0xFF2F9E62), Color(0xFF5BD18F)),
    TEAL("teal", R.string.color_teal, Color(0xFF12A594), Color(0xFF3DD6C3)),
    BLUE("blue", R.string.color_blue, Color(0xFF0B84E8), Color(0xFF63B3FF)),
    PURPLE("purple", R.string.color_purple, Color(0xFF8E4EC6), Color(0xFFB98AF0)),
    PINK("pink", R.string.color_pink, Color(0xFFD6409F), Color(0xFFF57CC6));

    companion object {
        fun of(key: String?): JobColor? = entries.firstOrNull { it.key == key }
    }
}

/** The colour itself in the current mode, or null for the default look. */
@Composable
fun jobAccent(key: String?): Color? = JobColor.of(key)?.let { if (LocalAppDark.current) it.dark else it.light }

/** A card's background in that colour: the usual surface, tinted just enough to tell. */
@Composable
fun jobContainer(key: String?): Color {
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = jobAccent(key) ?: return base
    return lerp(base, accent, if (LocalAppDark.current) 0.20f else 0.13f)
}

/**
 * A card's menu in the card's colour: the menu surface, tinted a little more than the card so it
 * stands out above it. The plain menu surface for a sync with no colour. A plain mix, no blend mode.
 */
@Composable
fun jobMenuContainer(key: String?): Color {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val accent = jobAccent(key) ?: return base
    return lerp(base, accent, if (LocalAppDark.current) 0.24f else 0.16f)
}

/** The sync's colour for its controls (figures, run button): a gradient of it, or null for the default look. */
@Composable
fun jobControlBrush(key: String?): Brush? {
    val accent = jobAccent(key) ?: return null
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val dark = LocalAppDark.current
    return Brush.linearGradient(listOf(lerp(base, accent, if (dark) 0.45f else 0.34f), lerp(base, accent, if (dark) 0.16f else 0.10f)))
}

/** Text and icons in the sync's colour, deep enough to read on its gradient. */
@Composable
fun jobInk(key: String?): Color? = jobAccent(key)?.let { if (LocalAppDark.current) it else lerp(it, Color.Black, 0.3f) }

/** One dot per colour plus the default, the chosen one ticked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun JobColorPicker(selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch(MaterialTheme.colorScheme.surfaceContainerHighest, stringResource(R.string.job_color_default), selected == null) { onSelect(null) }
        JobColor.entries.forEach { c ->
            Swatch(if (LocalAppDark.current) c.dark else c.light, stringResource(c.label), selected == c.key) { onSelect(c.key) }
        }
    }
}

@Composable
private fun Swatch(color: Color, name: String, chosen: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(color)
            .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = if (color.luminanceIsDark()) Color.White else Color.Black)
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.6f

/** The picker on its own, from a card's menu. */
@Composable
fun JobColorDialog(selected: String?, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.job_color)) },
        text = { JobColorPicker(selected) { onSelect(it); onDismiss() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
