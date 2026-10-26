package com.xlollx.songport.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xlollx.songport.providers.*

/**
 * The services' marks, drawn as a white glyph on a circle in the service's colour.
 *
 * Glyph paths are from Simple Icons 15.0.0 (https://simpleicons.org, CC0 1.0). The marks are the
 * services' trademarks and are used only to show which service a playlist or an account belongs to.
 * Services Simple Icons does not carry (Amazon Music, Deezer, ListenBrainz) keep their initial;
 * personal servers without a mark of their own (Subsonic/Navidrome) and files get a generic icon.
 */
object ServiceLogos {
    private const val SPOTIFY = "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"
    private const val APPLE_MUSIC = "M23.994 6.124a9.23 9.23 0 00-.24-2.19c-.317-1.31-1.062-2.31-2.18-3.043a5.022 5.022 0 00-1.877-.726 10.496 10.496 0 00-1.564-.15c-.04-.003-.083-.01-.124-.013H5.986c-.152.01-.303.017-.455.026-.747.043-1.49.123-2.193.4-1.336.53-2.3 1.452-2.865 2.78-.192.448-.292.925-.363 1.408-.056.392-.088.785-.1 1.18 0 .032-.007.062-.01.093v12.223c.01.14.017.283.027.424.05.815.154 1.624.497 2.373.65 1.42 1.738 2.353 3.234 2.801.42.127.856.187 1.293.228.555.053 1.11.06 1.667.06h11.03a12.5 12.5 0 001.57-.1c.822-.106 1.596-.35 2.295-.81a5.046 5.046 0 001.88-2.207c.186-.42.293-.87.37-1.324.113-.675.138-1.358.137-2.04-.002-3.8 0-7.595-.003-11.393zm-6.423 3.99v5.712c0 .417-.058.827-.244 1.206-.29.59-.76.962-1.388 1.14-.35.1-.706.157-1.07.173-.95.045-1.773-.6-1.943-1.536a1.88 1.88 0 011.038-2.022c.323-.16.67-.25 1.018-.324.378-.082.758-.153 1.134-.24.274-.063.457-.23.51-.516a.904.904 0 00.02-.193c0-1.815 0-3.63-.002-5.443a.725.725 0 00-.026-.185c-.04-.15-.15-.243-.304-.234-.16.01-.318.035-.475.066-.76.15-1.52.303-2.28.456l-2.325.47-1.374.278c-.016.003-.032.01-.048.013-.277.077-.377.203-.39.49-.002.042 0 .086 0 .13-.002 2.602 0 5.204-.003 7.805 0 .42-.047.836-.215 1.227-.278.64-.77 1.04-1.434 1.233-.35.1-.71.16-1.075.172-.96.036-1.755-.6-1.92-1.544-.14-.812.23-1.685 1.154-2.075.357-.15.73-.232 1.108-.31.287-.06.575-.116.86-.177.383-.083.583-.323.6-.714v-.15c0-2.96 0-5.922.002-8.882 0-.123.013-.25.042-.37.07-.285.273-.448.546-.518.255-.066.515-.112.774-.165.733-.15 1.466-.296 2.2-.444l2.27-.46c.67-.134 1.34-.27 2.01-.403.22-.043.442-.088.663-.106.31-.025.523.17.554.482.008.073.012.148.012.223.002 1.91.002 3.822 0 5.732z"
    private const val YOUTUBE_MUSIC = "M12 0C5.376 0 0 5.376 0 12s5.376 12 12 12 12-5.376 12-12S18.624 0 12 0zm0 19.104c-3.924 0-7.104-3.18-7.104-7.104S8.076 4.896 12 4.896s7.104 3.18 7.104 7.104-3.18 7.104-7.104 7.104zm0-13.332c-3.432 0-6.228 2.796-6.228 6.228S8.568 18.228 12 18.228s6.228-2.796 6.228-6.228S15.432 5.772 12 5.772zM9.684 15.54V8.46L15.816 12l-6.132 3.54z"
    private const val YOUTUBE = "M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"
    private const val TIDAL = "M12.012 3.992L8.008 7.996 4.004 3.992 0 7.996 4.004 12l4.004-4.004L12.012 12l-4.004 4.004 4.004 4.004 4.004-4.004L12.012 12l4.004-4.004-4.004-4.004zM16.042 7.996l3.979-3.979L24 7.996l-3.979 3.979z"
    private const val PLEX = "M3.987 8.409c-.96 0-1.587.28-2.12.933v-.72H0v8.88s.038.018.127.037c.138.03.821.187 1.331-.249.441-.377.542-.814.542-1.318v-1.283c.533.573 1.147.813 2 .813 1.84 0 3.253-1.493 3.253-3.48 0-2.12-1.36-3.613-3.266-3.613Zm16.748 5.595.406.591c.391.614.894.906 1.492.908.621-.012 1.064-.562 1.226-.755 0 0-.307-.27-.686-.72-.517-.614-1.214-1.755-1.24-1.803l-1.198 1.779Zm-3.205-1.955c0-2.08-1.52-3.64-3.52-3.64s-3.467 1.587-3.467 3.573a3.48 3.48 0 0 0 3.507 3.52c1.413 0 2.626-.84 3.253-2.293h-2.04l-.093.093c-.427.4-.72.533-1.227.533-.787 0-1.373-.506-1.453-1.266h4.986c.04-.214.054-.307.054-.52Zm-7.671-.219c0 .769.11 1.701.868 2.722l.056.069c-.306.526-.742.88-1.248.88-.399 0-.814-.211-1.138-.579a2.177 2.177 0 0 1-.538-1.441V6.409H9.86l-.001 5.421Zm9.283 3.46h-2.39l2.247-3.332-2.247-3.335h2.39l2.248 3.335-2.248 3.332Zm1.593-1.286Zm-17.162-.342c-.933 0-1.68-.773-1.68-1.72s.76-1.666 1.68-1.666c.92 0 1.68.733 1.68 1.68 0 .946-.733 1.706-1.68 1.706Zm18.361-1.974L24 8.622h-2.391l-.87 1.293 1.195 1.773Zm-9.404-.466c.16-.706.72-1.133 1.493-1.133.773 0 1.373.467 1.507 1.133h-3Z"
    private const val JELLYFIN = "M12 .002C8.826.002-1.398 18.537.16 21.666c1.56 3.129 22.14 3.094 23.682 0C25.384 18.573 15.177 0 12 0zm7.76 18.949c-1.008 2.028-14.493 2.05-15.514 0C3.224 16.9 9.92 4.755 12.003 4.755c2.081 0 8.77 12.166 7.759 14.196zM12 9.198c-1.054 0-4.446 6.15-3.93 7.189.518 1.04 7.348 1.027 7.86 0 .511-1.027-2.874-7.19-3.93-7.19z"
    private const val LASTFM = "M10.584 17.21l-.88-2.392s-1.43 1.594-3.573 1.594c-1.897 0-3.244-1.649-3.244-4.288 0-3.382 1.704-4.591 3.381-4.591 2.42 0 3.189 1.567 3.849 3.574l.88 2.749c.88 2.666 2.529 4.81 7.285 4.81 3.409 0 5.718-1.044 5.718-3.793 0-2.227-1.265-3.381-3.63-3.931l-1.758-.385c-1.21-.275-1.567-.77-1.567-1.595 0-.934.742-1.484 1.952-1.484 1.32 0 2.034.495 2.144 1.677l2.749-.33c-.22-2.474-1.924-3.492-4.729-3.492-2.474 0-4.893.935-4.893 3.932 0 1.87.907 3.051 3.189 3.601l1.87.44c1.402.33 1.869.907 1.869 1.704 0 1.017-.99 1.43-2.86 1.43-2.776 0-3.93-1.457-4.59-3.464l-.907-2.75c-1.155-3.573-2.997-4.893-6.653-4.893C2.144 5.333 0 7.89 0 12.233c0 4.18 2.144 6.434 5.993 6.434 3.106 0 4.591-1.457 4.591-1.457z"

    private val cache = HashMap<String, ImageVector>()

    /** The brand glyph for a service id (bridge and web variants share their brand's), or null. */
    fun glyph(serviceId: String?): ImageVector? {
        val path = when (serviceId) {
            "spotify", "spotify_bridge" -> SPOTIFY
            "apple", "apple_bridge" -> APPLE_MUSIC
            "ytm" -> YOUTUBE_MUSIC
            "youtube" -> YOUTUBE
            "tidal" -> TIDAL
            "plex" -> PLEX
            "jellyfin" -> JELLYFIN
            "lastfm" -> LASTFM
            else -> return null
        }
        return synchronized(cache) {
            cache.getOrPut(serviceId!!) {
                ImageVector.Builder(serviceId, 24.dp, 24.dp, 24f, 24f)
                    .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
                    .build()
            }
        }
    }
}

/**
 * The service's mark: its glyph in white on a circle of its colour; a generic icon for files and
 * personal servers without a mark; the initial otherwise. TalkBack reads the service's name.
 */
@Composable
fun ServiceLogo(p: MusicProvider?, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val bg = if (p != null) Color(p.brandColor) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (bg.luminance() > 0.45f) Color(0xFF1C1B22) else Color.White
    val name = p?.displayName ?: ""
    // A near-black mark (TIDAL) on a dark surface needs an edge to be seen.
    val edge = bg.luminance() < 0.03f
    Box(
        modifier.size(size).clip(CircleShape).background(bg)
            .then(if (edge) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier)
            .semantics { if (name.isNotEmpty()) contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val glyph = ServiceLogos.glyph(p?.serviceId)
        val generic = when (p?.serviceId) { "csv" -> Icons.Filled.Folder; "subsonic" -> Icons.Filled.Dns; else -> null }
        when {
            glyph != null -> Image(glyph, null, Modifier.size(size * 0.56f), colorFilter = ColorFilter.tint(fg))
            generic != null -> Icon(generic, null, tint = fg, modifier = Modifier.size(size * 0.56f))
            else -> Text(
                p?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.46f).sp,
            )
        }
    }
}

/** One choice in [ServiceTiles]: a service, or an extra entry such as "Link" with its own icon. */
class ServiceTile(val key: String, val label: String, val provider: MusicProvider? = null, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val detail: String? = null)

/**
 * The service picker: a horizontal row of tiles, the mark large enough to recognise at a glance
 * and the name under it; the selected tile takes the tonal container and a check. Scrolls when
 * the services do not fit, instead of wrapping into uneven rows of chips.
 */
@Composable
fun ServiceTiles(tiles: List<ServiceTile>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(tiles.size, key = { tiles[it].key }) { i ->
            val t = tiles[i]
            val on = t.key == selected
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            androidx.compose.material3.Surface(
                onClick = { if (!on) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onSelect(t.key) },
                shape = shape,
                color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.semantics { this.selected = on }.size(width = 96.dp, height = 104.dp),
            ) {
                Box {
                    androidx.compose.foundation.layout.Column(
                        Modifier.align(Alignment.Center).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (t.provider != null) ServiceLogo(t.provider, 40.dp)
                        else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                            t.icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        Text(
                            t.label, style = MaterialTheme.typography.labelMedium, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                        t.detail?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (on) Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

/** The usual service tiles for a list of connected providers. */
@Composable
fun ServiceTiles(providers: List<MusicProvider>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ServiceTiles(providers.map { ServiceTile(it.id, it.label(ctx), it) }, selectedId, onSelect, modifier)
}

